# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin Coroutines — keep internal dispatcher factories (required for Dispatchers.Main)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keep class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin suspend functions — keep internal markers used by coroutine machinery
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.coroutines.Continuation
-keep class kotlin.coroutines.jvm.internal.** { *; }
-keep class kotlinx.coroutines.scheduling.** { *; }

# ScreenshotManager — keep suspend function internals
-keep class com.bubbletranslate.app.util.ScreenshotManager { *; }

# Core service and view classes — keep for WindowManager, touch handlers, and alpha updates
-keep class com.bubbletranslate.app.service.FloatingBubbleService { *; }
-keep class com.bubbletranslate.app.service.SelectionOverlayView { *; }
-keep class com.bubbletranslate.app.service.BubbleView { *; }

# App — keep alpha/opacity fields and methods
-keep class com.bubbletranslate.app.App { *; }

# MainActivity — keep SeekBar listener methods (may be inlined by ProGuard)
-keep class com.bubbletranslate.app.MainActivity { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bubbletranslate.app.**$$serializer { *; }
-keepclassmembers class com.bubbletranslate.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.bubbletranslate.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
