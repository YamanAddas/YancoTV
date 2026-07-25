package com.yancotv.shared.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastStreamClassifierTest {
    private fun plan(video: CastVideoCodec, audio: CastAudioCodec) = classifyForCast(CastStreamProfile(video, audio))

    @Test
    fun h264AacIsCheapRemux() {
        assertEquals(CastPlan.REMUX, plan(CastVideoCodec.H264, CastAudioCodec.AAC))
    }

    @Test
    fun h264WithDolbyAudioTranscodesAudioOnly() {
        // AC-3/E-AC-3 passthrough is unreliable on an arbitrary sink → AAC.
        assertEquals(CastPlan.TRANSCODE_AUDIO, plan(CastVideoCodec.H264, CastAudioCodec.AC3))
        assertEquals(CastPlan.TRANSCODE_AUDIO, plan(CastVideoCodec.H264, CastAudioCodec.EAC3))
    }

    @Test
    fun unknownOrOtherAudioIsTranscodedToBeSafe() {
        assertEquals(CastPlan.TRANSCODE_AUDIO, plan(CastVideoCodec.H264, CastAudioCodec.OTHER))
        assertEquals(CastPlan.TRANSCODE_AUDIO, plan(CastVideoCodec.H264, CastAudioCodec.UNKNOWN))
    }

    @Test
    fun hevcAlwaysNeedsVideoTranscodeRegardlessOfAudio() {
        // HEVC-in-TS is categorically unsupported on Cast.
        assertEquals(CastPlan.TRANSCODE_VIDEO, plan(CastVideoCodec.HEVC, CastAudioCodec.AAC))
        assertEquals(CastPlan.TRANSCODE_VIDEO, plan(CastVideoCodec.HEVC, CastAudioCodec.AC3))
    }

    @Test
    fun unknownVideoIsTreatedOptimisticallyAsH264() {
        assertEquals(CastPlan.REMUX, plan(CastVideoCodec.UNKNOWN, CastAudioCodec.AAC))
        assertEquals(CastPlan.TRANSCODE_AUDIO, plan(CastVideoCodec.UNKNOWN, CastAudioCodec.AC3))
    }

    @Test
    fun incompatibleVideoFallsBackToMirror() {
        assertEquals(CastPlan.MIRROR, plan(CastVideoCodec.OTHER, CastAudioCodec.AAC))
    }
}
