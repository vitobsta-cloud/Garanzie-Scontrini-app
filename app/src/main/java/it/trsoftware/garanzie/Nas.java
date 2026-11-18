package it.trsoftware.garanzie;

import android.content.SharedPreferences;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Accesso alla cartella condivisa del NAS QNAP via SMB (smbj). */
final class Nas {

    static final String INFO_FILE = "garanzia.json";
    private static final Pattern DOC_EXT =
            Pattern.compile(".*\\.(pdf|jpe?g|png|webp|bmp|heic|heif|tiff?|gif)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DEPTH = 4;

    private static SMBClient client;

    static final class Cfg {
        String host, share, folder, user, domain, password;
        int port = 445;

        static Cfg load(SharedPreferences p) {
            Cfg c = new Cfg();
            String h = p.getString("host", "").trim();
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {
                try {
                    c.port = Integer.parseInt(h.substring(colon + 1));
                    h = h.substring(0, colon);
                } catch (NumberFormatException ignored) {
                }
            }
            c.host = h;
            c.share = p.getString("share", "").trim();
            c.folder = p.getString("folder", "").trim();
            c.user = p.getString("user", "");
            c.domain = p.getString("domain", "");
            c.password = p.getString("password", "");
            return c;
        }
    }

    interface Op<T> {
        T run(DiskShare share, String root) throws Exception;
    }

    private static synchronized SMBClient client() {
        if (client == null) {
            SmbConfig cfg = SmbConfig.builder()
                    .withSecurityProvider(new BCSecurityProvider())
                    .withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(45, TimeUnit.SECONDS)
                    .build();
            client = new SMBClient(cfg);
        }
        return client;
    }

    // ---------- collegamento riutilizzato ----------
    // Il collegamento resta aperto tra un'operazione e l'altra (fino a 3 minuti di inattività):
    // aprire una cartella o salvare uno scontrino non rifà ogni volta collegamento e accesso.

    private static Connection openConn;
    private static DiskShare openShare;
    private static String openKey;
    private static long lastUse;
    private static final long IDLE_MS = 3 * 60 * 1000L;

    private static String key(Cfg c) {
        return c.host + "|" + c.port + "|" + c.share + "|" + c.user + "|" + c.domain + "|" + (c.password == null ? 0 : c.password.hashCode());
    }

    private static void open(Cfg c) throws Exception {
        Connection conn = client().connect(c.host, c.port);
        try {
            AuthenticationContext auth = (c.user == null || c.user.isEmpty())
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(c.user, c.password == null ? new char[0] : c.password.toCharArray(), c.domain);
            Session session = conn.authenticate(auth);
            openShare = (DiskShare) session.connectShare(c.share);
            openConn = conn;
            openKey = key(c);
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    static synchronized void closeQuietly() {
        try { if (openShare != null) openShare.close(); } catch (Exception ignored) { }
        try { if (openConn != null) openConn.close(); } catch (Exception ignored) { }
        openShare = null;
        openConn = null;
        openKey = null;
    }

    private static boolean connectionProblem(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String n = x.getClass().getSimpleName();
            if (n.contains("Transport") || x instanceof java.io.IOException || n.contains("Timeout")) return true;
        }
        return false;
    }

    static synchronized <T> T with(Cfg c, Op<T> op) throws Exception {
        if (c.host == null || c.host.isEmpty()) throw new IllegalStateException("Indirizzo del NAS non impostato.");
        if (c.share == null || c.share.isEmpty()) throw new IllegalStateException("Cartella condivisa non impostata.");
        boolean reused = false;
        if (openShare != null && key(c).equals(openKey) && openConn != null && openConn.isConnected()
                && System.currentTimeMillis() - lastUse < IDLE_MS) {
            reused = true;
        } else {
            closeQuietly();
            open(c);
        }
        try {
            T r = op.run(openShare, norm(c.folder));
            lastUse = System.currentTimeMillis();
            return r;
        } catch (Exception e) {
            closeQuietly();
            // collegamento vecchio caduto nel frattempo: si riapre e si riprova una volta
            if (reused && connectionProblem(e)) {
                open(c);
                T r = op.run(openShare, norm(c.folder));
                lastUse = System.currentTimeMillis();
                return r;
            }
            throw e;
        }
    }

    /** Controllo veloce: collegamento, accesso e cartella dell'archivio (senza leggere l'elenco). */
    static String ping(Cfg c) throws Exception {
        return with(c, (s, root) -> {
            if (!root.isEmpty() && !s.folderExists(root))
                throw new IllegalStateException("Cartella \"" + root + "\" non trovata nella condivisione " + c.share + ".");
            return "ok";
        });
    }

    // ---------- copia dei garanzia.json già letti ----------
    // Rilegge un garanzia.json solo se è cambiato (data o dimensione diverse).

    static java.io.File infoCacheFile;
    private static JSONObject infoCache;

    private static synchronized JSONObject infoCache() {
        if (infoCache == null) {
            infoCache = new JSONObject();
            try {
                if (infoCacheFile != null && infoCacheFile.exists())
                    infoCache = new JSONObject(new String(java.nio.file.Files.readAllBytes(infoCacheFile.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        return infoCache;
    }

    private static synchronized void saveInfoCache() {
        try {
            if (infoCacheFile != null && infoCache != null)
                java.nio.file.Files.write(infoCacheFile.toPath(), infoCache.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ---------- percorsi ----------

    static String norm(String p) {
        if (p == null) return "";
        String s = p.replace('/', '\\');
        while (s.startsWith("\\")) s = s.substring(1);
        while (s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String n = norm(part);
            if (n.isEmpty()) continue;
            if (n.contains("..")) throw new IllegalArgumentException("Percorso non valido");
            if (sb.length() > 0) sb.append('\\');
            sb.append(n);
        }
        return sb.toString();
    }

    static void mkdirs(DiskShare s, String path) {
        String acc = "";
        for (String seg : norm(path).split("\\\\")) {
            if (seg.isEmpty()) continue;
            acc = acc.isEmpty() ? seg : acc + "\\" + seg;
            if (!s.folderExists(acc)) s.mkdir(acc);
        }
    }

    // ---------- operazioni ----------

    static String test(Cfg c) throws Exception {
        return with(c, (s, root) -> {
            if (!root.isEmpty() && !s.folderExists(root)) {
                throw new IllegalStateException("Collegato al NAS, ma la cartella \"" + root
                        + "\" non esiste nella condivisione " + c.share + ".");
            }
            int n = 0;
            for (FileIdBothDirectoryInformation f : s.list(root)) {
                String name = f.getFileName();
                if (isDir(f) && !skip(name)) n++;
            }
            // verifica scrittura
            String probe = join(root, ".garanzie_test_" + System.currentTimeMillis() + ".tmp");
            writeBytes(s, probe, new byte[]{'o', 'k'});
            s.rm(probe);
            return "NAS raggiungibile e scrivibile: " + n + " cartelle negozio in \\\\" + c.host + "\\" + c.share
                    + (root.isEmpty() ? "" : "\\" + root) + ".";
        });
    }

    /** Elenca le cartelle che contengono documenti (Negozio\nome AAAA_MM_GG\...). */
    static String list(Cfg c) throws Exception {
        return with(c, (s, root) -> {
            JSONArray out = new JSONArray();
            walk(s, root, "", 0, out);
            saveInfoCache();
            return out.toString();
        });
    }

    private static void walk(DiskShare s, String root, String rel, int depth, JSONArray out) throws Exception {
        String path = join(root, rel);
        JSONArray files = new JSONArray();
        String info = null;
        List<String> subdirs = new ArrayList<>();
        for (FileIdBothDirectoryInformation f : s.list(path)) {
            String name = f.getFileName();
            if (".".equals(name) || "..".equals(name) || skip(name)) continue;
            if (isDir(f)) {
                if (depth < MAX_DEPTH) subdirs.add(name);
            } else if (INFO_FILE.equalsIgnoreCase(name)) {
                String stamp = f.getLastWriteTime().toEpochMillis() + "/" + f.getEndOfFile();
                JSONObject cached = infoCache().optJSONObject(rel);
                if (cached != null && stamp.equals(cached.optString("stamp"))) {
                    info = cached.optString("text");
                } else {
                    try {
                        info = new String(readBytes(s, join(path, name)), StandardCharsets.UTF_8);
                        infoCache().put(rel, new JSONObject().put("stamp", stamp).put("text", info));
                    } catch (Exception ignored) {
                    }
                }
            } else if (DOC_EXT.matcher(name).matches()) {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("size", f.getEndOfFile());
                o.put("mtime", f.getLastWriteTime().toEpochMillis());
                files.put(o);
            }
        }
        if (!rel.isEmpty() && (files.length() > 0 || info != null)) {
            JSONObject e = new JSONObject();
            e.put("path", rel);
            e.put("files", files);
            if (info != null) e.put("info", info);
            out.put(e);
        }
        for (String d : subdirs) walk(s, root, rel.isEmpty() ? d : rel + "/" + d, depth + 1, out);
    }

    private static boolean isDir(FileIdBothDirectoryInformation f) {
        return (f.getFileAttributes() & 0x10L) != 0;
    }

    /** Cartelle di sistema del QNAP e file nascosti. */
    private static boolean skip(String name) {
        return name.startsWith(".") || name.startsWith("@") || name.startsWith("#")
                || name.equalsIgnoreCase("Thumbs.db") || name.equalsIgnoreCase("desktop.ini");
    }

    static byte[] read(Cfg c, String rel) throws Exception {
        return with(c, (s, root) -> readBytes(s, join(root, rel)));
    }

    /** Scrive un file nella cartella; se il nome esiste già aggiunge _2, _3… Restituisce il nome usato. */
    static String writeFile(Cfg c, String relDir, String name, byte[] data) throws Exception {
        return with(c, (s, root) -> {
            String dir = join(root, relDir);
            mkdirs(s, dir);
            String base = name, ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            String used = name;
            for (int k = 2; s.fileExists(join(dir, used)); k++) used = base + "_" + k + ext;
            writeBytes(s, join(dir, used), data);
            return used;
        });
    }

    static void writeText(Cfg c, String relDir, String name, String text) throws Exception {
        with(c, (s, root) -> {
            String dir = join(root, relDir);
            mkdirs(s, dir);
            writeBytes(s, join(dir, name), text.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    /** Elimina i file della cartella dell'acquisto e la cartella stessa (non tocca sottocartelle). */
    static String deleteFolder(Cfg c, String relDir) throws Exception {
        return with(c, (s, root) -> {
            if (norm(relDir).isEmpty()) throw new IllegalArgumentException("Cartella non valida");
            String dir = join(root, relDir);
            if (!s.folderExists(dir)) return "già eliminata";
            boolean hasSub = false;
            for (FileIdBothDirectoryInformation f : s.list(dir)) {
                String name = f.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                if (isDir(f)) hasSub = true;
                else s.rm(join(dir, name));
            }
            if (!hasSub) s.rmdir(dir, false);
            // se la cartella del negozio è rimasta vuota, elimina anche quella
            int cut = dir.lastIndexOf('\\');
            String parent = cut > 0 ? dir.substring(0, cut) : "";
            if (!parent.isEmpty() && !parent.equalsIgnoreCase(root)) {
                boolean empty = true;
                for (FileIdBothDirectoryInformation f : s.list(parent)) {
                    String name = f.getFileName();
                    if (!".".equals(name) && !"..".equals(name)) { empty = false; break; }
                }
                if (empty) s.rmdir(parent, false);
            }
            return "ok";
        });
    }

    // ---------- I/O di basso livello ----------

    private static byte[] readBytes(DiskShare s, String path) throws Exception {
        try (File f = s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
             InputStream in = f.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    private static void writeBytes(DiskShare s, String path, byte[] data) throws Exception {
        try (File f = s.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
             OutputStream out = f.getOutputStream()) {
            out.write(data);
        }
    }

    /** Traduce gli errori in messaggi comprensibili. */
    static String friendly(Throwable t) {
        return friendlyBase(t) + "\n[dettaglio: " + detail(t) + "]";
    }

    /** Catena tecnica dell'errore, per capire la causa senza altre prove. */
    static String detail(Throwable t) {
        StringBuilder d = new StringBuilder();
        int n = 0;
        for (Throwable x = t; x != null && n < 4; x = x.getCause(), n++) {
            if (n > 0) d.append(" ← ");
            d.append(x.getClass().getSimpleName());
            if (x.getMessage() != null) {
                String m = x.getMessage();
                d.append(": ").append(m.length() > 160 ? m.substring(0, 160) + "…" : m);
            }
        }
        return d.toString();
    }

    private static String friendlyBase(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) {
            all.append(x.getClass().getSimpleName()).append(' ').append(x.getMessage()).append(' ');
            if (x instanceof UnknownHostException || x instanceof ConnectException
                    || x instanceof NoRouteToHostException || x instanceof SocketTimeoutException) {
                return "NAS non raggiungibile. A casa controlla il Wi-Fi; fuori casa accendi la VPN del QNAP.";
            }
        }
        String a = all.toString().toUpperCase(Locale.ROOT);
        if (a.contains("STATUS_LOGON_FAILURE")) return "Nome utente o password del NAS errati.";
        if (a.contains("STATUS_ACCOUNT_LOCKED_OUT")) return "Utente del NAS bloccato per troppi tentativi: riprova tra qualche minuto.";
        if (a.contains("STATUS_ACCESS_DENIED")) return "Accesso negato: l'utente del NAS non ha il permesso di scrittura su questa cartella.";
        if (a.contains("STATUS_BAD_NETWORK_NAME")) return "La cartella condivisa indicata non esiste sul NAS.";
        if (a.contains("STATUS_OBJECT_NAME_NOT_FOUND") || a.contains("STATUS_OBJECT_PATH_NOT_FOUND"))
            return "Cartella o file non trovati sul NAS.";
        if (a.contains("STATUS_SHARING_VIOLATION")) return "Il file è aperto da un altro programma.";
        if (a.contains("STATUS_DISK_FULL")) return "Il NAS è pieno.";
        if (a.contains("TIMEOUT") || a.contains("TRANSPORTEXCEPTION"))
            return "NAS non raggiungibile. A casa controlla il Wi-Fi; fuori casa accendi la VPN del QNAP.";
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private Nas() {
    }
}
