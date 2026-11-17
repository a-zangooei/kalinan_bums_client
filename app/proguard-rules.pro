# Add project specific ProGuard rules here.
# Keep stack trace line numbers for DeveloperLogsDialog crash diagnostics
-keepattributes SourceFile,LineNumberTable

# Preserve data models and structured log models
-keep class com.example.data.model.** { *; }
-keep class com.example.data.logger.** { *; }

# Keep Tink / EncryptedSharedPreferences cryptographic primitives used by UserPreferences
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Preserve Jsoup, OkHttp, and Coroutines metadata
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit & Firebase Components discovery (R8 Full Mode strips <init>() when only class name is kept)
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
    <init>();
}
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { *; }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { *; }
-keep class com.google.mlkit.vision.text.internal.TextRegistrar { *; }

# Preserve Dynamite local module descriptors read reflectively by DynamiteModule.getLocalVersion
-keep class com.google.android.gms.dynamite.descriptors.com.google.mlkit.dynamite.text.** {
    public static *;
    public <init>();
}

# Preserve Bundled Latin OCR creator and VisionKit pipeline JNI / reflection targets
-keep class com.google.mlkit.vision.text.bundled.** { *; }
-keep class com.google.android.libraries.vision.visionkit.pipeline.alt.NativePipelineImpl { *; }
-keep class com.google.android.libraries.vision.visionkit.pipeline.alt.PipelineException { *; }
-keep class com.google.android.libraries.vision.visionkit.pipeline.AndroidAssetUtil { *; }

