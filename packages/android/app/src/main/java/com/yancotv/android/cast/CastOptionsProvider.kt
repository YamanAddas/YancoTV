package com.yancotv.android.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * MK.26 Track B — Google Cast options. Read lazily by the CAF framework the
 * first time `CastContext.getSharedInstance()` is called — which YancoTV only
 * does behind a `GoogleApiAvailability` gate, so this never runs on Fire OS.
 *
 * Uses Google's DEFAULT Media Receiver (app id CC1AD845): no Cast Developer
 * Console registration, no hosting. It plays H.264/AAC MP4 over HTTPS, which
 * covers the movie/series subset; raw-TS live needs a custom Web Receiver +
 * transcode proxy (B.2/B.3, out of scope for the in-app integration).
 */
class CastOptionsProvider : OptionsProvider {
    // Default Media Receiver (CC1AD845) — registration-free, no Console reg.
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder().setReceiverApplicationId("CC1AD845").build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
