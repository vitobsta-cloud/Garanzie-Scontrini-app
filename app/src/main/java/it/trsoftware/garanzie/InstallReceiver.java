package it.trsoftware.garanzie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Esito dell'installazione dell'aggiornamento. */
public class InstallReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "aggiornamenti";

    @Override
    public void onReceive(Context c, Intent intent) {
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_SUCCESS) return;   // l'app viene riavviata dal sistema
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // il telefono vuole comunque una conferma: se l'app è aperta la mostra subito, altrimenti notifica
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (MainActivity.inPrimoPiano) {
                try { c.startActivity(confirm); return; } catch (Exception ignored) { }
            }
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Aggiornamenti dell'app", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 7, confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(201, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Garanzie & Scontrini: aggiornamento pronto")
                    .setContentText("Tocca per completare l'aggiornamento")
                    .setContentIntent(pi).setAutoCancel(true).build());
            return;
        }
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        c.getSharedPreferences("agg", Context.MODE_PRIVATE).edit().putString("errore", msg == null ? "Installazione non riuscita" : msg).apply();
    }
}
