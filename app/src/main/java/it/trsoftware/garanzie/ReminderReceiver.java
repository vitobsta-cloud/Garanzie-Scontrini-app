package it.trsoftware.garanzie;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Controllo giornaliero delle garanzie in scadenza, con notifica. */
public class ReminderReceiver extends BroadcastReceiver {

    static final String PREFS = "promemoria";
    private static final String CHANNEL = "scadenze";
    private static final String ACTION = "it.trsoftware.garanzie.CONTROLLA_SCADENZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) schedule(ctx);
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                check(ctx.getApplicationContext());
            } finally {
                pr.finish();
            }
        }).start();
    }

    /** Ogni giorno verso le 9:00. */
    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() < System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    static synchronized void check(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int days = p.getInt("days", 30);
        Set<String> notified = new HashSet<>(p.getStringSet("notificati", new HashSet<>()));
        List<String> lines = new ArrayList<>();
        Set<String> nuovi = new HashSet<>();
        LocalDate today = LocalDate.now();
        DateTimeFormatter it = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        try {
            JSONArray arr = new JSONArray(p.getString("items", "[]"));
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                String sc = o.optString("scadenza", "");
                if (sc.isEmpty()) continue;
                LocalDate d = LocalDate.parse(sc);
                long left = ChronoUnit.DAYS.between(today, d);
                String key = o.optString("id") + "|" + sc;
                if (left >= 0 && left <= days && !notified.contains(key)) {
                    String title = o.optString("titolo", "Acquisto");
                    String neg = o.optString("negozio", "");
                    lines.add(title + (neg.isEmpty() ? "" : " (" + neg + ")") + " – "
                            + (left == 0 ? "scade oggi" : "scade il " + d.format(it)));
                    nuovi.add(key);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        if (lines.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return; // senza permesso non segniamo come notificati: riproverà
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Garanzie in scadenza",
                NotificationManager.IMPORTANCE_DEFAULT));

        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = lines.size() == 1 ? "Garanzia in scadenza" : lines.size() + " garanzie in scadenza";
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (String l : lines) style.addLine(l);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(lines.get(0))
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(100, n);

        notified.addAll(nuovi);
        p.edit().putStringSet("notificati", notified).apply();
    }
}
