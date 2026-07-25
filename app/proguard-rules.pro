# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Readable crash reports: keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Names that are part of stored data ---
#
# Anything below is a name the app has already written to disk. Obfuscating it
# would leave a database whose contents no longer parse — the failure would
# only show up in a release build, on a device that had used a debug one.

# LatLng is stored as Gson JSON in Room columns, so its field names are the
# on-disk format. Kept whole: the fields are only ever touched reflectively,
# so shrinking would otherwise be free to drop them.
-keep class com.spiritwisestudios.gpstracker.domain.model.LatLng { *; }

# Gson needs generic signatures to resolve TypeToken, and the converter uses
# an anonymous TypeToken subclass.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Enum constants are persisted by name — content sources and detail levels in
# Room, categories and tiers in DataStore — and read back with valueOf.
-keepclassmembers enum com.spiritwisestudios.gpstracker.** { *; }