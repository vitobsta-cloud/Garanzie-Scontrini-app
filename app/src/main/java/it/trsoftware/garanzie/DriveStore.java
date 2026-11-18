package it.trsoftware.garanzie;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Destinazione: Google Drive, cartella "Garanzie e Scontrini" nel Drive dell'utente.
 * Usa il permesso "drive.file": l'app vede solo i file che ha creato lei.
 */
final class DriveStore implements Store {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final int MAX_DEPTH = 4;

    interface TokenSource {
        String get() throws Exception;
    }

    private final TokenSource tokens;
    private final SharedPreferences prefs;

    DriveStore(TokenSource tokens, SharedPreferences prefs) {
        this.tokens = tokens;
        this.prefs = prefs;
    }

    // ---------------- HTTP ----------------

    private static final class Resp {
        int code;
        byte[] body;

        String text() { return new String(body, StandardCharsets.UTF_8); }

        JSONObject json() throws Exception { return new JSONObject(text()); }
    }

    private Resp http(String method, String url, String contentType, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if ("PATCH".equals(method)) {
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(method);
        }
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + tokens.get());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? new byte[0] : readAll(in);
        c.disconnect();
        if (r.code == 401) throw new IllegalStateException("Accesso a Google scaduto: entra di nuovo in Destinazione file → Accedi con Google.");
        if (r.code == 403 && r.text().contains("storageQuota")) throw new IllegalStateException("Spazio su Google Drive esaurito.");
        if (r.code >= 400) {
            String m = r.text();
            try { m = new JSONObject(m).getJSONObject("error").optString("message", m); } catch (Exception ignored) { }
            throw new IllegalStateException("Google Drive: " + m);
        }
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String q(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

    // ---------------- cartelle ----------------

    /** Email dell'account collegato. */
    String accountEmail() throws Exception {
        return http("GET", API + "/about?fields=user(emailAddress)", null, null).json()
                .getJSONObject("user").optString("emailAddress", "");
    }

    private String findChild(String parentId, String name, boolean folder) throws Exception {
        String query = "name='" + q(name) + "' and '" + parentId + "' in parents and trashed=false"
                + (folder ? " and mimeType='" + FOLDER + "'" : " and mimeType!='" + FOLDER + "'");
        JSONArray f = http("GET", API + "/files?fields=files(id,name)&pageSize=10&q=" + enc(query), null, null)
                .json().getJSONArray("files");
        return f.length() > 0 ? f.getJSONObject(0).getString("id") : null;
    }

    private String createFolder(String parentId, String name) throws Exception {
        JSONObject meta = new JSONObject().put("name", name).put("mimeType", FOLDER)
                .put("parents", new JSONArray().put(parentId));
        return http("POST", API + "/files?fields=id", "application/json; charset=UTF-8",
                meta.toString().getBytes(StandardCharsets.UTF_8)).json().getString("id");
    }

    private String rootId() throws Exception {
        String id = prefs.getString("driveRootId", "");
        if (!id.isEmpty()) {
            try {
                JSONObject f = http("GET", API + "/files/" + id + "?fields=id,trashed", null, null).json();
                if (!f.optBoolean("trashed")) return id;
            } catch (Exception ignored) {
            }
        }
        id = findChild("root", LocalStore.ARCHIVE_NAME, true);
        if (id == null) id = createFolder("root", LocalStore.ARCHIVE_NAME);
        prefs.edit().putString("driveRootId", id).apply();
        return id;
    }

    private String dirId(String rel, boolean create) throws Exception {
        String id = rootId();
        for (String seg : Store.segments(rel)) {
            String c = findChild(id, seg, true);
            if (c == null) {
                if (!create) return null;
                c = createFolder(id, seg);
            }
            id = c;
        }
        return id;
    }

    private String upload(String parentId, String name, byte[] data) throws Exception {
        String boundary = "garanzie" + System.nanoTime();
        JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(parentId));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta + "\r\n--"
                + boundary + "\r\nContent-Type: " + Store.mimeOf(name) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bo.write(data);
        bo.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        return http("POST", UPLOAD + "?uploadType=multipart&fields=id,name", "multipart/related; boundary=" + boundary,
                bo.toByteArray()).json().getString("name");
    }

    // ---------------- Store ----------------

    @Override
    public String test() throws Exception {
        String email = accountEmail();
        String root = rootId();
        String probeName = "garanzie_test_" + System.currentTimeMillis() + ".txt";
        upload(root, probeName, "ok".getBytes(StandardCharsets.UTF_8));
        String pid = findChild(root, probeName, false);
        if (pid == null) throw new IllegalStateException("File di prova non trovato dopo la scrittura.");
        String back = http("GET", API + "/files/" + pid + "?alt=media", null, null).text();
        http("DELETE", API + "/files/" + pid, null, null);
        if (!"ok".equals(back)) throw new IllegalStateException("Lettura di prova non riuscita.");
        return "Google Drive raggiungibile e scrivibile (" + email + "), cartella \"" + LocalStore.ARCHIVE_NAME + "\".";
    }

