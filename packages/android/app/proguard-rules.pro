# MK.0 stub. Real rules land in Stage 1.4 when R8 minification is enabled.

# ───── MK.9 (Stage 1.2) — FFmpeg ExoPlayer extension ─────
# These rules ride with MK.9 even though R8 is currently off (isMinifyEnabled
# = false in app/build.gradle.kts). When Stage 1.4 flips R8 on globally these
# stay correct — without them R8 strips the JNI bridge and the extension
# silently falls back to platform decoders, re-opening MB-14 on release builds.
#
# DefaultRenderersFactory loads the extension via reflection
# (`Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")` etc.),
# so the classes have no static reference from app code and R8 would otherwise
# treat them as unused. Native methods need their JNI signatures intact too.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclassmembers class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}
# Native libs themselves aren't subject to R8, but `LibraryLoader` resolves
# them by name via System.loadLibrary("ffmpegJNI") — keep that string literal.
-keepclassmembers class androidx.media3.common.util.LibraryLoader {
    *;
}
