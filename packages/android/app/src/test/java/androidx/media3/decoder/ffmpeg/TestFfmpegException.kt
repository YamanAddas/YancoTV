package androidx.media3.decoder.ffmpeg

/**
 * Test-only stand-in for the production [FfmpegDecoderException] (which is
 * `final` with package-private constructors, so it can't be subclassed or
 * constructed directly from a test). Lives in the same package
 * `androidx.media3.decoder.ffmpeg` so its FQN matches the watchdog
 * classifier's package-prefix check.
 *
 * Test-source-set only — never compiled into the production APK.
 */
class TestFfmpegException(message: String, cause: Throwable? = null) : Exception(message, cause)
