import AVFoundation
import Foundation
import SwiftUI

/// Owns playback — the iOS counterpart to Android's `PlaybackController`.
///
/// The Android hard rule is "one ExoPlayer, owned by the controller,
/// never constructed elsewhere". Same rule here, one level up: the
/// controller owns exactly one **engine** at a time and swaps it when a
/// stream needs a different one. Views never touch an engine directly.
///
/// ### Engine selection and fallback
///
/// `EngineRouter` guesses from the URL, and a format failure promotes to
/// VLC and retries once. This mirrors what the rest of the category does
/// — engine chains rather than a single player — because no one engine
/// covers what IPTV providers actually serve.
///
/// The retry is deliberately narrow: only an *unsupported format* falls
/// back. Retrying a 403 or a timeout on a second engine just fails twice
/// as slowly and buries the real cause.
///
/// ### Resume-point discipline
///
/// Carried over from `native-android-mk`, because the failure mode is
/// identical on either platform: **every transition that loads a new item
/// persists the outgoing position first**. Lifecycle hooks alone miss the
/// zap-through-player and queue-replace cases, so `load` and `teardown`
/// both flush before doing anything else.
@MainActor
@Observable
final class PlaybackController {

    private(set) var item: YancoItem?
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0
    private(set) var bufferedTime: Double = 0
    private(set) var errorMessage: String?
    /// Which engine is driving playback. Surfaced in the dock's
    /// diagnostics so a support question has an answer.
    private(set) var engineKind: EngineKind = .avPlayer

    var isLive: Bool { item?.kind == .live }

    @ObservationIgnored private(set) var engine: (any PlaybackEngine)?
    @ObservationIgnored private var library: LibraryStore?
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var triedVLC = false
    @ObservationIgnored private var pendingResume: Double = 0
    @ObservationIgnored private var aspectFill = false

    private static let userAgent = "YancoTV/1.0 (iOS)"

    func attach(library: LibraryStore) {
        self.library = library
    }

    // MARK: - Loading

    func load(_ item: YancoItem) async {
        flushPosition()
        teardownEngine()

        guard let raw = item.streamURL, let url = URL(string: raw) else {
            self.item = item
            errorMessage = "This title has no playable stream."
            return
        }

        self.item = item
        errorMessage = nil
        isBuffering = true
        currentTime = 0
        duration = 0
        bufferedTime = 0
        triedVLC = false

        configureAudioSession()
        pendingResume = item.kind == .live ? 0 : (await library?.resumePosition(for: item.id) ?? 0)

        start(engine: EngineRouter.preferredEngine(for: url), url: url)
    }

    private func start(engine kind: EngineKind, url: URL) {
        let engine: any PlaybackEngine
        switch kind {
        case .vlc where vlcEngineAvailable:
            #if canImport(VLCKitSPM)
            engine = VLCPlaybackEngine()
            #else
            engine = AVPlaybackEngine()
            #endif
        default:
            engine = AVPlaybackEngine()
        }

        engine.delegate = self
        engine.setAspectFill(aspectFill)
        self.engine = engine
        engineKind = engine.kind
        if engine.kind == .vlc { triedVLC = true }

        engine.load(url: url, userAgent: Self.userAgent, startAt: pendingResume)
        engine.play()
        isPlaying = true
        startTicker()
    }

    // MARK: - Transport

    func togglePlayPause() {
        guard let engine else { return }
        if isPlaying {
            engine.pause()
            // A pause is a natural commit point and often the last thing
            // that happens before the app is suspended.
            flushPosition()
        } else {
            engine.play()
        }
        isPlaying.toggle()
    }

    func seek(to seconds: Double) {
        guard !isLive, duration > 0, let engine else { return }
        let clamped = max(0, min(seconds, duration))
        currentTime = clamped
        engine.seek(to: clamped)
    }

    func skip(_ delta: Double) { seek(to: currentTime + delta) }

    func setAspectFill(_ fill: Bool) {
        aspectFill = fill
        engine?.setAspectFill(fill)
    }

    func teardown() {
        flushPosition()
        teardownEngine()
        isPlaying = false
        item = nil
        errorMessage = nil
        try? AVAudioSession.sharedInstance()
            .setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Internals

    private func teardownEngine() {
        ticker?.cancel()
        ticker = nil
        engine?.delegate = nil
        engine?.teardown()
        engine = nil
    }

    private func configureAudioSession() {
        // `.playback` keeps audio going with the ring switch silenced and
        // is the category background playback and PiP require.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
    }

    /// 2Hz, matching the Android dock's progress ticker. Polling rather
    /// than per-engine time callbacks so both engines report identically.
    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, let engine = self.engine else { return }
                self.currentTime = engine.currentTime
                self.bufferedTime = engine.bufferedTime
                if self.duration == 0, engine.duration > 0 {
                    self.duration = engine.duration
                }
            }
        }
    }

    private func flushPosition() {
        guard let item, currentTime > 0 else { return }
        library?.savePosition(
            for: item,
            seconds: currentTime,
            duration: duration > 0 ? duration : nil
        )
    }
}

extension PlaybackController: PlaybackEngineDelegate {
    func engineDidBecomeReady(duration: Double) {
        isBuffering = false
        if duration > 0 { self.duration = duration }
    }

    func engineBufferingChanged(_ buffering: Bool) {
        isBuffering = buffering
    }

    func engineDidFail(message: String, formatUnsupported: Bool) {
        // The one case worth a second attempt: AVFoundation could not open
        // the container. Promote to VLC and retry, once.
        if formatUnsupported,
           !triedVLC,
           vlcEngineAvailable,
           let raw = item?.streamURL,
           let url = URL(string: raw) {
            teardownEngine()
            isBuffering = true
            start(engine: .vlc, url: url)
            return
        }

        isBuffering = false
        errorMessage = formatUnsupported && !vlcEngineAvailable
            ? "This stream's format needs the VLC engine, which isn't available in this build."
            : message
    }
}
