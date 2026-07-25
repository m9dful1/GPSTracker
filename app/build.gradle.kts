import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Optional API keys from local.properties (gitignored — never commit keys).
// MAPS_API_KEY unlocks the Google Maps provider in settings (see
// app/docs/map_providers.md). GEMINI_API_KEY (a free AI Studio key,
// https://aistudio.google.com/apikey) switches tour narration to AI-written
// scripts (app/docs/ai_narration.md). Without either key the app runs fully
// keyless on OpenStreetMap services and Wikipedia narration.
val localProperties: Properties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY") ?: ""

// AdMob IDs (app/docs/ads.md). Google's public sample IDs — which only ever
// serve test ads — fill in until real ones from an AdMob app registration
// are added to local.properties. Debug builds always use the test ad units.
val testBannerAdUnitId = "ca-app-pub-3940256099942544/6300978111"
val testInterstitialAdUnitId = "ca-app-pub-3940256099942544/1033173712"
val admobAppId: String = localProperties.getProperty("ADMOB_APP_ID")
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerAdUnitId: String =
    localProperties.getProperty("ADMOB_BANNER_AD_UNIT_ID") ?: testBannerAdUnitId
val admobInterstitialAdUnitId: String =
    localProperties.getProperty("ADMOB_INTERSTITIAL_AD_UNIT_ID") ?: testInterstitialAdUnitId

// Release signing, from local.properties or the environment — never committed.
// A clone without the keystore still builds a release APK; it just comes out
// unsigned, which is what CI wants and the only thing it could do anyway.
fun signingProperty(name: String): String? =
    localProperties.getProperty(name) ?: System.getenv(name)

val releaseKeystore: File? = signingProperty("RELEASE_STORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.spiritwisestudios.gpstracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spiritwisestudios.gpstracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    signingConfigs {
        // Only configured when a keystore is actually available; see
        // signingProperty above for where the values come from.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingProperty("RELEASE_STORE_PASSWORD")
                keyAlias = signingProperty("RELEASE_KEY_ALIAS")
                keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")

            // R8 on, with the keep rules in proguard-rules.pro for the names
            // that are part of stored data. Resource shrinking is safe here:
            // nothing looks up a resource by name at runtime.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$admobBannerAdUnitId\"")
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"$admobInterstitialAdUnitId\"")
        }
        debug {
            // Never request real ads from a development build
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$testBannerAdUnitId\"")
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"$testInterstitialAdUnitId\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time below API 26
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-Xsam-conversions=class"
        )
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Room writes the schema of every version here, and the files are committed.
// Without that record a later migration has nothing to migrate *from*, which
// is how a schema change ends up destroying the user's journal.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    
    // MapLibre map rendering (OpenStreetMap vector tiles, no API key)
    implementation(libs.maplibre)

    // Google Maps rendering, for the optional Google map provider
    implementation(libs.play.services.maps)

    // AdMob banner + interstitial ads, with the UMP consent flow
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    // Destination search results list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Room database for local caching (using KSP instead of KAPT)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // OkHttp: every API service here is hand-rolled against it. Declared
    // explicitly rather than inherited from the map SDK, which is a
    // swappable choice and shouldn't be what supplies the HTTP client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson, for the LatLng column converter
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Dagger Hilt for dependency injection (using KSP instead of KAPT)
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    
    // Jetpack DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Real org.json for local unit tests (the android.jar stub throws)
    testImplementation("org.json:json:20231013")
}