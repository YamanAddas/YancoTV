import UIKit

#if canImport(VLCKitSPM)
import VLCKitSPM

/// libVLC engine — everything AVFoundation cannot demux.
///
/// Above all raw **MPEG-TS**, which is still how a large share of live
/// IPTV is delivered and which AVFoundation does not open at all. Also
/// MKV, AVI, and the `rtsp://` / `rtmp://` schemes some providers use.
///
/// This is the same answer every serious iOS IPTV player reaches: bundle
/// a software decoder and route to it when the system player can't cope.
///
/// ### Licensing
///
/// LGPLv2.1-or-later, linked dynamically as a binary framework. VideoLAN
/// relicensed libVLC from GPL expressly so App Store distribution would
/// be possible. The obligations that come with it — attribution, notice
/// to the user, and an offer of source — are met by
/// `docs/LICENSES.md` and the Settings acknowledgements entry, and they
/// must ship *with the app*, not just live in the repo.
@MainActor
final class VLCPlaybackEngine: NSObject, PlaybackEngine {

    let kind: EngineKind = .vlc
    weak var delegate: PlaybackEngineDelegate?

    private let player = VLCMediaPlayer()
    private let videoView = UIView()

    private(set) var duration: Double = 0
    /// libVLC exposes no loaded-range API, so the ribbon's buffered layer
    /// simply tracks the play head here rather than inventing a number.
    var bufferedTime: Double { currentTime }

    var currentTime: Double {
        Double(player.time.intValue) / 1000
    }

    var surface: UIView { videoView }

    override init() {
        super.init()
        videoView.backgroundColor = .black
        player.drawable = videoView
        player.delegate = self
    }

    func load(url: URL, userAgent: String, startAt: Double) {
        duration = 0

        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(userAgent)")
        // Live IPTV over a home connection needs a real jitter buffer;
        // libVLC's 300ms default stutters constantly on it. One second is
        // the usual IPTV-player figure — enough to ride out jitter without
        // a channel change feeling sluggish.
        media.addOption(":network-caching=1000")
        // Providers redirect constantly (load balancers, token refresh).
        media.addOption(":http-reconnect")

        if startAt > 0 {
            media.addOption(":start-time=\(Int(startAt))")
        }

        player.media = media
    }

    func play() { player.play() }

    @discardableResult
    func pause() -> Bool {
        // `pause()` on libVLC is a toggle, which double-fires if the
        // caller already tracks state — and a live feed with no buffer
        // behind it reports `canPause == false` and simply keeps going.
        // Reporting that back stops the dock claiming it paused.
        guard player.canPause else { return false }
        player.pause()
        return true
    }

    func seek(to seconds: Double) {
        guard duration > 0 else { return }
        player.time = VLCTime(int: Int32(seconds * 1000))
    }

    func setAspectFill(_ fill: Bool) {
        // libVLC scales to fit by default (`scaleFactor = 0` = auto). It
        // has no direct "aspect fill"; the nearest equivalent is a crop
        // geometry, which needs the view's ratio and is a separate piece
        // of work. Fit-only here, deliberately, rather than a broken
        // toggle — see MK.iOS.3b notes.
        player.scaleFactor = 0
    }

    func teardown() {
        player.stop()
        player.delegate = nil
        player.drawable = nil
    }
}

extension VLCPlaybackEngine: VLCMediaPlayerDelegate {
    /// libVLC calls its delegate from its own threads.
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch self.player.state {
            case .buffering, .opening:
                self.delegate?.engineBufferingChanged(true)
            case .playing:
                self.delegate?.engineBufferingChanged(false)
                let length = Double(self.player.media?.length.intValue ?? 0) / 1000
                // Live has no length; leave duration at 0 so the dock
                // renders an empty track rather than a bogus one (MB-340).
                self.duration = length > 0 ? length : 0
                self.delegate?.engineDidBecomeReady(duration: self.duration)
            case .error:
                // VLC is the last engine tried, so nothing is unsupported
                // *past* here — this is terminal and the user sees it.
                self.delegate?.engineDidFail(
                    message: "The provider rejected this stream, or it is offline.",
                    formatUnsupported: false
                )
            default:
                break
            }
        }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in
            guard let self, self.duration == 0 else { return }
            let length = Double(self.player.media?.length.intValue ?? 0) / 1000
            if length > 0 {
                self.duration = length
                self.delegate?.engineDidBecomeReady(duration: length)
            }
        }
    }
}

/// True when VLC is linked into this build.
let vlcEngineAvailable = true

#else

/// VLCKit is not linked in this build.
///
/// The package is declared in `project.yml`, so this branch should never
/// compile in a normal checkout — it exists so a resolution failure (an
/// offline machine, a CI runner without the ~200MB artifact cached)
/// degrades to an AVPlayer-only app that still builds, instead of a red
/// project. `PlaybackController` reports the format honestly in that case
/// rather than silently failing.
let vlcEngineAvailable = false

#endif
