# Google Maps API Key Setup Guide

The app reads its Google Maps/Places/Directions API key from `local.properties`
(which is gitignored and never committed):

```properties
MAPS_API_KEY=your_api_key_here
```

At build time, Gradle injects this value into:
- the manifest `com.google.android.geo.API_KEY` meta-data entry (used by the Maps SDK), and
- `BuildConfig.MAPS_API_KEY` (used by the Places SDK initialization and the
  Directions / Places web-service calls).

If the key is missing, the app builds but the map will not load, and a warning
is logged at startup.

> **Note:** An earlier revision of this repo committed a plaintext API key to
> the manifest, so that key exists in git history. Treat it as public: restrict
> it in Google Cloud Console (or rotate it) before shipping anything.

## Required APIs

Enable these in Google Cloud Console for the project that owns the key:

- Maps SDK for Android
- Places API
- Directions API

Wikipedia content requires no key.

## Restricting the key

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Select the project that owns the API key
3. Navigate to APIs & Services → Credentials and edit the key
4. Under "API restrictions", limit the key to the three APIs above
5. Under "Application restrictions": note that this app calls the Directions
   and Places web services over HTTP, which do not send Android app
   credentials. An Android-app-restricted key will work for the map itself but
   those HTTP calls will be denied. For development, leave application
   restrictions off; for production, split into two keys (an Android-restricted
   key for the Maps/Places SDKs and a separate key for web-service calls,
   ideally proxied through a backend).

## Finding Your App's SHA-1 Fingerprint

### For debug builds (Android Studio development):
```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

### For release builds:
```
keytool -list -v -keystore <path_to_your_keystore> -alias <your_alias_name>
```
