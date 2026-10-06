# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android/sdk/tools/proguard/proguard-android.txt

# Keep OpenCode API models
-keep class com.opencode.chat.data.api.dto.** { *; }
-keep class com.opencode.chat.domain.model.** { *; }

# Keep Retrofit interfaces
-keep interface com.opencode.chat.data.api.OpenCodeApi { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
