package com.yancotv.android.player

import androidx.media3.common.PlaybackException
import androidx.media3.decoder.ffmpeg.TestFfmpegException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [isFfmpegRelatedError] — the MK.9.4 watchdog's classifier.
 *
 * The watchdog only rebuilds the ExoPlayer when an error trace points back
 * to the vendored FFmpeg extension package. Any other error (network, bad
 * HTTP, platform MediaCodec failures, source format errors) must fall
 * through to the existing PlayerActivity error overlay so the user gets a
 * real "this stream is broken" message instead of a silent rebuild → black
 * screen.
 *
 * Pinning the classifier here means a Media3 version bump that renames
 * exception classes, or a refactor that changes the cause-chain order, or
 * an accidental loosening of the package-prefix match, can't silently
 * break MB-14 recovery on real Fire TV channels.
 *
 * Note: class name is deliberately *not* "FfmpegSomethingTest" — the
 * classifier matches on package prefix, not substring, but keeping the test
 * class name free of the marker keeps the contract intent obvious to
 * future readers.
 */
class WatchdogClassifierTest {
    @Test fun ffmpegPackageExceptionAtTopOfCauseChainClassifies() {
        val cause = TestFfmpegException("bad packet")
        val exception = decodingFailedWithCause(cause)
        assertTrue(isFfmpegRelatedError(exception))
    }

    @Test fun ffmpegPackageExceptionDeepInCauseChainStillClassifies() {
        val deep = TestFfmpegException("bad packet")
        val middle = RuntimeException("middle wrap", deep)
        val top = IllegalStateException("top wrap", middle)
        val exception = decodingFailedWithCause(top)
        assertTrue(isFfmpegRelatedError(exception))
    }

    @Test fun networkErrorWithFfmpegInChainStillSkips() {
        // Wrong errorCode — even if the cause looks FFmpeg-y, network
        // failures must surface to the user, not trigger a rebuild.
        val cause = TestFfmpegException("noise")
        val exception =
            TestPlaybackException(
                message = "io",
                cause = cause,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        assertFalse(isFfmpegRelatedError(exception))
    }

    @Test fun decodingFailedWithoutFfmpegCauseDoesNotRebuild() {
        // MediaCodec failures hit the same errorCode as FFmpeg ones; the
        // package-prefix check is what disambiguates. Without an FFmpeg-
        // package class anywhere in the chain, no rebuild.
        val platformCause = IllegalStateException("MediaCodec hates this")
        val exception = decodingFailedWithCause(platformCause)
        assertFalse(isFfmpegRelatedError(exception))
    }

    @Test fun decoderInitFailedWithFfmpegCauseClassifies() {
        val cause = TestFfmpegException("init")
        val exception =
            TestPlaybackException(
                message = "init failed",
                cause = cause,
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            )
        assertTrue(isFfmpegRelatedError(exception))
    }

    @Test fun decodingFormatUnsupportedWithFfmpegCauseClassifies() {
        val cause = TestFfmpegException("unsupported")
        val exception =
            TestPlaybackException(
                message = "fmt",
                cause = cause,
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        assertTrue(isFfmpegRelatedError(exception))
    }

    @Test fun nullCauseDoesNotCrashClassifier() {
        // Defensive: a PlaybackException with no cause shouldn't trip the
        // walker. The exception itself isn't in the FFmpeg package.
        val exception = decodingFailedWithCause(null)
        assertFalse(isFfmpegRelatedError(exception))
    }

    @Test fun substringMatchOnlyDoesNotFalsePositive() {
        // A class that has "Ffmpeg" in its simple name but NOT in the
        // androidx.media3.decoder.ffmpeg package must NOT classify. This
        // is the false-positive shape that motivated the package-prefix
        // tightening over the original substring matcher.
        val cause = SomeUnrelatedFfmpegRelatedException("noise")
        val exception = decodingFailedWithCause(cause)
        assertFalse(isFfmpegRelatedError(exception))
    }

    private fun decodingFailedWithCause(cause: Throwable?): TestPlaybackException = TestPlaybackException(
        message = "decoding failed",
        cause = cause,
        errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
    )

    /**
     * PlaybackException's primary public constructor delegates to a
     * protected one that captures a timestamp from `SystemClock.elapsed
     * Realtime()`. Subclassing here is the simplest way to fixture the
     * type without standing up Robolectric.
     */
    private class TestPlaybackException(message: String, cause: Throwable?, errorCode: Int) : PlaybackException(message, cause, errorCode)

    /**
     * Lives in the test class's package (`com.yancotv.android.player`),
     * NOT in `androidx.media3.decoder.ffmpeg`. Its name mentions FFmpeg
     * but the package doesn't — the classifier should ignore it.
     */
    private class SomeUnrelatedFfmpegRelatedException(message: String) : Exception(message)
}
