package com.yancotv.android.di

import androidx.media3.common.util.UnstableApi
import app.cash.sqldelight.db.SqlDriver
import com.yancotv.android.logger.AndroidLogger
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.reminders.ReminderScheduler
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.sync.AndroidEpgImporter
import com.yancotv.android.ui.theme.ThemeController
import com.yancotv.shared.catchup.CatchupService
import com.yancotv.shared.content.ContentDetailService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.db.DatabaseFactory
import com.yancotv.shared.db.YancoDatabase
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.epg.BulkEpgWriter
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.epg.androidGunzip
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.createAndroidHttpClient
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.parental.AndroidPinHasher
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.parental.PinHasher
import com.yancotv.shared.reminders.ReminderRepository
import com.yancotv.shared.sources.AndroidFileContentReader
import com.yancotv.shared.sources.AndroidKeystoreCredentialStore
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.FileContentReader
import com.yancotv.shared.sources.SourceRepository
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * MK.4 DI wiring. Everything the shell needs (db, http, sources) is bound
 * here as singletons. Screens pull repositories via `koinInject()`.
 *
 * Desktop has its own TS module system — this file is Android-only.
 */
@UnstableApi
val appModule =
    module {
        single<Logger> { AndroidLogger() }
        single<YancoDatabase> { DatabaseFactory(androidContext()).create() }
        single<YancoDb> { get<YancoDatabase>().db }
        single<SqlDriver> { get<YancoDatabase>().driver }
        // MK.24.I.7 / MB-230 — single shared OkHttpClient for non-player
        // HTTP (Coil image fetches, AndroidEpgImporter XMLTV downloads).
        // Pre-fix the app had three separate OkHttpClient instances (player,
        // Coil, EPG importer), each with its own connection pool, dispatcher
        // thread executor, DNS cache, and TLS session cache. Consolidating
        // the non-player ones into one shared instance halves that overhead.
        // PlaybackController stays on its own OkHttp because its interceptor
        // applies per-source User-Agent and Referer headers that Coil + EPG
        // sync should NOT inherit. Callers that need use-case-specific
        // timeouts should `newBuilder()` from this client — that preserves
        // the connection pool + dispatcher while letting them override
        // timeouts per request.
        single<OkHttpClient> {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        single<HttpClient> {
            val prefs = get<AppPreferences>()
            createAndroidHttpClient(
                userAgentProvider = {
                    prefs.networkFlow.value.userAgentOverride
                        ?.takeIf { it.isNotBlank() }
                        ?: "YancoTV/0.1 (Android)"
                },
                perRequestReadTimeoutMs = {
                    prefs.networkFlow.value.readTimeoutSec
                        .takeIf { it > 0 }
                        ?.let { it * 1000L }
                },
                cacheDir = androidContext().cacheDir,
            )
        }
        single {
            com.yancotv.android.player.subtitles.OpenSubtitlesClient(
                http = get(),
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
        single {
            val sourceRepo: com.yancotv.shared.sources.SourceRepository = get()
            SourceSyncCoordinator(
                syncSource = sourceRepo::syncSource,
                logger = get(),
                kickEpgRefresh = {
                    com.yancotv.android.sync.EpgSyncWorker.enqueueOnce(androidContext())
                },
            )
        }
        // MK.14.8 — singleton tee sink so live recordings tap ExoPlayer's
        // existing HTTP traffic instead of opening a second GET. One
        // instance is shared between the PlaybackController data-source
        // chain (writer side) and RecordingService (begin / end side).
        single { com.yancotv.android.recording.RecordingDataSink(logger = get()) }
        // Stage 5.2.2 — sideload auto-update plumbing. Endpoint URL
        // baked in at build time via BuildConfig.UPDATE_ENDPOINT (empty
        // when local.properties.update.endpoint is absent — checker
        // short-circuits to null in that case so dev builds without
        // configured updates are silent no-ops). versionCode lives on
        // BuildConfig too.
        single {
            com.yancotv.shared.update.UpdateChecker(
                http = get(),
                endpointUrl = com.yancotv.android.BuildConfig.UPDATE_ENDPOINT,
                currentVersionCode = com.yancotv.android.BuildConfig.VERSION_CODE,
                logger = get(),
            )
        }
        single {
            com.yancotv.android.update.UpdateRepository(
                checker = get(),
                prefs = get(),
            )
        }
        // Stage 5.2.3 — APK download + install controller. Owns its own
        // CoroutineScope (SupervisorJob + IO); the Compose UI collects
        // its StateFlow and dispatches actions. Singleton because
        // download + install state must persist across composition
        // (Settings tab can be left and re-entered while a download is
        // still running).
        single {
            com.yancotv.android.update.UpdateInstaller(
                appContext = androidContext(),
                sharedHttp = get(),
                logger = get(),
            )
        }
        single {
            PlaybackController(
                context = androidContext(),
                prefs = get(),
                history = get(),
                recordingSink = get(),
                sources = get(),
            )
        }
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
                sharedHttp = get(),
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
        // MK.19.8.3 + 19.8.4 — backup/restore coordinator. Bridges SAF
        // picker results to the pure BackupExporter / BackupImporter
        // engine. Subscribes to SourceSyncCoordinator state so buffered
        // re-link records drain when each source's catalog finishes
        // syncing post-restore.
        single {
            com.yancotv.android.backup.BackupCoordinator(
                context = androidContext(),
                db = get(),
                credentialStore = get(),
                syncCoordinator = get(),
            )
        }
        // Stage 3.1 / MK.14.1c — recording state-machine repo. Used by
        // RecordingService and (eventually) the Recordings screen.
        //
        // MB-219 — `fileBytesIfExists` is the boot-recovery hook for
        // `sweepOrphans`. When the service's `handleStop` is interrupted
        // by process death between `cancelAndJoin` and `markCompleted`,
        // the file is on disk but the row's status never flipped. On
        // the next boot, sweep probes the file via this resolver; if
        // bytes are present the row lands as COMPLETED instead of the
        // default FAILED("orphaned_by_app_kill"), so a fully-recorded
        // file isn't lost behind a "Failed" badge.
        single {
            com.yancotv.shared.recording.RecordingsRepository(
                db = get(),
                clock = { System.currentTimeMillis() },
                fileBytesIfExists = com.yancotv.android.recording.recordingFileBytesResolver(androidContext()),
            )
        }
        // MK.14.3 — scheduled-recording state machine. Owned by
        // RecordingScheduleScheduler (alarm pairing, slice 2) and
        // RecordingScheduleReceiver (fire-time transitions).
        single {
            com.yancotv.shared.recording.RecordingScheduleRepository(
                db = get(),
                clock = { System.currentTimeMillis() },
            )
        }
        // MK.14.3 — alarm wrapper + scheduler. Receiver pulls these
        // via KoinComponent.inject() at fire time.
        single { com.yancotv.android.recording.schedule.RecordingScheduleAlarmManager(androidContext()) }
        single {
            com.yancotv.android.recording.schedule.RecordingScheduleScheduler(
                context = androidContext(),
                repo = get(),
                alarmManager = get(),
            )
        }
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
        // MK.16.1 — theme controller. Singleton so every composable
        // in the app reads from the same StateFlow<ThemeId>. Pref
        // persistence (AppPreferences-backed) wires in MK.16.2.
        single {
            // MK.16.2 — restore last-picked theme from prefs at app start.
            val prefs = get<AppPreferences>()
            val controller = ThemeController()
            prefs.readThemeId()?.let { name ->
                controller.setTheme(com.yancotv.android.ui.theme.ThemeId.fromKey(name))
            }
            prefs.readAccentId()?.let { name ->
                controller.setAccent(com.yancotv.android.ui.theme.AccentId.fromKey(name))
            }
            controller
        }
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
