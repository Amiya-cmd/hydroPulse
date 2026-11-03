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
    // Always loads the built-in app first (zero Netlify 404 errors!)
    private static final String LOCAL_ASSET_URL = "file:///android_asset/index.html";
    // Optional background auto-update directly from your GitHub repo:
    private static final String GITHUB_RAW_UPDATE_URL = "https://raw.githubusercontent.com/Amiya-cmd/hydroPulse/main/app/src/main/assets/index.html";
    public static final String CHANNEL_ID = "hydropulse_reminders_channel";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        // Prompt for Android 13+ Notification permission right on startup
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
        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                requestLocationPermissionNative();
                callback.invoke(origin, true, false);
            }
        });

        // 1. Immediately load the built-in app so it NEVER shows "Site not found"
        webView.loadUrl(LOCAL_ASSET_URL);

        // 2. Silently check your GitHub repo in the background for any newer index.html updates
        checkForGitHubAutoUpdate();
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
                        SharedPreferences prefs = getSharedPreferences("hydropulse_ota", MODE_PRIVATE);
                        int lastLen = prefs.getInt("html_len", -1);
                        if (lastLen != -1 && lastLen != remoteHtml.length()) {
                            runOnUiThread(() -> {
                                if (webView != null) {
                                    webView.loadDataWithBaseURL(LOCAL_ASSET_URL, remoteHtml, "text/html", "UTF-8", null);
                                }
                            });
                        }
                        prefs.edit().putInt("html_len", remoteHtml.length()).apply();
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {
                // Offline or no update needed — stays on built-in LOCAL_ASSET_URL
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HydroPulse Water Reminders",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("15, 30, and 60 minute hydration interval alerts");
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
        public void scheduleNativeReminder(long delayMs, int intervalMins, String title, String body) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(context, WaterAlarmReceiver.class);
            intent.putExtra("title", title);
            intent.putExtra("body", body);
            intent.putExtra("intervalMins", intervalMins);

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
