import SwiftUI
import UIKit

/// Hosts whichever engine is currently playing.
///
/// Each engine vends its own `UIView` — an `AVPlayerLayer`-backed view for
/// AVFoundation, a plain view libVLC renders into. This wrapper just
/// mounts it, so the player UI never knows which engine is behind the
/// picture.
///
/// `AVKit.VideoPlayer` would have been less code for the AVPlayer case,
/// but it brings Apple's own transport controls, which cannot be
/// restyled — and it has no answer at all for VLC.
struct EngineSurface: UIViewRepresentable {
    /// The identity of the engine, so SwiftUI rebuilds the representable
    /// when the controller swaps engines mid-stream (the format fallback).
    let engineID: ObjectIdentifier
    let makeSurface: () -> UIView

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        mount(makeSurface(), in: container)
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        let current = makeSurface()
        guard container.subviews.first !== current else { return }
        container.subviews.forEach { $0.removeFromSuperview() }
        mount(current, in: container)
    }

    private func mount(_ view: UIView, in container: UIView) {
        view.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            view.topAnchor.constraint(equalTo: container.topAnchor),
            view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
    }
}

/// Aspect modes the dock's FIT chip cycles.
enum VideoAspect: String, CaseIterable {
    case fit = "FIT"
    case fill = "FILL"

    var isFill: Bool { self == .fill }
    var next: VideoAspect { self == .fit ? .fill : .fit }
}
