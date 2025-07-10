# Google Maps API Key Setup Guide

The app is currently using the API key:
```
AIzaSyASDymHsKY9mAZ1-INzVhVskC7x4EVCPg0
```

## Fixing the API Key Authorization

To fix the map rendering issue, you need to properly register your app's signing certificate in the Google Cloud Console:

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Select or create the project that owns the API key
3. Navigate to APIs & Services → Credentials
4. Find the API key "AIzaSyASDymHsKY9mAZ1-INzVhVskC7x4EVCPg0" and edit it
5. Under "Application restrictions", select "Android apps"
6. Add your app's SHA-1 certificate fingerprint and package name:
   - SHA-1 certificate fingerprint: `2D:B7:5A:31:71:CE:9F:40:6C:F3:6B:D8:0D:5B:8C:13:8B:62:B1:3E`
   - Package name: `com.spiritwisestudios.gpstracker`
7. Save the changes

## Finding Your App's SHA-1 Fingerprint

If you need a different SHA-1 fingerprint for your specific development environment:

### For debug builds (Android Studio development):
```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

### For release builds:
```
keytool -list -v -keystore <path_to_your_keystore> -alias <your_alias_name>
```

## Alternative: Generate a New API Key

If you cannot access the existing API key, you can generate a new one:

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Select your project
3. Navigate to APIs & Services → Credentials
4. Create a new API key
5. Restrict it to Android apps with your app's SHA-1 fingerprint and package name
6. Enable the required APIs:
   - Maps SDK for Android
   - Places API (if using Places features)
7. Update the API key in:
   - `AndroidManifest.xml`
   - `app/src/main/res/values/api_keys.xml` 