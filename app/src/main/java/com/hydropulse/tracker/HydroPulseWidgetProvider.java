package com.hydropulse.tracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HydroPulseWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_WIDGET_ADD_SIP = "com.hydropulse.tracker.ACTION_WIDGET_ADD_SIP";
    public static final String PREFS_WIDGET_STATE = "hydropulse_widget_state";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
            updateSingleWidget(context, appWidgetManager, appWidgetId, options);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateSingleWidget(context, appWidgetManager, appWidgetId, newOptions);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_WIDGET_ADD_SIP.equals(intent.getAction())) {
            SharedPreferences wPrefs = context.getSharedPreferences(PREFS_WIDGET_STATE, Context.MODE_PRIVATE);
            int currentMl = wPrefs.getInt("currentMl", 0);
            int targetMl = wPrefs.getInt("targetMl", 3050);
            int sipMl = wPrefs.getInt("sipMl", 110);
            int streak = wPrefs.getInt("streakDays", 0);

            int updatedMl = currentMl + sipMl;
            wPrefs.edit().putInt("currentMl", updatedMl).apply();

            // Queue drink for MainActivity WebView sync
            try {
                String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                SharedPreferences nPrefs = context.getSharedPreferences(MainActivity.PREFS_NOTIF_LOGS, Context.MODE_PRIVATE);
                String existing = nPrefs.getString("pending_json", "[]");
                JSONArray arr = new JSONArray(existing);
                JSONObject entry = new JSONObject();
                entry.put("amount", sipMl);
                entry.put("time", timeStr);
                arr.put(entry);
                nPrefs.edit().putString("pending_json", arr.toString()).apply();
            } catch (Exception ignored) {}

            Toast.makeText(context, "💧 +" + sipMl + " ml added (" + updatedMl + " / " + targetMl + " ml)!", Toast.LENGTH_SHORT).show();
            MainActivity.notifyDrinkLoggedFromNotification();
            refreshAllWidgets(context);
        }
    }

    public static void refreshAllWidgets(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName comp = new ComponentName(context, HydroPulseWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(comp);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    Bundle opts = manager.getAppWidgetOptions(id);
                    updateSingleWidget(context, manager, id, opts);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void updateSingleWidget(Context context, AppWidgetManager manager, int appWidgetId, Bundle options) {
        SharedPreferences wPrefs = context.getSharedPreferences(PREFS_WIDGET_STATE, Context.MODE_PRIVATE);
        int currentMl = wPrefs.getInt("currentMl", 0);
        int targetMl = Math.max(1000, wPrefs.getInt("targetMl", 3050));
        int sipMl = Math.max(50, wPrefs.getInt("sipMl", 110));
        int streakDays = wPrefs.getInt("streakDays", 0);

        int minW = 160;
        int minH = 160;
        if (options != null) {
            minW = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160));
            minH = Math.max(110, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160));
        }

        boolean isDark = (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        Bitmap bmp = renderGlassyWaveWidgetBitmap(minW, minH, currentMl, targetMl, streakDays, isDark);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_hydropulse);
        views.setImageViewBitmap(R.id.widget_wave_canvas_img, bmp);
        views.setTextViewText(R.id.widget_add_sip_btn, "💧 +Sip (" + sipMl + " ml)");

        // Click on "+Sip" button logs water immediately
        Intent sipIntent = new Intent(context, HydroPulseWidgetProvider.class);
        sipIntent.setAction(ACTION_WIDGET_ADD_SIP);
        PendingIntent sipPending = PendingIntent.getBroadcast(
            context,
            5001,
            sipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_add_sip_btn, sipPending);

        // Click on widget body opens HydroPulse app
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            context,
            5002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_wave_canvas_img, openPending);

        manager.updateAppWidget(appWidgetId, views);
    }

    private static Bitmap renderGlassyWaveWidgetBitmap(int dpW, int dpH, int currentMl, int targetMl, int streakDays, boolean isDark) {
        int w = Math.min(800, Math.max(320, dpW * 2));
        int h = Math.min(900, Math.max(320, dpH * 2));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RectF cardRect = new RectF(4f, 4f, w - 4f, h - 4f);
        float radius = 48f;

        // 1. Clip to rounded glassy card
        Path clipPath = new Path();
        clipPath.addRoundRect(cardRect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        // 2. Translucent Glassy Background (Light vs Dark mode)
        if (isDark) {
            paint.setColor(Color.argb(215, 15, 23, 42)); // #0F172A translucent dark glass
        } else {
            paint.setColor(Color.argb(225, 240, 249, 255)); // #F0F9FF translucent light glass
        }
        canvas.drawRect(cardRect, paint);

        // 3. Smooth Dual Sine-Wave Liquid Fill
        float ratio = Math.min(0.92f, Math.max(0.08f, (float) currentMl / (float) targetMl));
        float waterTopY = h * (1.0f - ratio);

        // Back wave
        Path wave2 = new Path();
        wave2.moveTo(0, h);
        for (int x = 0; x <= w; x += 12) {
            float y = waterTopY + (float) Math.sin((x * 0.022) + 1.8) * 12f;
            wave2.lineTo(x, y);
        }
        wave2.lineTo(w, h);
        wave2.close();
        Paint wave2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave2Paint.setColor(Color.argb(95, 56, 189, 248));
        canvas.drawPath(wave2, wave2Paint);

        // Front wave with gradient
        Path wave1 = new Path();
        wave1.moveTo(0, h);
        for (int x = 0; x <= w; x += 10) {
            float y = waterTopY + (float) Math.sin(x * 0.026) * 14f;
            wave1.lineTo(x, y);
        }
        wave1.lineTo(w, h);
        wave1.close();
        Paint wave1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave1Paint.setShader(new LinearGradient(
            0, waterTopY, 0, h,
            Color.argb(155, 14, 165, 233),
            Color.argb(210, 2, 132, 199),
            Shader.TileMode.CLAMP
        ));
        canvas.drawPath(wave1, wave1Paint);

        // 4. Typography (Header, Percentage Meter, Consumption Requirement)
        int pct = Math.min(100, Math.round(((float) currentMl / (float) targetMl) * 100f));
        int remMl = Math.max(0, targetMl - currentMl);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Top badge
        textPaint.setColor(isDark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
        textPaint.setTextSize(Math.max(20f, w * 0.055f));
        textPaint.setFakeBoldText(true);
        canvas.drawText("💧 HYDROPULSE  •  🔥 " + streakDays + "d", w / 2f, h * 0.16f, textPaint);

        // Big Center Percentage
        textPaint.setColor(isDark ? Color.WHITE : Color.parseColor("#0F172A"));
        textPaint.setTextSize(Math.max(42f, w * 0.15f));
        canvas.drawText(pct + "%", w / 2f, h * 0.42f, textPaint);

        // Total Consumption / Requirement Line
        textPaint.setTextSize(Math.max(22f, w * 0.068f));
        canvas.drawText(currentMl + " / " + targetMl + " ml", w / 2f, h * 0.54f, textPaint);

        // Remaining Subtitle
        textPaint.setFakeBoldText(false);
        textPaint.setColor(isDark ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155"));
        textPaint.setTextSize(Math.max(18f, w * 0.05f));
        String remText = remMl > 0 ? (remMl + " ml left today") : "Daily Goal Complete! 🎉";
        canvas.drawText(remText, w / 2f, h * 0.64f, textPaint);

        canvas.restore();

        // 5. Glassy Specular Border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3.5f);
        borderPaint.setColor(isDark ? Color.argb(120, 56, 189, 248) : Color.argb(160, 14, 165, 233));
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint);

        return bmp;
    }
}
