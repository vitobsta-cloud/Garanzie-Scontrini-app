package it.trsoftware.garanzie;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintJob;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
        Nas.infoCacheFile = new File(act.getCacheDir(), "info_nas.json");
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
                Nas.wifiSocketFactory = networkSocketFactory(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                Nas.vpnSocketFactory = networkSocketFactory(android.net.NetworkCapabilities.TRANSPORT_VPN);
                return new NasStore(cfg());
        }
    }

    /** Rete del tipo indicato (VPN o Wi-Fi) attiva sul telefono, per forzare da dove passa il collegamento al NAS. */
    @SuppressWarnings("deprecation")
    private javax.net.SocketFactory networkSocketFactory(int transport) {
        try {
            android.net.ConnectivityManager cm = act.getSystemService(android.net.ConnectivityManager.class);
            if (cm == null) return null;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(transport)) continue;
                if (transport == android.net.NetworkCapabilities.TRANSPORT_WIFI
                        && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                return n.getSocketFactory();
            }
        } catch (Exception ignored) {
        }
        return null;
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
            nasQueue.execute(Nas::closeQuietly);
        } catch (Exception ignored) {
        }
    }

    // ---------------- NAS ----------------

    @JavascriptInterface
    public void nasTest(String id) {
        async(nasQueue, id, () -> store().test());
    }

    /** Controllo veloce del collegamento: il pallino diventa verde senza aspettare l'elenco. */
    @JavascriptInterface
    public void nasPing(String id) {
        async(nasQueue, id, () -> store().ping());
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

    private static final String APK_URL = "https://github.com/vitobsta-cloud/Garanzie-Scontrini-app/releases/latest/download/GaranzieScontrini.apk";

    /**
     * Scarica l'ultima versione dell'app da GitHub e apre direttamente l'installazione
     * (Android chiede solo la conferma "Aggiorna"). Risponde "permesso" se prima bisogna
     * consentire l'installazione di app da questa app.
     */
    @JavascriptInterface
    public void installApk(String id) {
        async(pool, id, () -> {
            if (!act.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.runOnUiThread(() -> act.startActivity(s));
                return "permesso";
            }
            File dir = new File(act.getCacheDir(), "aggiornamento");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "GaranzieScontrini.apk");
            String url = APK_URL;
            HttpURLConnection c = null;
            for (int i = 0; i < 6; i++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code != 200) throw new Exception("Download non riuscito (codice " + code + ")");
                break;
            }
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                c.disconnect();
            }
            if (f.length() < 100000) throw new Exception("File scaricato non valido, riprova.");
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(() -> act.startActivity(i));
            return "ok";
        });
    }

    /** Scarica l'aggiornamento in sottofondo: verrà installato da solo quando si esce dall'app. */
    @JavascriptInterface
    public void aggiornaInSilenzio(String id) {
        async(pool, id, () -> {
            if (!act.getPackageManager().canRequestPackageInstalls()) return "permesso";
            Updater.pronto = Updater.scarica(act);
            return "pronto";
        });
    }

    /** Aggiorna subito (tasto "Cerca aggiornamenti"). */
    @JavascriptInterface
    public void aggiornaOra(String id) {
        async(pool, id, () -> {
            if (!act.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.runOnUiThread(() -> act.startActivity(s));
                return "permesso";
            }
            Updater.installa(act, Updater.scarica(act));
            return "ok";
        });
    }

    /** Ultimo errore dell'aggiornamento automatico (e lo cancella). */
    @JavascriptInterface
    public String erroreAggiornamento() {
        SharedPreferences p = act.getSharedPreferences("agg", Context.MODE_PRIVATE);
        String e = p.getString("errore", "");
        p.edit().remove("errore").apply();
        return e;
    }

    /** Versione dell'APK installato: l'interfaccia la usa per proporre la reinstallazione quando serve. */
    @JavascriptInterface
    public int getVersionCode() {
        try {
            return (int) act.getPackageManager().getPackageInfo(act.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    // tenuta in memoria finché Android non ha preparato la stampa
    private WebView printView;

    /** Stampa la scheda A4 creata come PDF (metodo principale, dalla v2.6). */
    @JavascriptInterface
    public void stampaPdf(String id, String json, String titolo) {
        async(pool, id, () -> {
            File f = SchedaPdf.crea(new File(act.getCacheDir(), "stampa"), json);
            String nome = (titolo == null || titolo.isEmpty()) ? "Scheda acquisto" : titolo.replaceAll("[\\\\/:*?\"<>|]", "_");
            act.runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                    PrintJob job = pm.print(nome, new SchedaPdf.Adapter(f, nome), new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                            .build());
                    seguiStampa(job);
                } catch (Exception e) {
                    toast("Stampa non riuscita: " + e.getMessage());
                }
            });
            return "ok";
        });
    }

    /** true se sul telefono c'è Epson iPrint. */
    @JavascriptInterface
    public boolean haIPrint() {
        try {
            act.getPackageManager().getPackageInfo("epson.print", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stampa con l'app della stampante: la scheda PDF (creata dall'interfaccia) viene passata
     * direttamente a Epson iPrint, che mostra l'anteprima con il tasto Stampa.
     * Se iPrint non c'è, si apre la scelta delle app che possono stampare il PDF.
     */
    @JavascriptInterface
    public void stampaIPrint(String id, String base64, String nome) {
        async(pool, id, () -> {
            File dir = new File(act.getCacheDir(), "stampa");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String n = (nome == null || nome.isEmpty()) ? "Scheda acquisto" : nome.replaceAll("[\\\\/:*?\"<>|]", "_");
            File f = new File(dir, n + ".pdf");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(Base64.decode(base64, Base64.DEFAULT));
            }
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean iprint = haIPrint();
            if (iprint) i.setPackage("epson.print");
            Intent avvio = iprint ? i : Intent.createChooser(i, "Stampa con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(() -> {
                try {
                    act.startActivity(avvio);
                } catch (Exception e) {
                    try {
                        act.startActivity(Intent.createChooser(i.setPackage(null), "Stampa con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e2) {
                        toast("Nessuna app per stampare il PDF.");
                    }
                }
            });
            return iprint ? "iprint" : "scelta";
        });
    }

    /** Apre l'ultima scheda PDF creata (per stamparla con un'altra app, es. Epson iPrint, o condividerla). */
    @JavascriptInterface
    public void apriPdf() {
        File f = new File(new File(act.getCacheDir(), "stampa"), "scheda.pdf");
        if (!f.exists()) { toast("Nessuna scheda da aprire."); return; }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(() -> {
            try {
                act.startActivity(Intent.createChooser(i, "Apri la scheda con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                toast("Nessuna app per aprire i PDF.");
            }
        });
    }

    /** Segue il lavoro di stampa e ne comunica lo stato all'interfaccia (badge "stampa in corso"). */
    private void seguiStampa(PrintJob job) {
        final Handler h = new Handler(Looper.getMainLooper());
        final long inizio = System.currentTimeMillis();
        final String[] ultimo = {""};
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                String st;
                try {
                    if (job.isCompleted()) st = "fatta";
                    else if (job.isFailed()) st = "errore";
                    else if (job.isCancelled()) st = "annullata";
                    else if (job.isBlocked()) st = "bloccata";
                    else if (job.isStarted()) st = "incorso";
                    else if (job.isQueued()) st = "coda";
                    else st = "scelta";   // finestra di stampa ancora aperta
                } catch (Exception e) {
                    st = "errore";
                }
                if (!st.equals(ultimo[0])) {
                    ultimo[0] = st;
                    final String js = "window.onStampa && window.onStampa('" + st + "')";
                    web.evaluateJavascript(js, null);
                }
                boolean finito = st.equals("fatta") || st.equals("errore") || st.equals("annullata");
                if (finito) printView = null;
                else if (System.currentTimeMillis() - inizio < 15 * 60 * 1000L) h.postDelayed(this, 1000);
            }
        }, 800);
    }

    /** Stampa (o salva in PDF) una scheda A4 preparata dall'interfaccia. */
    @JavascriptInterface
    public void stampa(String html, String titolo) {
        act.runOnUiThread(() -> {
            try {
                WebView w = new WebView(act);
                w.getSettings().setJavaScriptEnabled(false);
                w.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }

                    @Override
                    public void onPageFinished(WebView v, String url) {
                        if (printView != v) return;
                        try {
                            String nome = (titolo == null || titolo.isEmpty()) ? "Scheda acquisto" : titolo;
                            PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                            PrintDocumentAdapter ad = v.createPrintDocumentAdapter(nome);
                            PrintJob job = pm.print(nome, ad, new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                    .build());
                            seguiStampa(job);
                        } catch (Exception e) {
                            toast("Stampa non riuscita: " + e.getMessage());
                        }
                    }
                });
                printView = w;
                w.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null);
            } catch (Exception e) {
                toast("Stampa non riuscita: " + e.getMessage());
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
