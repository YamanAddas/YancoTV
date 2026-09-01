import AVFoundation
import AVKit
import SwiftUI

/// The video surface — an `AVPlayerLayer` in a `UIViewRepresentable`.
///
/// `VideoPlayer` from AVKit would be less code but brings Apple's own
/// transport controls, which cannot be restyled. The whole point of this
/// milestone is the Yanco dock, so the layer is hosted directly and the
/// chrome is drawn over it.
///
/// The layer is also what Picture-in-Picture attaches to.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = gravity
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
        view.playerLayer.videoGravity = gravity
    }
}

/// A UIView whose backing layer *is* the player layer, so it resizes with
/// the view rather than needing manual frame bookkeeping.
final class PlayerLayerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer {
        // Safe by construction: `layerClass` above guarantees the type.
        layer as! AVPlayerLayer
    }
}

/// Aspect-ratio modes the user can cycle, matching the dock's ASPECT chip.
enum VideoAspect: String, CaseIterable {
    case fit = "FIT"
    case fill = "FILL"

    var gravity: AVLayerVideoGravity {
        switch self {
        case .fit: return .resizeAspect
        case .fill: return .resizeAspectFill
        }
    }

    var next: VideoAspect { self == .fit ? .fill : .fit }
}
