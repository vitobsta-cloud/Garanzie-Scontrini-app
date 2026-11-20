package it.trsoftware.garanzie;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_TREE = 3;
    private static final int REQ_AUTH = 4;

    /** Risposta asincrona alla pagina. */
    interface Result {
        void ok(String value);

        void fail(String msg);
    }

    private Result treeResult, authResult;
    static final String HOST = "appassets.androidplatform.net";
    /** true mentre l'app è sullo schermo (serve all'aggiornamento automatico). */
    static volatile boolean inPrimoPiano;

    @Override
    protected void onPause() {
        inPrimoPiano = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Updater.seInAttesa(this);   // aggiornamento scaricato: si installa ora che l'app non è in uso
    }
    /** L'interfaccia viene aggiornata da qui: basta caricare index.html su GitHub. */
    static final String WEB_URL =
            "https://raw.githubusercontent.com/vitobsta-cloud/Garanzie-Scontrini-app/main/app/src/main/assets/www/index.html";
    private long lastUpdateCheck = 0;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        // Le pagine vengono servite da https://appassets.androidplatform.net/assets/...
        // così IndexedDB e localStorage funzionano come in un sito normale.
        resetCacheIfNewApk();
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                // prima la versione scaricata da GitHub (se c'è), poi quella inclusa nell'APK
                .addPathHandler("/assets/www/", path -> {
                    File f = webCache();
                    if (!"index.html".equals(path) || !f.exists()) return null;
                    try {
                        return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f));
                    } catch (Exception e) {
                        return null;
                    }
                })
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    if (params.isCaptureEnabled()) openCamera();
                    else openPicker(params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Impossibile aprire: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(this, web), "AndroidBridge");
        web.loadUrl("https://" + HOST + "/assets/www/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        ReminderReceiver.schedule(this);
    }

    private void openCamera() {
        File dir = new File(getCacheDir(), "camera");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "scontrino_" + System.currentTimeMillis() + ".jpg");
        cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            throw new RuntimeException("nessuna app fotocamera trovata");
        }
    }

    private void openPicker(boolean multiple) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        startActivityForResult(Intent.createChooser(i, "Scegli scontrino o garanzia"), REQ_FILE);
    }

    // ---------------- cartella sul telefono ----------------

    void pickTree(Result r) {
        treeResult = r;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            treeResult = null;
            r.fail("Il telefono non permette di scegliere una cartella.");
        }
    }

    // ---------------- Google Drive ----------------

    /** Chiede a Google il permesso per Drive; interactive=false non mostra finestre (rinnovo silenzioso). */
    void driveAuth(boolean interactive, Result r) {
        com.google.android.gms.auth.api.identity.AuthorizationRequest req =
                com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
                        .setRequestedScopes(java.util.Collections.singletonList(
                                new com.google.android.gms.common.api.Scope(DriveStore.SCOPE)))
                        .build();
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener(res -> {
                    if (res.hasResolution()) {
                        if (!interactive || res.getPendingIntent() == null) {
                            r.fail("Accesso a Google scaduto: entra in Destinazione file e tocca Accedi con Google.");
                            return;
                        }
                        authResult = r;
                        try {
                            startIntentSenderForResult(res.getPendingIntent().getIntentSender(), REQ_AUTH, null, 0, 0, 0, null);
                        } catch (Exception e) {
                            authResult = null;
                            r.fail("Impossibile aprire l'accesso a Google: " + e.getMessage());
                        }
                    } else if (res.getAccessToken() != null) {
                        r.ok(res.getAccessToken());
                    } else {
                        r.fail("Google non ha concesso l'accesso.");
                    }
                })
                .addOnFailureListener(e -> r.fail(googleError(e)));
    }

    private static String googleError(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.startsWith("10:") || m.contains("DEVELOPER_ERROR"))
            return "L'app non è ancora registrata presso Google (errore 10): serve la configurazione nella Google Cloud Console.";
        if (m.startsWith("7:")) return "Nessuna connessione a internet.";
        return "Accesso a Google non riuscito: " + m;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            Result r = treeResult;
            treeResult = null;
            if (r == null) return;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) r.ok(data.getData().toString());
            else r.fail("Nessuna cartella scelta.");
            return;
        }
        if (requestCode == REQ_AUTH) {
            Result r = authResult;
            authResult = null;
            if (r == null) return;
            if (resultCode != RESULT_OK) {
                r.fail("Accesso a Google annullato.");
                return;
            }
            try {
                com.google.android.gms.auth.api.identity.AuthorizationResult res =
                        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this)
                                .getAuthorizationResultFromIntent(data);
                if (res.getAccessToken() != null) r.ok(res.getAccessToken());
                else r.fail("Google non ha concesso l'accesso.");
            } catch (Exception e) {
                r.fail(googleError(e));
            }
            return;
        }
        if (fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (requestCode == REQ_CAMERA) {
                result = new Uri[]{cameraUri};
            } else if (requestCode == REQ_FILE && data != null) {
                List<Uri> list = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int k = 0; k < clip.getItemCount(); k++) list.add(clip.getItemAt(k).getUri());
                } else if (data.getData() != null) {
                    list.add(data.getData());
                }
                if (!list.isEmpty()) result = list.toArray(new Uri[0]);
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        inPrimoPiano = true;
        if (web != null) web.evaluateJavascript("window.onAndroidResume && window.onAndroidResume()", null);
        checkWebUpdate(false);
    }

    // ---------------- aggiornamento automatico dell'interfaccia ----------------

    private File webCache() {
        return webCacheFile(this);
    }

    static File webCacheFile(android.content.Context c) {
        return new File(new File(c.getFilesDir(), "web"), "index.html");
    }

    static File pendingFile(android.content.Context c) {
        return new File(new File(c.getFilesDir(), "web"), "pending.html");
    }

    /** Dopo l'installazione di un APK nuovo si riparte dalla versione inclusa nell'APK. */
    private void resetCacheIfNewApk() {
        try {
            long vc = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            android.content.SharedPreferences p = getSharedPreferences("web", MODE_PRIVATE);
            if (p.getLong("apk", -1) != vc) {
                //noinspection ResultOfMethodCallIgnored
                webCache().delete();
                //noinspection ResultOfMethodCallIgnored
                pendingFile(this).delete();
                p.edit().putLong("apk", vc).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /** Scarica index.html da GitHub; se è cambiato lo salva e avvisa la pagina. */
    void checkWebUpdate(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateCheck < 10 * 60 * 1000L) return;
        lastUpdateCheck = now;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(WEB_URL + "?t=" + now).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                    data = bo.toByteArray();
                }
                String html = new String(data, StandardCharsets.UTF_8);
                // controllo minimo che sia davvero la nostra pagina
                if (data.length < 20000 || !html.contains("AndroidBridge") || !html.contains("</html>")) return;
                byte[] current = currentHtml();
                File pending = pendingFile(this);
                if (current != null && Arrays.equals(current, data)) {
                    //noinspection ResultOfMethodCallIgnored
                    pending.delete();   // già aggiornata
                    return;
                }
                // la nuova versione resta "in attesa": la pagina chiede all'utente se applicarla
                //noinspection ResultOfMethodCallIgnored
                pending.getParentFile().mkdirs();
                File tmp = new File(pending.getParentFile(), "pending.tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    fo.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                pending.delete();
                if (!tmp.renameTo(pending)) return;
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.onWebUpdate && window.onWebUpdate()", null));
            } catch (Exception ignored) {
                // niente rete: resta la versione attuale
            }
        }).start();
    }

    private byte[] currentHtml() {
        try {
            InputStream in = webCache().exists() ? new FileInputStream(webCache()) : getAssets().open("www/index.html");
            try (InputStream i = in) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = i.read(buf)) > 0) bo.write(buf, 0, r);
                return bo.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'true' : 'false'", value -> {
            if (!"\"true\"".equals(value) && !"true".equals(value)) finish();
        });
    }
}
