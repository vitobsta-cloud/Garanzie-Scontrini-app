package it.trsoftware.garanzie;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Funzioni native chiamate dalla pagina (window.AndroidBridge).
 * I metodi asincroni ricevono un id e rispondono con window.__bridgeCb(id, ok, payload).
 */
public class Bridge {

    private final Activity act;
    private final WebView web;
    private final SharedPreferences nasPrefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    /** Le operazioni sul NAS vanno in fila, una alla volta. */
    private final ExecutorService nasQueue = Executors.newSingleThreadExecutor();

    Bridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        this.nasPrefs = act.getSharedPreferences("nas", Context.MODE_PRIVATE);
    }

    private interface Job {
        String run() throws Exception;
    }

    private void reply(String id, boolean ok, String payload) {
        final String js = "window.__bridgeCb(" + JSONObject.quote(id) + "," + ok + ","
                + JSONObject.quote(payload == null ? "" : payload) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void async(ExecutorService ex, String id, Job job) {
        ex.execute(() -> {
            try {
                reply(id, true, job.run());
            } catch (Throwable t) {
                reply(id, false, friendly(t));
            }
        });
    }

    private String friendly(Throwable t) {
        if (!"nas".equals(archivio())) {
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof java.net.UnknownHostException || x instanceof java.net.ConnectException
                        || x instanceof java.net.SocketTimeoutException)
                    return "Nessuna connessione a internet.";
            }
            String m = t.getMessage();
            return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
        }
        return Nas.friendly(t);
    }

    private Nas.Cfg cfg() {
        return Nas.Cfg.load(nasPrefs);
    }

    // ---------------- destinazione dei file ----------------

    private String archivio() {
        return nasPrefs.getString("archivio", "nas");
    }

    /** La destinazione scelta: NAS, cartella sul telefono o Google Drive. */
    private Store store() {
        switch (archivio()) {
            case "locale": {
                String t = nasPrefs.getString("localTree", "");
                if (t.isEmpty()) throw new IllegalStateException("Nessuna cartella scelta: sceglila in Destinazione file.");
                return new LocalStore(act, Uri.parse(t));
            }
            case "gdrive":
                if (nasPrefs.getString("driveEmail", "").isEmpty())
                    throw new IllegalStateException("Nessun account Google collegato: accedi in Destinazione file.");
                return new DriveStore(this::driveToken, nasPrefs);
            default:
                return new NasStore(cfg());
        }
    }

    @JavascriptInterface
    public void setArchivio(String mode) {
        nasPrefs.edit().putString("archivio", mode == null || mode.isEmpty() ? "nas" : mode).apply();
    }

    // ---------------- cartella sul telefono ----------------

    @JavascriptInterface
    public String getLocalFolder() {
        String t = nasPrefs.getString("localTree", "");
        return t.isEmpty() ? "" : LocalStore.displayPath(act, Uri.parse(t));
    }

    /** Apre il selettore di cartelle di Android. */
    @JavascriptInterface
    public void pickFolder(String id) {
        act.runOnUiThread(() -> ((MainActivity) act).pickTree(new MainActivity.Result() {
            @Override public void ok(String uri) {
                Uri u = Uri.parse(uri);
                try {
                    act.getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                nasPrefs.edit().putString("localTree", uri).apply();
                reply(id, true, LocalStore.displayPath(act, u));
            }

            @Override public void fail(String msg) { reply(id, false, msg); }
        }));
    }

    // ---------------- Google Drive ----------------

    private String cachedToken;
    private long tokenTime;

    @JavascriptInterface
    public String getDriveAccount() {
        return nasPrefs.getString("driveEmail", "");
    }

    /** Accesso con Google (chiede il permesso la prima volta). */
    @JavascriptInterface
    public void driveLogin(String id) {
        act.runOnUiThread(() -> ((MainActivity) act).driveAuth(true, new MainActivity.Result() {
            @Override public void ok(String token) {
                synchronized (Bridge.this) {
                    cachedToken = token;
                    tokenTime = System.currentTimeMillis();
                }
                async(pool, id, () -> {
                    String email = new DriveStore(() -> token, nasPrefs).accountEmail();
                    nasPrefs.edit().putString("driveEmail", email).remove("driveRootId").apply();
                    return email;
                });
            }

            @Override public void fail(String msg) { reply(id, false, msg); }
        }));
    }

    @JavascriptInterface
    public void driveLogout() {
        synchronized (this) {
            cachedToken = null;
        }
        nasPrefs.edit().remove("driveEmail").remove("driveRootId").apply();
    }

    /** Token valido per Google Drive (rinnovato in automatico, senza chiedere nulla). */
    private String driveToken() throws Exception {
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() - tokenTime < 45 * 60 * 1000L) return cachedToken;
        }
        final String[] out = new String[1];
        final String[] err = new String[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        act.runOnUiThread(() -> ((MainActivity) act).driveAuth(false, new MainActivity.Result() {
            @Override public void ok(String token) { out[0] = token; done.countDown(); }

            @Override public void fail(String msg) { err[0] = msg; done.countDown(); }
        }));
        if (!done.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Google non risponde, riprova.");
        if (out[0] == null) throw new IllegalStateException(err[0] != null ? err[0] : "Accesso a Google non riuscito.");
        synchronized (this) {
            cachedToken = out[0];
            tokenTime = System.currentTimeMillis();
        }
        return out[0];
    }

    // ---------------- configurazione NAS ----------------

    @JavascriptInterface
    public String getNasConfig() {
        try {
            JSONObject o = new JSONObject();
            o.put("host", nasPrefs.getString("host", ""));
            o.put("share", nasPrefs.getString("share", ""));
            o.put("folder", nasPrefs.getString("folder", ""));
            o.put("user", nasPrefs.getString("user", ""));
            o.put("domain", nasPrefs.getString("domain", ""));
            o.put("hasPassword", !nasPrefs.getString("password", "").isEmpty());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void setNasConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = nasPrefs.edit();
            for (String k : new String[]{"host", "share", "folder", "user", "domain"}) {
                if (o.has(k)) e.putString(k, o.optString(k, "").trim());
            }
            String pw = o.optString("password", "");
            if (!pw.isEmpty()) e.putString("password", pw);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------- NAS ----------------

    @JavascriptInterface
    public void nasTest(String id) {
        async(nasQueue, id, () -> store().test());
    }

    @JavascriptInterface
    public void nasList(String id) {
        async(nasQueue, id, () -> store().list());
    }

    @JavascriptInterface
    public void nasRead(String id, String relPath) {
        async(nasQueue, id, () -> Base64.encodeToString(store().read(relPath), Base64.NO_WRAP));
    }

    @JavascriptInterface
    public void nasOpen(String id, String relPath) {
        async(nasQueue, id, () -> {
            byte[] data = store().read(relPath);
            String name = relPath.substring(Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\')) + 1);
            view(name, mimeFor(name), data);
            return "ok";
        });
    }

    @JavascriptInterface
    public void nasWriteFile(String id, String relDir, String name, String base64) {
        async(nasQueue, id, () -> store().writeFile(relDir, name, Base64.decode(base64, Base64.DEFAULT)));
    }

    @JavascriptInterface
    public void nasWriteText(String id, String relDir, String name, String text) {
        async(nasQueue, id, () -> {
            store().writeText(relDir, name, text);
            return "ok";
        });
    }

    @JavascriptInterface
    public void nasDeleteFolder(String id, String relDir) {
        async(nasQueue, id, () -> store().deleteFolder(relDir));
    }

    // ---------------- OCR ----------------

    @JavascriptInterface
    public void ocr(String id, String base64, String mime) {
        async(pool, id, () -> Ocr.read(act, Base64.decode(base64, Base64.DEFAULT), mime));
    }

    // ---------------- Claude (facoltativo) ----------------

    @JavascriptInterface
    public void claudeRequest(String id, String body, String apiKey) {
        async(pool, id, () -> {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = in == null ? "{}" : new String(readAll(in), StandardCharsets.UTF_8);
            c.disconnect();
            JSONObject o = new JSONObject();
            o.put("status", status);
            o.put("body", resp);
            return o.toString();
        });
    }

    // ---------------- file locali ----------------

    @JavascriptInterface
    public void openFile(String name, String mime, String base64) {
        pool.execute(() -> {
            try {
                view(name, mime, Base64.decode(base64, Base64.DEFAULT));
            } catch (Exception e) {
                toast(Nas.friendly(e));
            }
        });
    }

    /** Notifica di prova (Impostazioni → Garanzie e avvisi). */
    @JavascriptInterface
    public void testNotification() {
        pool.execute(() -> ReminderReceiver.test(act.getApplicationContext()));
    }

    /** Controllo aggiornamenti immediato (Impostazioni → Informazioni). */
    @JavascriptInterface
    public void checkUpdateNow() {
        act.runOnUiThread(() -> ((MainActivity) act).checkWebUpdate(true));
    }

    @JavascriptInterface
    public void setReminders(String json, int days) {
        act.getSharedPreferences(ReminderReceiver.PREFS, Context.MODE_PRIVATE).edit()
                .putString("items", json).putInt("days", days).apply();
        pool.execute(() -> ReminderReceiver.check(act.getApplicationContext()));
    }

    // ---------------- backup ----------------

    @JavascriptInterface
    public String getNasPassword() {
        return nasPrefs.getString("password", "");
    }

    /** Salva un file nella cartella Download del telefono. Restituisce il percorso mostrato all'utente. */
    @JavascriptInterface
    public void saveDownload(String id, String name, String mime, String base64) {
        async(pool, id, () -> {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Garanzie e Scontrini");
            Uri uri = act.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("Impossibile creare il file in Download");
            try (OutputStream os = act.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("Impossibile scrivere il file");
                os.write(data);
            }
            return "Download/Garanzie e Scontrini/" + name;
        });
    }

    // ---------------- aggiornamento dell'interfaccia ----------------

    /** Testo della nuova versione scaricata e non ancora applicata ("" se non c'è). */
    @JavascriptInterface
    public String getPendingUpdate() {
        try {
            File f = MainActivity.pendingFile(act);
            if (!f.exists()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Applica la nuova versione e ricarica la pagina. */
    @JavascriptInterface
    public void applyUpdate() {
        File p = MainActivity.pendingFile(act), f = MainActivity.webCacheFile(act);
        if (!p.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (p.renameTo(f)) web.post(web::reload);
    }

    // ---------------- utilità ----------------

    private void view(String name, String mime, byte[] data) throws Exception {
        File dir = new File(act.getCacheDir(), "apri");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null && old.length > 30) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File f = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(data);
        }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(() -> {
            try {
                act.startActivity(i);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(act, "Nessuna app installata per aprire questo file.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void toast(String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
    }

    static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic") || n.endsWith(".heif")) return "image/heic";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        return "image/jpeg";
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }
}
