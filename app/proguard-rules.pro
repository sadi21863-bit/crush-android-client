# R8 rules for Crush Mobile (release/minified builds).
#
# Rewritten 2026-10-31 when isMinifyEnabled flipped to true (decision item 1):
# the measured release artifact carries 47.76 MB of DEX against 24.84 MB of
# compressed engine, so obfuscation is a size win worth the keep-rule
# discipline. Every rule below exists because ONE of these things is true:
# reflection over that code, native (JNI) entry points, or third-party
# interfaces reached by name. If you delete a rule, run the device sanity
# check (one end-to-end turn over the UDS socket; engine start, streaming,
# and a real model call) before trusting the build.

# --- Gson reflection ---------------------------------------------------------
# The parser talks to DTO/data/model classes by field name through Gson. Keep
# the whole data layer: it is small and entirely on the wire path.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.crush.mobile.data.** { *; }
-keep class com.crush.mobile.domain.model.** { *; }

# Gson internals: TypeToken subtypes are created by anonymous classes
# (object : TypeToken<...>() {} in the SSE/event parsing paths).
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# --- junixsocket: the engine's transport -------------------------------------
# AFUNIXSocketFactory.FactoryArg reaches the socket classes by name from the
# OkHttp socketFactory call, and the vendored libjunixsocket-native.so binds
# into this package via JNI (NativeUnixSocket.createSocket et al). Losing
# these breaks the FIRST connection attempt - the app shows a readiness
# timeout, not a stack trace.
-keep class org.newsclub.net.unix.** { *; }
-keepclassmembers class org.newsclub.net.unix.** { native <methods>; }

# junixsocket references compile-only annotations and java.rmi machinery that
# does not exist on Android (AFUNIXSocketCredentials.remotePeerCredentials is
# a desktop-peer feature; none of these paths execute on device). Rules from
# R8's own missing_rules.txt after the 2026-10-31 first minify attempt.
-dontwarn com.kohlschutter.annotations.compiletime.ExcludeFromCodeCoverageGeneratedReport
-dontwarn com.kohlschutter.annotations.compiletime.SuppressFBWarnings
-dontwarn java.rmi.server.RemoteServer
-dontwarn org.eclipse.jdt.annotation.NonNullByDefault

# --- crash attribution -------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
