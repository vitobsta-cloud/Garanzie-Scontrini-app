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
                reply(id, false, Nas.friendly(t));
            }
        });
    }

    private Nas.Cfg cfg() {
        return Nas.Cfg.load(nasPrefs);
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
        async(nasQueue, id, () -> Nas.test(cfg()));
    }

    @JavascriptInterface
    public void nasList(String id) {
        async(nasQueue, id, () -> Nas.list(cfg()));
    }

    @JavascriptInterface
    public void nasRead(String id, String relPath) {
        async(nasQueue, id, () -> Base64.encodeToString(Nas.read(cfg(), relPath), Base64.NO_WRAP));
    }

    @JavascriptInterface
    public void nasOpen(String id, String relPath) {
        async(nasQueue, id, () -> {
            byte[] data = Nas.read(cfg(), relPath);
            String name = relPath.substring(Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\')) + 1);
            view(name, mimeFor(name), data);
            return "ok";
        });
    }

    @JavascriptInterface
    public void nasWriteFile(String id, String relDir, String name, String base64) {
        async(nasQueue, id, () -> Nas.writeFile(cfg(), relDir, name, Base64.decode(base64, Base64.DEFAULT)));
    }

    @JavascriptInterface
    public void nasWriteText(String id, String relDir, String name, String text) {
        async(nasQueue, id, () -> {
            Nas.writeText(cfg(), relDir, name, text);
            return "ok";
        });
    }

    @JavascriptInterface
    public void nasDeleteFolder(String id, String relDir) {
        async(nasQueue, id, () -> Nas.deleteFolder(cfg(), relDir));
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

    @JavascriptInterface
    public void setReminders(String json, int days) {
        act.getSharedPreferences(ReminderReceiver.PREFS, Context.MODE_PRIVATE).edit()
                .putString("items", json).putInt("days", days).apply();
        pool.execute(() -> ReminderReceiver.check(act.getApplicationContext()));
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
