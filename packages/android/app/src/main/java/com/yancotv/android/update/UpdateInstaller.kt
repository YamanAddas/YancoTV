package com.yancotv.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Stage 5.2.3 — sideload auto-update download + install controller.
 *
 * Drives the in-app "Update now" flow on top of the [UpdateInfo]
 * surfaced by [UpdateRepository] (which is itself fed by the
 * [UpdateCheckWorker]). Owns:
 *   1. APK download into the app-specific external files dir under
 *      `updates/` (no WRITE_EXTERNAL_STORAGE needed, FileProvider can
 *      hand it to the system installer as a content:// URI).
 *   2. A [StateFlow] of the current download/install state so the
 *      About-tab UI can render progress, "ready to install", or an
 *      error inline.
 *   3. The system install hand-off via [Intent.ACTION_VIEW] +
 *      `application/vnd.android.package-archive`. On Android 8+ we
 *      first check [android.content.pm.PackageManager.canRequestPackageInstalls]
 *      and, if false, route the user to the system "install unknown
 *      apps" settings screen so they can grant the one-time consent.
 *
 * Pure state — no UI imports. Compose surfaces collect [state] and
 * dispatch the public actions.
 *
 * Threading: the download runs on [Dispatchers.IO]; state mutations
 * happen via the underlying [MutableStateFlow] (lock-free). Install /
 * settings hand-offs hit `context.startActivity(...)`, which is safe
 * from any thread (it goes through the system process).
 *
 * Singleton — bound in [com.yancotv.android.di.appModule]. The download
 * uses the same shared [OkHttpClient] as Coil + EPG sync (MK.24.I.7 /
 * MB-230 consolidation) so we don't open another connection pool /
 * dispatcher for what's effectively a one-shot fetch.
 */
class UpdateInstaller(
    private val appContext: Context,
    private val sharedHttp: OkHttpClient,
    private val logger: Logger,
) {
    sealed interface State {
        data object Idle : State

        data class Downloading(
            val percent: Int,
            val versionName: String,
        ) : State

        data class ReadyToInstall(
            val apkFile: File,
            val versionName: String,
        ) : State

        data class Failed(
            val reason: String,
            val versionName: String,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private var activeDownload: Job? = null

    /**
     * Kick off an APK download for [info]. Idempotent on overlap — if a
     * download is already in flight, the call returns immediately and
     * the state continues advancing on the existing job. To re-download
     * (e.g. after a [State.Failed]), call [reset] first then [download].
     *
     * The downloaded APK lands at
     * `getExternalFilesDir(null)/updates/yancotv-<versionCode>.apk`.
     * If a previous download for the same versionCode already exists
     * we skip the network and jump straight to [State.ReadyToInstall].
     */
    fun download(info: UpdateInfo) {
        if (activeDownload?.isActive == true) {
            logger.info("UpdateInstaller: download already in flight, ignoring duplicate request")
            return
        }
        activeDownload =
            scope.launch {
                downloadMutex.withLock {
                    runDownload(info)
                }
            }
    }

    /** Cancel an in-flight download and revert to [State.Idle]. */
    fun cancel() {
        activeDownload?.cancel()
        activeDownload = null
        _state.value = State.Idle
    }

    /** Drop terminal state ([State.Failed] / [State.ReadyToInstall]) so a fresh download can start. */
    fun reset() {
        cancel()
    }

    /**
     * Hand the downloaded APK off to the system PackageInstaller. On
     * Android 8+ the user must have granted "install unknown apps" for
     * YancoTV first; if not, we route them to the system settings page
     * so they can grant it (then return to the app and tap Install
     * again — Android does NOT bring us back automatically).
     *
     * Returns true if the install intent was launched, false if the
     * permission gate redirected to system settings instead. UI uses
     * the return value to flip a hint label ("Grant permission, then
     * tap Install again").
     */
    fun launchInstall(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            logger.warn("UpdateInstaller: launchInstall called but APK is gone — resetting")
            _state.value = State.Idle
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            openInstallUnknownAppsSettings()
            return false
        }
        val authority = "${appContext.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(appContext, authority, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { e ->
                logger.warn("UpdateInstaller: install intent failed — ${e.message}")
            }
        return true
    }

    /**
     * Route the user to the system "Install unknown apps" settings
     * screen for YancoTV. Used when [launchInstall] discovers the
     * permission isn't granted yet, AND exposed publicly for the UI's
     * "Open install settings" affordance so the user can grant it
     * pre-emptively.
     */
    fun openInstallUnknownAppsSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { e ->
                logger.warn("UpdateInstaller: settings intent failed — ${e.message}")
            }
    }

    private suspend fun runDownload(info: UpdateInfo) {
        val targetFile = apkFileFor(info.versionCode)
        if (targetFile.exists() && targetFile.length() > 0L) {
            // Same versionCode already downloaded — skip the round trip
            // and jump to ready. The user could still tap Install.
            _state.value = State.ReadyToInstall(targetFile, info.versionName)
            return
        }
        targetFile.parentFile?.mkdirs()
        _state.value = State.Downloading(percent = 0, versionName = info.versionName)
        val partial = File(targetFile.parentFile, targetFile.name + ".part")
        partial.delete()

        val outcome =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(info.downloadUrl).build()
                    sharedHttp.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("empty body")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var downloaded = 0L
                                var lastReportedPercent = -1
                                while (true) {
                                    if (!isActive) error("cancelled")
                                    val read = input.read(buf)
                                    if (read == -1) break
                                    output.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0L) {
                                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                        // Throttle UI updates — only emit on whole-percent flips
                                        // so the StateFlow doesn't churn for every 64 KB chunk.
                                        if (pct != lastReportedPercent) {
                                            _state.value = State.Downloading(pct, info.versionName)
                                            lastReportedPercent = pct
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!partial.renameTo(targetFile)) error("rename failed")
                }
            }
        outcome.fold(
            onSuccess = {
                _state.value = State.ReadyToInstall(targetFile, info.versionName)
            },
            onFailure = { e ->
                partial.delete()
                logger.warn("UpdateInstaller: download failed — ${e.message}")
                _state.value =
                    State.Failed(
                        reason = e.message ?: "download failed",
                        versionName = info.versionName,
                    )
            },
        )
    }

    private fun apkFileFor(versionCode: Int): File {
        val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val updatesDir = File(baseDir, "updates")
        return File(updatesDir, "yancotv-$versionCode.apk")
    }
}
