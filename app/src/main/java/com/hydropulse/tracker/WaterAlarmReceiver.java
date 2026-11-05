package com.hydropulse.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.RemoteViews;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WaterAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_SHOW_REMINDER = "com.hydropulse.tracker.ACTION_SHOW_REMINDER";
    public static final String ACTION_ADD_WATER = "com.hydropulse.tracker.ACTION_ADD_WATER";
    public static final String ACTION_DISMISS_NOTIF = "com.hydropulse.tracker.ACTION_DISMISS_NOTIF";
    public static final int NOTIF_ID = 3001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int intervalMins = intent.getIntExtra("intervalMins", 30);
        int perIntervalMl = intent.getIntExtra("perIntervalMl", 110);
        int currentIntakeMl = intent.getIntExtra("currentIntakeMl", 0);
        int totalDailyMl = intent.getIntExtra("totalDailyMl", 3050);

        // 1. Handle "✕" Dismiss Button on Custom Notification
        if (ACTION_DISMISS_NOTIF.equals(action)) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIF_ID);
            }
            return;
        }

        // 2. Handle "+Drink" Button on Custom Notification
        if (ACTION_ADD_WATER.equals(action)) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIF_ID);
            }

            try {
                String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NOTIF_LOGS, Context.MODE_PRIVATE);
                String existing = prefs.getString("pending_json", "[]");
                JSONArray arr = new JSONArray(existing);
                JSONObject entry = new JSONObject();
                entry.put("amount", perIntervalMl);
                entry.put("time", timeStr);
                arr.put(entry);
                prefs.edit().putString("pending_json", arr.toString()).apply();
            } catch (Exception ignored) {}

            int newIntake = currentIntakeMl + perIntervalMl;
            Toast.makeText(
                context,
                "💧 Added +" + perIntervalMl + " ml in HydroPulse!",
                Toast.LENGTH_SHORT
            ).show();

            MainActivity.notifyDrinkLoggedFromNotification();
            scheduleNextInterval(context, intervalMins, perIntervalMl, newIntake, totalDailyMl);
            return;
        }

        // 3. Trigger Hardware Vibration & Audible Chime Immediately!
        triggerDirectVibrationAndSound(context);

        // 4. Ensure High-Priority Heads-Up NotificationChannel exists
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                MainActivity.CHANNEL_ID,
                "HydroPulse Pop-Up Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Floating heads-up water reminders with sound and vibration");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 350, 150, 350});
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
            channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes);
            nm.createNotificationChannel(channel);
        }

        // 5. PendingIntents for Card Tap, "+Drink" Button, and "✕" Dismiss Button
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent addDrinkIntent = new Intent(context, WaterAlarmReceiver.class);
        addDrinkIntent.setAction(ACTION_ADD_WATER);
        addDrinkIntent.putExtra("intervalMins", intervalMins);
        addDrinkIntent.putExtra("perIntervalMl", perIntervalMl);
        addDrinkIntent.putExtra("currentIntakeMl", currentIntakeMl);
        addDrinkIntent.putExtra("totalDailyMl", totalDailyMl);
        PendingIntent addDrinkPending = PendingIntent.getBroadcast(
            context,
            4001,
            addDrinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent dismissIntent = new Intent(context, WaterAlarmReceiver.class);
        dismissIntent.setAction(ACTION_DISMISS_NOTIF);
        PendingIntent dismissPending = PendingIntent.getBroadcast(
            context,
            4002,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 6. Populate Custom Dark Pill Banner Layout (Matches In-App Banner 1000069144.jpg!)
        String headerText = intervalMins + "-MIN HYDRATION ALERT  Now";
        String titleText = "Time to drink " + perIntervalMl + " ml of water!";
        String subText = "Tap +Drink to log your " + intervalMins + "-min sip quota.";

        RemoteViews customBanner = new RemoteViews(context.getPackageName(), R.layout.custom_water_notification);
        customBanner.setTextViewText(R.id.notif_header_badge, headerText);
        customBanner.setTextViewText(R.id.notif_main_title, titleText);
        customBanner.setTextViewText(R.id.notif_subtitle, subText);
        customBanner.setTextViewText(R.id.notif_drink_btn, "+Drink");
        customBanner.setOnClickPendingIntent(R.id.notif_drink_btn, addDrinkPending);
        customBanner.setOnClickPendingIntent(R.id.notif_close_btn, dismissPending);
        customBanner.setOnClickPendingIntent(R.id.notif_root_card, contentIntent);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, MainActivity.CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(R.drawable.ic_stat_water_drop)
               .setLargeIcon(createHydroPulseLogoBitmap())
               .setContentTitle(titleText)
               .setContentText(subText)
               .setColor(Color.parseColor("#0284C7"))
               .setAutoCancel(true)
               .setPriority(Notification.PRIORITY_MAX)
               .setCategory(Notification.CATEGORY_ALARM)
               .setVisibility(Notification.VISIBILITY_PUBLIC)
               .setDefaults(Notification.DEFAULT_ALL)
               .setContentIntent(contentIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setCustomContentView(customBanner);
            builder.setCustomBigContentView(customBanner);
            builder.setCustomHeadsUpContentView(customBanner);
        } else {
            builder.setContent(customBanner);
        }

        if (nm != null) {
            nm.notify(NOTIF_ID, builder.build());
        }

        scheduleNextInterval(context, intervalMins, perIntervalMl, currentIntakeMl, totalDailyMl);
    }

    // Explicitly triggers phone vibration motor + audible chime so it works even on strict OEM skins
    private void triggerDirectVibrationAndSound(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = new long[]{0, 350, 150, 350, 150, 450};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    vibrator.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {}

        // Play default notification sound + synthesized hydration chime
        new Thread(() -> {
            try {
                Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (notifUri != null) {
                    Ringtone r = RingtoneManager.getRingtone(context, notifUri);
                    if (r != null) {
                        r.play();
                    }
                }
            } catch (Exception ignored) {}

            try {
                int sampleRate = 16000;
                int numSamples = sampleRate; // 1.0 sec two-tone chime
                short[] pcm = new short[numSamples];
                for (int i = 0; i < numSamples; i++) {
                    double t = (double) i / sampleRate;
                    double freq = (t < 0.45) ? 587.33 : 880.0;
                    double localT = (t < 0.45) ? (t / 0.45) : ((t - 0.45) / 0.55);
                    double env = Math.max(0.0, 1.0 - localT);
                    pcm[i] = (short) (Math.sin(2.0 * Math.PI * freq * t) * 22000 * env);
                }
                AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
                AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
                AudioTrack track = new AudioTrack(
                    attrs,
                    format,
                    pcm.length * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                );
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(1100);
                track.release();
            } catch (Exception ignored) {}
        }).start();
    }

    private void scheduleNextInterval(Context context, int intervalMins, int perIntervalMl, int currentIntakeMl, int totalDailyMl) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null && intervalMins > 0) {
            Intent nextIntent = new Intent(context, WaterAlarmReceiver.class);
            nextIntent.setAction(ACTION_SHOW_REMINDER);
            nextIntent.putExtra("intervalMins", intervalMins);
            nextIntent.putExtra("perIntervalMl", perIntervalMl);
            nextIntent.putExtra("currentIntakeMl", currentIntakeMl);
            nextIntent.putExtra("totalDailyMl", totalDailyMl);

            PendingIntent nextPending = PendingIntent.getBroadcast(
                context,
                2001,
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            long nextTriggerMs = System.currentTimeMillis() + (intervalMins * 60L * 1000L);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, nextPending);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, nextPending);
            }
        }
    }

    private static Bitmap createHydroPulseLogoBitmap() {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(Color.parseColor("#0284C7"));
        RectF rect = new RectF(0, 0, size, size);
        canvas.drawRoundRect(rect, 44f, 44f, paint);

        paint.setColor(Color.parseColor("#0EA5E9"));
        canvas.drawCircle(size / 2f, size / 2f, 70f, paint);

        paint.setColor(Color.WHITE);
        Path drop = new Path();
        drop.moveTo(96f, 36f);
        drop.cubicTo(96f, 36f, 52f, 88f, 52f, 118f);
        drop.arcTo(new RectF(52f, 74f, 140f, 162f), 180f, -180f, false);
        drop.cubicTo(140f, 88f, 96f, 36f, 96f, 36f);
        drop.close();
        canvas.drawPath(drop, paint);

        return bmp;
    }
}
