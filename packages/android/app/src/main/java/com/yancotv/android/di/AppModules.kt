package com.yancotv.android.di

import androidx.media3.common.util.UnstableApi
import com.yancotv.android.logger.AndroidLogger
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.reminders.ReminderScheduler
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.sync.AndroidEpgImporter
import com.yancotv.shared.epg.BulkEpgWriter
import com.yancotv.shared.parental.AndroidPinHasher
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.parental.PinHasher
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.catchup.CatchupService
import com.yancotv.shared.content.ContentDetailService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.reminders.ReminderRepository
import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.db.DatabaseFactory
import com.yancotv.shared.db.YancoDatabase
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.epg.androidGunzip
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.createAndroidHttpClient
import com.yancotv.shared.sources.AndroidFileContentReader
import com.yancotv.shared.sources.AndroidKeystoreCredentialStore
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.FileContentReader
import com.yancotv.shared.sources.SourceRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * MK.4 DI wiring. Everything the shell needs (db, http, sources) is bound
 * here as singletons. Screens pull repositories via `koinInject()`.
 *
 * Desktop has its own TS module system — this file is Android-only.
 */
@UnstableApi
val appModule = module {
    single<Logger> { AndroidLogger() }
    single<YancoDatabase> { DatabaseFactory(androidContext()).create() }
    single<YancoDb> { get<YancoDatabase>().db }
    single<SqlDriver> { get<YancoDatabase>().driver }
    single<HttpClient> {
        val prefs = get<AppPreferences>()
        createAndroidHttpClient(
            userAgentProvider = {
                prefs.networkFlow.value.userAgentOverride?.takeIf { it.isNotBlank() }
                    ?: "YancoTV/0.1 (Android)"
            },
            perRequestReadTimeoutMs = {
                prefs.networkFlow.value.readTimeoutSec.takeIf { it > 0 }?.let { it * 1000L }
            },
            cacheDir = androidContext().cacheDir,
        )
    }
    single<CredentialStore> { AndroidKeystoreCredentialStore() }
    single<FileContentReader> { AndroidFileContentReader(androidContext()) }
    single { ContentRepository(get()) }
    single {
        SourceRepository(
            db = get(),
            driver = get(),
            credentialStore = get(),
            http = get(),
            fileReader = get(),
            clock = { System.currentTimeMillis() },
            logger = get(),
        )
    }
    single { SourceSyncCoordinator(context = androidContext(), repo = get(), logger = get()) }
    single { PlaybackController(context = androidContext(), history = get()) }
    single {
        EpgRepository(
            db = get(),
            driver = get(),
            http = get(),
            clock = { System.currentTimeMillis() },
            gunzip = ::androidGunzip,
            logger = get(),
        )
    }
    single { BulkEpgWriter(driver = get(), logger = get()) }
    single {
        AndroidEpgImporter(
            context = androidContext(),
            db = get(),
            writer = get(),
            logger = get(),
        )
    }
    single {
        ReminderRepository(
            db = get(),
            clock = { System.currentTimeMillis() },
        )
    }
    single { ReminderScheduler(androidContext(), get()) }
    single { FavoritesRepository(db = get(), clock = { System.currentTimeMillis() }) }
    single { WatchHistoryRepository(db = get(), clock = { System.currentTimeMillis() }) }
    single<PinHasher> { AndroidPinHasher() }
    single {
        ParentalRepository(
            db = get(),
            hasher = get(),
            clock = { System.currentTimeMillis() },
        )
    }
    single {
        CatchupService(
            contentRepo = get(),
            sourceRepo = get(),
            clock = { System.currentTimeMillis() },
        )
    }
    single { AppPreferences(db = get()) }
    single {
        ContentDetailService(
            db = get(),
            sources = get(),
            http = get(),
            logger = get(),
            clock = { System.currentTimeMillis() },
        )
    }
}
