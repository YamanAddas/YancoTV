import Foundation
import UIKit

/// The playback abstraction.
///
/// Desktop has the same rule — *"Don't couple desktop code to mpv
/// directly (use `IPlayer`)"* — for the same reason: YancoTV needs more
/// than one engine, because no single one plays everything an IPTV
/// provider serves.
///
/// - **AVPlayer** handles HLS and progressive MP4 with hardware decoding
///   and the lowest battery cost, and is the only engine that can drive
///   AirPlay and system PiP.
/// - **VLCKit** handles everything else — raw MPEG-TS above all, which is
///   still a large share of live IPTV and which AVFoundation simply does
///   not demux.
///
/// `PlaybackController` picks one per stream and falls back on failure.
@MainActor
protocol PlaybackEngine: AnyObject {
    var kind: EngineKind { get }

    /// The engine's video output. Hosted by `EngineSurface`.
    var surface: UIView { get }

    var currentTime: Double { get }
    var duration: Double { get }
    var bufferedTime: Double { get }

    var delegate: PlaybackEngineDelegate? { get set }

    func load(url: URL, userAgent: String, startAt: Double)
    func play()
    /// Returns whether the stream actually paused. A live feed with no
    /// buffer behind it cannot, and the caller must not flip its own
    /// playing state when it didn't.
    @discardableResult
    func pause() -> Bool
    func seek(to seconds: Double)
    func setAspectFill(_ fill: Bool)
    func teardown()
}

enum EngineKind: String {
    case avPlayer = "AVPlayer"
    case vlc = "VLC"
}

@MainActor
protocol PlaybackEngineDelegate: AnyObject {
    func engineDidBecomeReady(duration: Double)
    func engineBufferingChanged(_ buffering: Bool)
    /// `formatUnsupported` is the signal that another engine should be
    /// tried; anything else is terminal and shown to the user.
    func engineDidFail(message: String, formatUnsupported: Bool)
}

/// Which engine to try first for a given URL.
///
/// A guess, not a guarantee — providers mislabel constantly, and an
/// extensionless URL says nothing at all. Being wrong is cheap because
/// `PlaybackController` falls back; being right avoids spinning up the
/// software decoder for a stream AVPlayer would have hardware-decoded.
enum EngineRouter {
    /// Container extensions AVFoundation demuxes natively.
    private static let avPlayerFormats: Set<String> = [
        "m3u8", "mp4", "m4v", "mov", "m4a", "mp3", "aac",
    ]

    /// Containers AVFoundation cannot open, whatever the codec inside.
    private static let softwareOnlyFormats: Set<String> = [
        "ts", "mpegts", "mkv", "avi", "flv", "wmv", "asf", "vob", "mpg", "mpeg", "ogv", "rm",
    ]

    static func preferredEngine(for url: URL) -> EngineKind {
        #if DEBUG
        // Forces an engine regardless of the URL, so either can be
        // exercised against a stream known to work:
        //   SIMCTL_CHILD_YANCO_DEBUG_ENGINE=vlc  xcrun simctl launch …
        if let forced = ProcessInfo.processInfo.environment["YANCO_DEBUG_ENGINE"],
           let kind = EngineKind(rawValue: forced == "vlc" ? "VLC" : "AVPlayer") {
            return kind
        }
        #endif

        // rtsp/rtmp/udp/rtp are never AVFoundation's.
        if let scheme = url.scheme?.lowercased(), scheme != "http", scheme != "https", scheme != "file" {
            return .vlc
        }
        let ext = url.pathExtension.lowercased()
        if avPlayerFormats.contains(ext) { return .avPlayer }
        if softwareOnlyFormats.contains(ext) { return .vlc }
        // Extensionless is the common Xtream live shape
        // (`/live/user/pass/12345`), which is usually MPEG-TS. Guessing VLC
        // costs a software decode; guessing AVPlayer costs a failed load,
        // a visible error and a re-buffer, so the cheaper mistake wins.
        return .vlc
    }
}
