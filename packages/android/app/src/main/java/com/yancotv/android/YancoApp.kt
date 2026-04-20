package com.yancotv.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * App entry. Koin is started here with an empty module list in MK.0;
 * actual modules (db, http, sources, player) are wired from MK.1 onward.
 */
class YancoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@YancoApp)
            modules(emptyList())
        }
    }
}