    @Override
    public String list() throws Exception {
        String root = rootId();
        // tutti i file creati dall'app (permesso drive.file), poi l'albero in memoria
        Map<String, List<JSONObject>> children = new HashMap<>();
        String page = null;
        do {
            String url = API + "/files?pageSize=1000&q=" + enc("trashed=false")
                    + "&fields=" + enc("nextPageToken,files(id,name,mimeType,parents,size,modifiedTime)")
                    + (page != null ? "&pageToken=" + enc(page) : "");
            JSONObject r = http("GET", url, null, null).json();
            JSONArray f = r.optJSONArray("files");
            for (int i = 0; f != null && i < f.length(); i++) {
                JSONObject o = f.getJSONObject(i);
                JSONArray ps = o.optJSONArray("parents");
                if (ps == null) continue;
                for (int k = 0; k < ps.length(); k++) children.computeIfAbsent(ps.getString(k), x -> new ArrayList<>()).add(o);
            }
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);

        JSONArray out = new JSONArray();
        Map<JSONObject, String> infoIds = new HashMap<>();
        walk(children, root, "", 0, out, infoIds);

        // garanzia.json scaricati in parallelo
        ExecutorService ex = Executors.newFixedThreadPool(6);
        try {
            Map<JSONObject, Future<String>> res = new HashMap<>();
            for (Map.Entry<JSONObject, String> e : infoIds.entrySet())
                res.put(e.getKey(), ex.submit(() -> http("GET", API + "/files/" + e.getValue() + "?alt=media", null, null).text()));
            for (Map.Entry<JSONObject, Future<String>> e : res.entrySet()) {
                try { e.getKey().put("info", e.getValue().get()); } catch (Exception ignored) { }
            }
        } finally {
            ex.shutdown();
        }
        return out.toString();
    }

    private void walk(Map<String, List<JSONObject>> children, String id, String rel, int depth,
                      JSONArray out, Map<JSONObject, String> infoIds) throws Exception {
        List<JSONObject> kids = children.get(id);
        if (kids == null) return;
        JSONArray files = new JSONArray();
        String infoId = null;
        List<JSONObject> subs = new ArrayList<>();
        for (JSONObject k : kids) {
            String name = k.optString("name");
            if (Store.skip(name)) continue;
            if (FOLDER.equals(k.optString("mimeType"))) {
                if (depth < MAX_DEPTH) subs.add(k);
            } else if (INFO_FILE.equalsIgnoreCase(name)) {
                infoId = k.optString("id");
            } else if (Store.isDoc(name)) {
                long mt = 0;
                try { mt = java.time.Instant.parse(k.optString("modifiedTime")).toEpochMilli(); } catch (Exception ignored) { }
                files.put(new JSONObject().put("name", name).put("size", k.optLong("size")).put("mtime", mt));
            }
        }
        if (!rel.isEmpty() && (files.length() > 0 || infoId != null)) {
            JSONObject e = new JSONObject().put("path", rel).put("files", files);
            out.put(e);
            if (infoId != null) infoIds.put(e, infoId);
        }
        for (JSONObject s : subs) {
            String n = s.optString("name");
            walk(children, s.optString("id"), rel.isEmpty() ? n : rel + "/" + n, depth + 1, out, infoIds);
        }
    }

    @Override
    public byte[] read(String relPath) throws Exception {
        String[] seg = Store.segments(relPath);
        if (seg.length == 0) throw new IllegalArgumentException("Percorso non valido");
        String dir = dirId(String.join("/", java.util.Arrays.copyOf(seg, seg.length - 1)), false);
        String id = dir == null ? null : findChild(dir, seg[seg.length - 1], false);
        if (id == null) throw new IllegalStateException("File non trovato su Google Drive: " + relPath);
        return http("GET", API + "/files/" + id + "?alt=media", null, null).body;
    }

    @Override
    public String writeFile(String relDir, String name, byte[] data) throws Exception {
        String dir = dirId(relDir, true);
        String used = Store.uniqueName(name, n -> {
            try { return findChild(dir, n, false) != null; } catch (Exception e) { return false; }
        });
        return upload(dir, used, data);
    }

    @Override
    public void writeText(String relDir, String name, String text) throws Exception {
        String dir = dirId(relDir, true);
        String id = findChild(dir, name, false);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (id == null) upload(dir, name, data);
        else http("PATCH", UPLOAD + "/" + id + "?uploadType=media", Store.mimeOf(name), data);
    }

    /** La cartella va nel cestino di Google Drive (recuperabile per 30 giorni). */
    @Override
    public String deleteFolder(String relDir) throws Exception {
        String[] seg = Store.segments(relDir);
        if (seg.length == 0) throw new IllegalArgumentException("Cartella non valida");
        String id = dirId(relDir, false);
        if (id == null) return "già eliminata";
        http("PATCH", API + "/files/" + id, "application/json; charset=UTF-8",
                "{\"trashed\":true}".getBytes(StandardCharsets.UTF_8));
        if (seg.length > 1) {
            String parent = dirId(String.join("/", java.util.Arrays.copyOf(seg, seg.length - 1)), false);
            if (parent != null) {
                JSONArray f = http("GET", API + "/files?pageSize=1&fields=files(id)&q="
                        + enc("'" + parent + "' in parents and trashed=false"), null, null).json().getJSONArray("files");
                if (f.length() == 0) http("PATCH", API + "/files/" + parent, "application/json; charset=UTF-8",
                        "{\"trashed\":true}".getBytes(StandardCharsets.UTF_8));
            }
        }
        return "ok";
    }
}
