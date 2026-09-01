import AVFoundation
import UIKit

/// AVFoundation engine — HLS and progressive MP4.
///
/// Preferred whenever it can play the stream: hardware decoding, the
/// lowest battery cost, and the only path to AirPlay and system PiP.
@MainActor
final class AVPlaybackEngine: NSObject, PlaybackEngine {

    let kind: EngineKind = .avPlayer
    let player = AVPlayer()
    weak var delegate: PlaybackEngineDelegate?

    private(set) var duration: Double = 0
    private(set) var bufferedTime: Double = 0

    var currentTime: Double { player.currentTime().seconds }

    lazy var surface: UIView = {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        return view
    }()

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var bufferObservation: NSKeyValueObservation?
    private var stallObservation: NSKeyValueObservation?
    private var pendingSeek: Double = 0

    func load(url: URL, userAgent: String, startAt: Double) {
        duration = 0
        bufferedTime = 0
        pendingSeek = startAt

        // Providers commonly gate streams on a recognised player UA; the
        // default Darwin one draws 403s.
        let asset = AVURLAsset(
            url: url,
            options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": userAgent]]
        )
        let item = AVPlayerItem(asset: asset)
        observe(item)
        player.replaceCurrentItem(with: item)
        startTimeObserver()
    }

    func play() { player.play() }

    @discardableResult
    func pause() -> Bool {
        player.pause()
        return true
    }

    func seek(to seconds: Double) {
        player.seek(
            to: CMTime(seconds: seconds, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func setAspectFill(_ fill: Bool) {
        (surface as? PlayerLayerView)?.playerLayer.videoGravity =
            fill ? .resizeAspectFill : .resizeAspect
    }

    func teardown() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        statusObservation = nil
        bufferObservation = nil
        stallObservation = nil
    }

    // MARK: - Observation

    private func observe(_ item: AVPlayerItem) {
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    let seconds = item.duration.seconds
                    self.duration = seconds.isFinite && seconds > 0 ? seconds : 0
                    if self.pendingSeek > 0 {
                        self.seek(to: self.pendingSeek)
                        self.pendingSeek = 0
                    }
                    self.delegate?.engineDidBecomeReady(duration: self.duration)
                case .failed:
                    let error = item.error as NSError?
                    self.delegate?.engineDidFail(
                        message: Self.describe(error),
                        formatUnsupported: Self.isFormatFailure(error)
                    )
                default:
                    break
                }
            }
        }

        bufferObservation = item.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let range = item.loadedTimeRanges.first?.timeRangeValue else { return }
                self?.bufferedTime = range.start.seconds + range.duration.seconds
            }
        }

        stallObservation = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                self?.delegate?.engineBufferingChanged(!item.isPlaybackLikelyToKeepUp)
            }
        }
    }

    private func startTimeObserver() {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.duration == 0,
                      let d = self.player.currentItem?.duration.seconds,
                      d.isFinite, d > 0
                else { return }
                self.duration = d
            }
        }
    }

    // MARK: - Errors

    /// True when the failure is "AVFoundation can't open this container",
    /// which is the cue to hand the stream to VLC rather than to show the
    /// user an error.
    ///
    /// These are the media-services codes AVFoundation reports for an
    /// undecodable or unrecognised container. Network and auth failures
    /// are deliberately excluded: retrying those on another engine just
    /// fails twice as slowly.
    private static func isFormatFailure(_ error: NSError?) -> Bool {
        guard let error else { return true }
        switch error.code {
        case -11828, // cannot open
             -11829, // unsupported media type
             -11800, // unknown / generic AVFoundation failure
             -12939, // byte-range requests unsupported by the origin
             -11850: // operation not supported for this media
            return true
        default:
            return false
        }
    }

    private static func describe(_ error: NSError?) -> String {
        guard let error else { return "Playback failed." }
        switch error.code {
        case -1009: return "No internet connection."
        case -1001: return "The provider timed out."
        case -1003, -1200: return "Couldn't reach the provider."
        default: return error.localizedDescription
        }
    }
}

/// A UIView whose backing layer *is* the player layer, so it resizes with
/// the view instead of needing manual frame bookkeeping.
final class PlayerLayerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
