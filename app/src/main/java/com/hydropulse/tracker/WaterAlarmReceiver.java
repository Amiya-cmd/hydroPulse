package com.hydropulse.tracker;

import android.app.AlarmManager;
import android.app.Notification;
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
import android.os.Build;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class WaterAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_SHOW_REMINDER = "com.hydropulse.tracker.ACTION_SHOW_REMINDER";
    public static final String ACTION_ADD_WATER = "com.hydropulse.tracker.ACTION_ADD_WATER";
    public static final int NOTIF_ID = 3001;

    private static final String[] ENGAGING_TITLES = new String[]{
        "💧 Hydration Check-In! Ready for %d ml?",
        "🌊 Keep Your Streak Flowing! Sip %d ml Now",
        "⚡ Instant Energy Boost: Grab %d ml of Water!",
        "✨ Refresh Your Body & Mind with %d ml!",
        "🚰 Time for Your %d-Min Hydration Goal (%d ml)!"
    };

    private static final String[] ENGAGING_TIPS = new String[]{
        "A quick %d ml sip right now keeps your energy high and your daily goal (%d ml) within easy reach. Tap below to log it instantly!",
        "Staying hydrated improves focus and mood! Drink %d ml now and tap '+Add %d ml Now' right from this notification.",
        "Don't let yesterday's morning penalty catch you! Finish your %d ml session sip now to stay on track for your %d ml target."
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int intervalMins = intent.getIntExtra("intervalMins", 30);
        int perIntervalMl = intent.getIntExtra("perIntervalMl", 100);
        int currentIntakeMl = intent.getIntExtra("currentIntakeMl", 0);
        int totalDailyMl = intent.getIntExtra("totalDailyMl", 3000);

        // 1. Handle direct "+Add [X] ml Now" button click from the Notification!
        if (ACTION_ADD_WATER.equals(action)) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIF_ID);
            }

            // Save drink log to SharedPreferences so MainActivity syncs it immediately or on next open
            String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NOTIF_LOGS, Context.MODE_PRIVATE);
            String existing = prefs.getString("pending_json", "[]");
            String newEntry = "{"amount":" + perIntervalMl + ","time":"" + timeStr + ""}";
            String updated;
            if (existing == null || existing.equals("[]") || existing.length() < 2) {
                updated = "[" + newEntry + "]";
            } else {
                updated = existing.substring(0, existing.length() - 1) + "," + newEntry + "]";
            }
            prefs.edit().putString("pending_json", updated).apply();

            int newIntake = currentIntakeMl + perIntervalMl;
            Toast.makeText(
                context,
                "💧 Added +" + perIntervalMl + " ml in HydroPulse!",
                Toast.LENGTH_SHORT
            ).show();

            // Notify MainActivity if currently open
            MainActivity.notifyDrinkLoggedFromNotification();

            // Schedule the next interval reminder automatically
            scheduleNextInterval(context, intervalMins, perIntervalMl, newIntake, totalDailyMl);
            return;
        }

        // 2. Otherwise, show the Engaging Reminder Notification with Custom Logo + Action Button!
        Random rng = new Random();
        int titleIdx = rng.nextInt(ENGAGING_TITLES.length);
        String title;
        if (titleIdx == 4) {
            title = String.format(Locale.US, ENGAGING_TITLES[titleIdx], intervalMins, perIntervalMl);
        } else {
            title = String.format(Locale.US, ENGAGING_TITLES[titleIdx], perIntervalMl);
        }

        int tipIdx = rng.nextInt(ENGAGING_TIPS.length);
        String bigBody;
        if (tipIdx == 1) {
            bigBody = String.format(Locale.US, ENGAGING_TIPS[tipIdx], perIntervalMl, perIntervalMl);
        } else {
            bigBody = String.format(Locale.US, ENGAGING_TIPS[tipIdx], perIntervalMl, totalDailyMl);
        }
        String shortBody = "Tap '+Add " + perIntervalMl + " ml Now' below to log your drink directly!";

        // Intent to open the app when tapping the notification body
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Intent for the direct "+Add [X] ml Now" button on the notification
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

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, MainActivity.CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        // Custom HydroPulse Water-Drop Status Bar Icon + Full-Color Logo Badge + Sky-Blue Brand Color
        builder.setSmallIcon(R.drawable.ic_stat_water_drop)
               .setLargeIcon(createHydroPulseLogoBitmap())
               .setContentTitle(title)
               .setContentText(shortBody)
               .setStyle(new Notification.BigTextStyle().bigText(bigBody).setSummaryText("HydroPulse • " + intervalMins + "-Min Sip Quota"))
               .setAutoCancel(true)
               .setContentIntent(contentIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setColor(Color.parseColor("#0284C7"));
        }

        // Add direct 1-tap water logging action button right on the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            Notification.Action drinkAction = new Notification.Action.Builder(
                R.drawable.ic_stat_water_drop,
                "💧 +Add " + perIntervalMl + " ml Now",
                addDrinkPending
            ).build();
            builder.addAction(drinkAction);

            Notification.Action openAction = new Notification.Action.Builder(
                R.drawable.ic_stat_water_drop,
                "Open Tracker",
                contentIntent
            ).build();
            builder.addAction(openAction);
        }

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, builder.build());
        }

        // Schedule the next recurring interval automatically
        scheduleNextInterval(context, intervalMins, perIntervalMl, currentIntakeMl, totalDailyMl);
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

    // Draws a crisp, colorful HydroPulse Water-Drop Badge Bitmap for the Notification Large Icon
    private static Bitmap createHydroPulseLogoBitmap() {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Rounded square sky-blue background
        paint.setColor(Color.parseColor("#0284C7"));
        RectF rect = new RectF(0, 0, size, size);
        canvas.drawRoundRect(rect, 44f, 44f, paint);

        // Inner cyan glow circle
        paint.setColor(Color.parseColor("#0EA5E9"));
        canvas.drawCircle(size / 2f, size / 2f, 70f, paint);

        // White water droplet
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
