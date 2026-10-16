# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android/sdk/tools/proguard/proguard-android.txt

# The engine keeps OpenCode API models intact for Gson reflection.
-keep class com.crush.mobile.data.api.dto.** { *; }
-keep class com.crush.mobile.domain.model.** { *; }

# Retrofit interfaces are gone: the only HTTP client now talks to the
# embedded Crush engine over loopback, built on OkHttp directly.

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
