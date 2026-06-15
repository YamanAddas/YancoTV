package com.yancotv.android.handoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.MainActivity
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.shared.handoff.HandoffOutcome
import com.yancotv.shared.handoff.HandoffPlayCommand
import com.yancotv.shared.handoff.HandoffReject
import com.yancotv.shared.handoff.resolveHandoffCommand
import com.yancotv.shared.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * MK.26.A.1 — foreground service hosting the LAN companion-handoff receiver.
 *
 * Owns a [HandoffServer] (embedded Ktor CIO) and routes every accepted command
 * to the single shared [PlaybackController]. The controller is main-thread-only
 * (native CLAUDE.md hard rule 2), so the Ktor request coroutine hops to
 * [Dispatchers.Main] before touching it — the controller dispatches its own IO
 * (resume lookup, source-override resolve) internally from there.
 *
 * One-player rule holds by construction: this never instantiates a player, it
 * calls `play(...)` on the existing singleton.
 *
 * Scope notes for A.1: discovery/pairing is A.2 (this just listens on a fixed
 * port), the pairing token is a build-time stub ([TOKEN_STUB]) until A.4 wires
 * real per-pairing tokens, and the sender's exact resume position is carried in
 * the outcome but not yet seeked-to (A.3 owns position fidelity). The
 * `mediaPlayback` foreground type's Android-14+ runtime requirements are a
 * de-risk item for A.5; the canonical Fire TV test target (AFTDCT31) is API 28,
 * where the type attribute is not runtime-enforced.
 */
@UnstableApi
class HandoffReceiverService : Service() {
    private val controller: PlaybackController by inject()
    private val logger: Logger by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val server: HandoffServer by lazy {
        HandoffServer(
            port = HandoffServer.DEFAULT_PORT,
            logger = logger,
            onPlay = ::handlePlay,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START or a sticky restart (null intent): become foreground and
        // ensure the listener is up. Both calls are idempotent.
        startForegroundIfNeeded()
        runCatching { server.start() }
            .onFailure { logger.error("Handoff: failed to start receiver — ${it.message}") }
        advertise()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
        runCatching { server.stop() }
        serviceScope.cancel()
    }

    /** Advertise this receiver on the LAN via NSD so phones can auto-find it (A.2). */
    private fun advertise() {
        if (registrationListener != null) return
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val info =
            NsdServiceInfo().apply {
                serviceName = "YancoTV (${Build.MODEL})"
                serviceType = HandoffDiscovery.SERVICE_TYPE
                port = HandoffServer.DEFAULT_PORT
            }
        val l =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registered: NsdServiceInfo) {
                    logger.info("Handoff: advertised as ${registered.serviceName}")
                }

                override fun onRegistrationFailed(failed: NsdServiceInfo, errorCode: Int) {
                    logger.warn("Handoff: advertise failed: $errorCode")
                }

                override fun onServiceUnregistered(unregistered: NsdServiceInfo) {}

                override fun onUnregistrationFailed(failed: NsdServiceInfo, errorCode: Int) {}
            }
        registrationListener = l
        nsdManager = nsd
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { logger.warn("Handoff: registerService failed: ${it.message}") }
    }

    private fun stopAdvertising() {
        registrationListener?.let { active -> runCatching { nsdManager?.unregisterService(active) } }
        registrationListener = null
    }

    /**
     * Validate + dispatch one command. Returns null on success or a
     * [HandoffReject] reason (mapped to an HTTP status by [HandoffServer]).
     * Runs on a Ktor request coroutine; the controller call hops to main.
     */
    private suspend fun handlePlay(command: HandoffPlayCommand): HandoffReject? =
        when (val outcome = resolveHandoffCommand(command, expectedToken = TOKEN_STUB)) {
            is HandoffOutcome.Rejected -> {
                logger.warn("Handoff: rejected command — ${outcome.reason}")
                outcome.reason
            }

            is HandoffOutcome.PlayContent -> {
                withContext(Dispatchers.Main) {
                    controller.play(listOf(outcome.item), 0, outcome.fromStart)
                }
                logger.info("Handoff: playing ${outcome.item.id}")
                null
            }

            is HandoffOutcome.PlayEpisode -> {
                withContext(Dispatchers.Main) {
                    controller.play(outcome.episode, outcome.fromStart)
                }
                logger.info("Handoff: playing episode ${outcome.episode.id}")
                null
            }
        }

    // ── Notification / foreground ─────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.handoff_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.handoff_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.handoff_notification_title))
            .setContentText(getString(R.string.handoff_notification_body))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yanco_handoff"
        private const val NOTIFICATION_ID = 9101

        const val ACTION_START = "com.yancotv.android.handoff.START"
        const val ACTION_STOP = "com.yancotv.android.handoff.STOP"

        /**
         * Placeholder pairing token until MK.26.A.4 establishes a real
         * per-pairing secret. The sender must echo this in
         * [HandoffPlayCommand.pairingToken]; it is NOT a security boundary
         * yet — A.4 replaces it with a paired token + LAN-only binding.
         */
        const val TOKEN_STUB = "yanco-dev"

        fun start(context: Context) {
            val intent = Intent(context, HandoffReceiverService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HandoffReceiverService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
