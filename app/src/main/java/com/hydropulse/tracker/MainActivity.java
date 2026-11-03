package com.hydropulse.tracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String LOCAL_ASSET_URL = "file:///android_asset/index.html";
    private static final String GITHUB_RAW_UPDATE_URL = "https://raw.githubusercontent.com/Amiya-cmd/hydroPulse/main/app/src/main/assets/index.html";
    public static final String CHANNEL_ID = "hydropulse_reminders_channel";
    public static final String PREFS_NOTIF_LOGS = "hydropulse_notif_logs";
    private static MainActivity activeInstance;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        createNotificationChannel();
        requestNotificationPermissionNative();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                triggerWebViewDrinkSync();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                requestLocationPermissionNative();
                callback.invoke(origin, true, false);
            }
        });

        java.io.File cachedFile = new java.io.File(getFilesDir(), "cached_index.html");
        if (cachedFile.exists() && cachedFile.length() > 1000) {
            webView.loadUrl("file://" + cachedFile.getAbsolutePath());
        } else {
            webView.loadUrl(LOCAL_ASSET_URL);
        }
        checkForGitHubAutoUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        triggerWebViewDrinkSync();
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    public static void notifyDrinkLoggedFromNotification() {
        if (activeInstance != null) {
            activeInstance.runOnUiThread(() -> activeInstance.triggerWebViewDrinkSync());
        }
    }

    public void triggerWebViewDrinkSync() {
        if (webView != null) {
            webView.evaluateJavascript("if(window.syncDrinksLoggedFromNotification) window.syncDrinksLoggedFromNotification();", null);
        }
    }

    private void checkForGitHubAutoUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_RAW_UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    final String remoteHtml = sb.toString();
                    if (remoteHtml.contains("HydroPulse") && remoteHtml.contains("</html>")) {
                        java.io.File cachedFile = new java.io.File(getFilesDir(), "cached_index.html");
                        SharedPreferences prefs = getSharedPreferences("hydropulse_ota", MODE_PRIVATE);
                        int lastHash = prefs.getInt("html_hash", 0);
                        int newHash = remoteHtml.hashCode();
                        if (lastHash != newHash || !cachedFile.exists()) {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(cachedFile, false);
                            fos.write(remoteHtml.getBytes("UTF-8"));
                            fos.close();
                            prefs.edit().putInt("html_hash", newHash).apply();
                            if (lastHash != 0) {
                                runOnUiThread(() -> {
                                    if (webView != null) {
                                        webView.loadUrl("file://" + cachedFile.getAbsolutePath());
                                    }
                                });
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HydroPulse Water Reminders",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Interactive hydration reminders with 1-tap water logging");
            channel.enableVibration(true);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public void requestNotificationPermissionNative() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    public void requestLocationPermissionNative() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }, 102);
            }
        }
    }

    public void requestBatteryExemptionNative() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    public class AndroidBridge {
        private final Context context;

        AndroidBridge(Context ctx) {
            this.context = ctx;
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> requestNotificationPermissionNative());
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            runOnUiThread(() -> requestLocationPermissionNative());
        }

        @JavascriptInterface
        public void requestBackgroundPermission() {
            runOnUiThread(() -> requestBatteryExemptionNative());
        }

        @JavascriptInterface
        public String consumePendingNotificationDrinks() {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NOTIF_LOGS, MODE_PRIVATE);
            String json = prefs.getString("pending_json", "[]");
            prefs.edit().putString("pending_json", "[]").apply();
            return json;
        }

        @JavascriptInterface
        public void scheduleNativeReminder(long delayMs, int intervalMins, int perIntervalMl, int currentIntakeMl, int totalDailyMl) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(context, WaterAlarmReceiver.class);
            intent.setAction(WaterAlarmReceiver.ACTION_SHOW_REMINDER);
            intent.putExtra("intervalMins", intervalMins);
            intent.putExtra("perIntervalMl", perIntervalMl);
            intent.putExtra("currentIntakeMl", currentIntakeMl);
            intent.putExtra("totalDailyMl", totalDailyMl);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            long triggerAtMs = System.currentTimeMillis() + Math.max(5000, delayMs);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
            }
        }

        @JavascriptInterface
        public void cancelNativeReminder() {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;
            Intent intent = new Intent(context, WaterAlarmReceiver.class);
            intent.setAction(WaterAlarmReceiver.ACTION_SHOW_REMINDER);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(pendingIntent);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
