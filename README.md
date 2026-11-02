# HydroPulse Native Android APK Builder (Real Permissions & Closed-App Alarms)

Why did WebIntoApp not show **Notifications** or **Location** in Android App Info?
Because WebIntoApp's free wrapper compiles an `.apk` **without** `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`, `ACCESS_FINE_LOCATION`, or Android's native `AlarmManager`.

This native Android project includes:
1. **Real Android 13+ Notification Permission (`POST_NOTIFICATIONS`)** & **Location Permission (`ACCESS_FINE_LOCATION`)** in `AndroidManifest.xml` (shows up in **App Info -> Permissions** and pops up the genuine Android system dialog!).
2. **Real Native `AlarmManager` (`WaterAlarmReceiver.java`)**: Fires high-priority Android status-bar notifications even when the app is **completely closed or swiped away**.
3. **Auto-Updates via Netlify (`https://lambent-cranachan-fcf6b3.netlify.app/`)**: Loads your live Netlify site automatically so future updates you drop onto Netlify still appear automatically for everyone!

## How to Compile This APK on GitHub for Free (No PC Software Needed):
1. Unzip this folder.
2. In your GitHub repository (`HydroPulse`), click **Add file -> Upload files**.
3. Drag all files and folders from inside this zip (`app`, `.github`, `build.gradle`, `settings.gradle`) into GitHub and click **Commit changes**.
4. Click the **Actions** tab at the top of your GitHub repository -> wait ~90 seconds for **Build Native HydroPulse APK** to turn green -> click it and download **HydroPulse-Native-Android-APK**!
