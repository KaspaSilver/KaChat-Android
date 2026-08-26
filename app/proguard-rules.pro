# KaChat ProGuard rules

# Keep data classes used with Gson serialization (Retrofit DTOs)
-keepclassmembers class com.kachat.app.services.** { *; }
-keepclassmembers class com.kachat.app.models.** { *; }

# Same as above, for the Gson-deserialized inline-message envelopes that live in util instead of
# models (VoiceMessageContent in VoiceMessage.kt/ImageMessage.kt, MessageReplyContent in
# MessageReply.kt) — this package had no keep rule at all, so R8 was free to rename their fields
# in release builds. Gson's reflection then couldn't match "mimeType"/"content"/etc. from incoming
# JSON to the renamed fields, leaving them null even though the JSON genuinely had that data —
# confirmed via device logcat: every incoming photo/voice message failed to parse with
# "NullPointerException: Parameter specified as non-null is null" on an obfuscated method name,
# rendering as a raw JSON/base64 text bubble instead of an image, in release builds only (debug
# has isMinifyEnabled = false so this never reproduced there).
-keepclassmembers class com.kachat.app.util.** { *; }

# Two more Gson-persisted payloads that live outside services/models/util, so nothing above
# covered them: R8 was free to rename their fields, which makes the persisted JSON readable only
# by the exact build that wrote it — every later release silently reads back null/0 fields (the
# cold-storage snapshot's `address` is declared non-null, so a stale payload could hand the UI a
# null String). Keeping the field names makes both payloads version-stable like every other
# Gson store in the app.
-keepclassmembers class com.kachat.app.repository.PortfolioRepository$StoredPriceHistory { *; }
-keepclassmembers class com.kachat.app.repository.PortfolioRepository$StoredPricePoint { *; }
-keepclassmembers class com.kachat.app.repository.PortfolioRepository$StoredCurrentPrice { *; }
-keepclassmembers class com.kachat.app.viewmodels.ColdStorageViewModel$AddressRow { *; }

# Gson: retain generic signatures of TypeToken and its (usually anonymous, e.g.
# `object : TypeToken<List<Account>>() {}`) subclasses. Without this, R8 can merge/strip those
# synthetic subclasses and drop the generic signature Gson reads at runtime, throwing
# "TypeToken must be created with a type argument" the moment any Gson-backed store (accounts,
# pending KNS commits, etc.) is read — confirmed via device logcat: WalletManager.createWallet()
# -> setActiveAccount() -> getAccounts() crashes on this exact line the instant an account is
# first saved, then every subsequent launch crash-loops on the same read at Application.onCreate.
# Only ever showed up in release builds since debug has isMinifyEnabled = false and R8 never
# runs there. Rule per Gson's own recommendation for R8 3.0+.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# gRPC
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Protobuf-lite (protowire.* generated messages, e.g. Messages.KaspadRequest/Rpc.RpcBlock):
# GeneratedMessageLite finds its own fields by name via reflection at runtime (for
# serialization), matching them against the schema descriptor — confirmed via device logcat,
# every single node probe failed with "RuntimeException: Field id_ for zf.b not found" the
# instant R8 renamed those fields, meaning every gRPC call threw before any network I/O ever
# happened. This is what actually caused every "connection status stuck red" report — nothing
# to do with networks/WiFi/seed nodes, which were red herrings from raw TCP-level testing that
# never touched this reflection failure. Standard rule per protobuf-lite's own R8 guidance.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Room
-keep class androidx.room.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Strip verbose/debug logging from release builds entirely (R8 removes the calls and, with them,
# any string-building they'd have done). Info/warn/error lines are kept — they are the field
# diagnostics ("ApiLog" failures, sync errors) and were already written not to carry secrets.
# Debug builds are untouched (isMinifyEnabled = false there).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
