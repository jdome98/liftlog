package com.splitstak.app.widget;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.splitstak.app.MainActivity;
import com.splitstak.app.R;

/**
 * Native local-notification pipeline for the rest timer. Replaces the PWA's
 * Web Push path inside the Capacitor build because PushManager.subscribe()
 * doesn't work reliably under the capacitor://localhost origin. Same UX:
 * a "10 seconds left" warning followed by a "Rest complete" finish, both
 * delivered by the OS regardless of whether the screen is on.
 */
public class WidgetNotifications {

    public static final String CHANNEL_ID = "splitstak_rest_timer";
    private static final String CHANNEL_NAME = "Rest Timer";

    public static final String TYPE_FIRE_WARNING = "fire_warning";
    public static final String TYPE_FIRE_FINISH = "fire_finish";

    public static final int NOTIF_WARNING_ID = 5001;
    public static final int NOTIF_FINISH_ID = 5002;

    private static final int REQ_WARNING = 5101;
    private static final int REQ_FINISH = 5102;

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rest timer warning + finish alerts");
        ch.enableVibration(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    public static void scheduleRestNotifications(Context ctx, long restEndsAt) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(fireNotificationPI(ctx, TYPE_FIRE_WARNING, REQ_WARNING));
        am.cancel(fireNotificationPI(ctx, TYPE_FIRE_FINISH, REQ_FINISH));

        long now = System.currentTimeMillis();
        long warningAt = restEndsAt - 10_000L;
        if (warningAt > now) {
            scheduleExactAlarm(am, warningAt,
                    fireNotificationPI(ctx, TYPE_FIRE_WARNING, REQ_WARNING));
        }
        if (restEndsAt > now) {
            scheduleExactAlarm(am, restEndsAt,
                    fireNotificationPI(ctx, TYPE_FIRE_FINISH, REQ_FINISH));
        }
    }

    /**
     * Two notifications only ~10s apart hit the per-app rate limit on
     * setAndAllowWhileIdle (Doze enforces ~9 min minimum), so the second
     * one gets silently deferred. setExactAndAllowWhileIdle is exempt.
     * Falls back if the runtime denies exact permission for any reason.
     */
    private static void scheduleExactAlarm(AlarmManager am, long fireAt, PendingIntent pi) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        }
    }

    public static void cancelRestNotifications(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(fireNotificationPI(ctx, TYPE_FIRE_WARNING, REQ_WARNING));
            am.cancel(fireNotificationPI(ctx, TYPE_FIRE_FINISH, REQ_FINISH));
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIF_WARNING_ID);
            nm.cancel(NOTIF_FINISH_ID);
        }
    }

    public static void postWarning(Context ctx) {
        post(ctx, NOTIF_WARNING_ID, "10 seconds left", "Rest timer almost done");
    }

    public static void postFinish(Context ctx) {
        post(ctx, NOTIF_FINISH_ID, "Rest complete", "Time to start your next set");
    }

    private static void post(Context ctx, int id, String title, String body) {
        ensureChannel(ctx);
        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(contentPI)
                .build();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, notif);
    }

    private static PendingIntent fireNotificationPI(Context ctx, String type, int requestCode) {
        Intent i = new Intent(ctx, SplitstakAppWidgetProvider.class);
        i.setAction(SplitstakAppWidgetProvider.ACTION);
        i.putExtra(SplitstakAppWidgetProvider.EXTRA_TYPE, type);
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
