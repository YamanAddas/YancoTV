package com.yancotv.shared.cast

/** Video codec of the source stream, as far as the on-device prober can tell. */
enum class CastVideoCodec { H264, HEVC, OTHER, UNKNOWN }

/** Audio codec of the source stream. */
enum class CastAudioCodec { AAC, AC3, EAC3, OTHER, UNKNOWN }

/** The probed shape of a stream the user wants to cast to a bare Chromecast. */
data class CastStreamProfile(val video: CastVideoCodec, val audio: CastAudioCodec)

/**
 * What the MK.26 B.2 on-device cast proxy must do to a stream before Google's
 * Default Media Receiver can play it. The proxy ALWAYS repackages raw TS into
 * HLS and injects provider headers + CORS; this plan only decides how much
 * TRANSCODING is needed on top of that.
 */
enum class CastPlan {
    /** Copy both tracks, repackage TS -> HLS. Cheapest; H.264 + AAC. */
    REMUX,

    /** Copy video, transcode AC-3/E-AC-3 (or unknown) audio -> AAC. ~realtime on a phone. */
    TRANSCODE_AUDIO,

    /** Transcode HEVC video -> H.264 (+ audio as needed). Phase 2; HW-only, heavy. */
    TRANSCODE_VIDEO,

    /** Can't be made castable cheaply (incompatible video) — recommend screen mirroring. */
    MIRROR,
}

/**
 * Pure decision: given a probed [CastStreamProfile], pick the cheapest [CastPlan]
 * that yields a Default-Media-Receiver-playable stream.
 *
 * Rationale (from the 2026-06-15 cast research): HEVC-in-TS is categorically
 * unsupported by Cast, so it always needs a video transcode. AC-3 passthrough is
 * unreliable on an arbitrary sink, so AC-3/E-AC-3 is transcoded to AAC. Unknown
 * audio is treated as risky → transcoded to AAC. Unknown video is treated
 * optimistically as H.264 (cheapest attempt; if it doesn't play the user mirrors).
 */
fun classifyForCast(profile: CastStreamProfile): CastPlan = when (profile.video) {
    CastVideoCodec.HEVC -> CastPlan.TRANSCODE_VIDEO
    CastVideoCodec.OTHER -> CastPlan.MIRROR
    CastVideoCodec.H264, CastVideoCodec.UNKNOWN ->
        when (profile.audio) {
            CastAudioCodec.AAC -> CastPlan.REMUX
            CastAudioCodec.AC3,
            CastAudioCodec.EAC3,
            CastAudioCodec.OTHER,
            CastAudioCodec.UNKNOWN,
            -> CastPlan.TRANSCODE_AUDIO
        }
}
