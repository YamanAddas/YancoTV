import AVFoundation
import Combine
import Foundation
import SwiftUI

/// AVPlayer wrapper — the iOS counterpart to Android's `PlaybackController`.
///
/// The Android hard rule is "one ExoPlayer, owned by the controller, never
/// constructed elsewhere". Same rule here: one `AVPlayer`, and the item is
/// swapped with `replaceCurrentItem` rather than building a second player.
///
/// ### Resume-point discipline
///
/// Carried over from `native-android-mk` because the failure mode is
/// identical on either platform: **every transition that loads a new item
/// persists the outgoing position first**. Lifecycle hooks alone miss the
/// zap-through-player and queue-replace cases, so [load] and [teardown]
/// both flush before they do anything else.
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

    /// Live streams have no meaningful duration or scrub target.
    var isLive: Bool { item?.kind == .live }

    let player = AVPlayer()

    private var library: LibraryStore?
    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var bufferObservation: NSKeyValueObservation?
    private var stallObservation: NSKeyValueObservation?

    // MARK: - Lifecycle

    func attach(library: LibraryStore) {
        self.library = library
    }

    /// Loads `item` and starts playback, seeking to its stored resume point.
    func load(_ item: YancoItem) async {
        // Persist the *outgoing* item before anything replaces it.
        flushPosition()

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

        configureAudioSession()

        let resume = await library?.resumePosition(for: item.id)

        let asset = AVURLAsset(
            url: url,
            options: [
                // Providers commonly gate playlists and streams on a
                // recognised player UA; the default Darwin one gets 403s.
                "AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": Self.userAgent]
            ]
        )
        let playerItem = AVPlayerItem(asset: asset)
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)

        if let resume, resume > 0, item.kind != .live {
            await player.seek(to: CMTime(seconds: resume, preferredTimescale: 600))
        }
        player.play()
        isPlaying = true
        startTimeObserver()
    }

    func togglePlayPause() {
        if isPlaying {
            player.pause()
            // A pause is a natural commit point, and on iOS it is often the
            // last thing that happens before the app is suspended.
            flushPosition()
        } else {
            player.play()
        }
        isPlaying.toggle()
    }

    func seek(to seconds: Double) {
        guard !isLive, duration > 0 else { return }
        let clamped = max(0, min(seconds, duration))
        currentTime = clamped
        player.seek(
            to: CMTime(seconds: clamped, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func skip(_ delta: Double) {
        seek(to: currentTime + delta)
    }

    /// Stops playback and releases observers. Flushes first — closing the
    /// player is exactly the transition a lifecycle hook would miss.
    func teardown() {
        flushPosition()
        player.pause()
        player.replaceCurrentItem(with: nil)
        stopTimeObserver()
        statusObservation = nil
        bufferObservation = nil
        stallObservation = nil
        isPlaying = false
        item = nil
        errorMessage = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Internals

    private static let userAgent = "YancoTV/1.0 (iOS)"

    private func configureAudioSession() {
        // `.playback` keeps audio going with the ring switch silenced and
        // is the category background playback and PiP require.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
    }

    private func observe(_ playerItem: AVPlayerItem) {
        statusObservation = playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    self.isBuffering = false
                    let seconds = item.duration.seconds
                    self.duration = seconds.isFinite && seconds > 0 ? seconds : 0
                case .failed:
                    self.isBuffering = false
                    self.errorMessage = Self.describe(item.error)
                default:
                    break
                }
            }
        }

        bufferObservation = playerItem.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let range = item.loadedTimeRanges.first?.timeRangeValue else { return }
                self?.bufferedTime = range.start.seconds + range.duration.seconds
            }
        }

        stallObservation = playerItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                self?.isBuffering = !item.isPlaybackLikelyToKeepUp
            }
        }
    }

    private func startTimeObserver() {
        stopTimeObserver()
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.currentTime = time.seconds
                if self.duration == 0, let d = self.player.currentItem?.duration.seconds,
                   d.isFinite, d > 0 {
                    self.duration = d
                }
            }
        }
    }

    private func stopTimeObserver() {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
    }

    private func flushPosition() {
        guard let item, currentTime > 0 else { return }
        library?.savePosition(
            for: item,
            seconds: currentTime,
            duration: duration > 0 ? duration : nil
        )
    }

    /// Turns AVFoundation's opaque failures into something a user can act
    /// on.
    ///
    /// The one worth naming explicitly is the format: AVPlayer handles HLS
    /// and progressive MP4 but **not raw MPEG-TS**, which a large share of
    /// live IPTV still serves. ExoPlayer does, which is why Android has no
    /// equivalent message. Saying "this stream format isn't supported yet"
    /// is honest; letting it read as a network error would send the user
    /// chasing the wrong problem. VLCKit closes this gap — see MK.iOS.3b.
    private static func describe(_ error: Error?) -> String {
        guard let error = error as NSError? else { return "Playback failed." }
        switch error.code {
        case -11828, -11829, -11800:
            return "This stream format isn't supported by iOS playback yet. "
                + "HLS (.m3u8) streams play; raw MPEG-TS needs the VLC engine."
        case -1009:
            return "No internet connection."
        case -1001:
            return "The provider timed out."
        case -1003, -1200:
            return "Couldn't reach the provider."
        default:
            return error.localizedDescription
        }
    }
}
