package it.trsoftware.garanzie;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Aggiornamento dell'app senza passaggi: scarica l'APK da GitHub in sottofondo e lo installa
 * quando l'app va in secondo piano. Da Android 12 un'app che aggiorna se stessa può farlo
 * senza chiedere conferma; se il telefono la chiede comunque, arriva una notifica da toccare.
 */
final class Updater {
    static final String APK_URL = "https://github.com/vitobsta-cloud/Garanzie-Scontrini-app/releases/latest/download/GaranzieScontrini.apk";

    /** APK scaricato e pronto da installare alla chiusura dell'app (null = niente). */
    static volatile File pronto;

    static File scarica(Context c) throws Exception {
        File dir = new File(c.getCacheDir(), "aggiornamento");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, "scarico.tmp"), f = new File(dir, "GaranzieScontrini.apk");
        String url = APK_URL;
        HttpURLConnection con = null;
        for (int i = 0; i < 6; i++) {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(20000);
            con.setReadTimeout(60000);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                url = new URL(new URL(url), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("Download non riuscito (codice " + code + ")");
            break;
        }
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            con.disconnect();
        }
        if (tmp.length() < 100000) throw new Exception("File scaricato non valido, riprova.");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!tmp.renameTo(f)) throw new Exception("Impossibile preparare l'aggiornamento.");
        // se su GitHub non c'è ancora una versione più nuova di quella installata, non fa nulla
        try {
            android.content.pm.PackageInfo nuovo = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            long installata = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
            if (nuovo != null && nuovo.getLongVersionCode() <= installata) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                throw new IllegalStateException("La nuova versione non è ancora pronta su GitHub: riprova tra qualche minuto.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignored) {
        }
        return f;
    }

    /** Installa l'APK con il PackageInstaller chiedendo di non disturbare l'utente. */
    static void installa(Context c, File f) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            try (OutputStream out = s.openWrite("app.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            PendingIntent cb = PendingIntent.getBroadcast(c, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            s.commit(cb.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /** Chiamato quando l'app va in secondo piano: se c'è un aggiornamento pronto, lo installa. */
    static void seInAttesa(Context c) {
        File f = pronto;
        if (f == null || !f.exists()) return;
        pronto = null;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                installa(app, f);
            } catch (Exception e) {
                app.getSharedPreferences("agg", Context.MODE_PRIVATE).edit().putString("errore", String.valueOf(e.getMessage())).apply();
            }
        }).start();
    }
}
