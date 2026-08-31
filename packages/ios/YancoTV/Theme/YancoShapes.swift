import SwiftUI

/// Port of `ui/theme/Shapes.kt` — the hex-inspired shape library.
///
/// The component language is cut-corner + elongated-hex geometry rather
/// than plain rounded rectangles. Every tile, chip and button pulls its
/// silhouette from here so the visual family stays coherent across
/// platforms.
///
/// Conventions (unchanged from the Kotlin source):
///   - `bevel` means a single straight cut instead of a rounded corner.
///   - "Hex capsule" cuts both left and right edges inwards — six sides,
///     wider than tall, for horizontal tiles where a literal hexagon
///     would crop poorly.
///   - "Cut-corner card" bevels two opposite corners and squares the
///     other two. Keeps posters readable while adding the angular
///     identity.
enum YancoShapes {
    /// Horizontal hex capsule — Live TV channel tiles, favourite rows.
    static let hexCapsule = HexCapsuleShape(cutFraction: 0.28)
    /// Long horizontal hex pill — category rail. Steeper side caps.
    static let hexPill = HexCapsuleShape(cutFraction: 0.5)
    /// Cut-corner poster — the Concept A signature (`hex-2cut`).
    static let cutCornerCard = CutCornerCardShape(cut: 22)
    /// Compact cards — home rail tiles, sidebar rows.
    static let cutCornerCardSmall = CutCornerCardShape(cut: 16)
    /// Hero panels and the live-TV preview pane.
    static let cutCornerCardLarge = CutCornerCardShape(cut: 32)
    /// Chip silhouette — angular leading edge, rounded trailing edge.
    static let chipBevel = ChipBevelShape()
    /// Button silhouette — slim horizontal hex, symmetrical bevels.
    static let buttonBevel = ButtonBevelShape()
    /// Pointy-top hexagon — transport buttons, small badges.
    static let pointyHex = PointyHexShape()
}

/// `hex-2cut` — top-right + bottom-left bevelled, the other two squared.
/// Design CSS polygon this ports:
/// `0 0, calc(100% - cut) 0, 100% cut, 100% 100%, cut 100%, 0 calc(100% - cut)`
struct CutCornerCardShape: Shape {
    var cut: CGFloat = 22

    func path(in rect: CGRect) -> Path {
        // Same clamp as the Kotlin original, so a small tile degrades to
        // the same silhouette rather than folding in on itself.
        let cap = min(rect.width, rect.height) * 0.4
        let c = min(cut, cap)
        var path = Path()
        path.move(to: CGPoint(x: 0, y: 0))
        path.addLine(to: CGPoint(x: rect.width - c, y: 0))
        path.addLine(to: CGPoint(x: rect.width, y: c))
        path.addLine(to: CGPoint(x: rect.width, y: rect.height))
        path.addLine(to: CGPoint(x: c, y: rect.height))
        path.addLine(to: CGPoint(x: 0, y: rect.height - c))
        path.closeSubpath()
        return path
    }
}

/// Elongated horizontal hex. `cutFraction` is how much of the height the
/// left/right bevels consume (0.28 ≈ 15°), clamped to 10…36pt so the
/// angle stays sane at both tiny and tall sizes.
struct HexCapsuleShape: Shape {
    var cutFraction: CGFloat = 0.28

    func path(in rect: CGRect) -> Path {
        let h = rect.height
        let w = rect.width
        let cut = min(max(h * cutFraction, 10), 36)
        let mid = h / 2
        var path = Path()
        path.move(to: CGPoint(x: cut, y: 0))
        path.addLine(to: CGPoint(x: w - cut, y: 0))
        path.addLine(to: CGPoint(x: w, y: mid))
        path.addLine(to: CGPoint(x: w - cut, y: h))
        path.addLine(to: CGPoint(x: cut, y: h))
        path.addLine(to: CGPoint(x: 0, y: mid))
        path.closeSubpath()
        return path
    }
}

/// Single angular cut on the leading edge, half-pill trailing edge.
/// Reads as part of the hex family without crowding short labels.
struct ChipBevelShape: Shape {
    func path(in rect: CGRect) -> Path {
        let h = rect.height
        let w = rect.width
        let cut = min(max(h * 0.42, 8), 16)
        let r = h / 2
        var path = Path()
        path.move(to: CGPoint(x: cut, y: 0))
        path.addLine(to: CGPoint(x: w - r, y: 0))
        path.addArc(
            center: CGPoint(x: w - r, y: r),
            radius: r,
            startAngle: .degrees(-90),
            endAngle: .degrees(90),
            clockwise: false
        )
        path.addLine(to: CGPoint(x: cut, y: h))
        path.addLine(to: CGPoint(x: 0, y: h / 2))
        path.closeSubpath()
        return path
    }
}

/// Symmetrical horizontal hex, slightly steeper than the tile so buttons
/// feel more pointed.
struct ButtonBevelShape: Shape {
    func path(in rect: CGRect) -> Path {
        let h = rect.height
        let w = rect.width
        let cut = min(max(h * 0.42, 10), 22)
        let mid = h / 2
        var path = Path()
        path.move(to: CGPoint(x: cut, y: 0))
        path.addLine(to: CGPoint(x: w - cut, y: 0))
        path.addLine(to: CGPoint(x: w, y: mid))
        path.addLine(to: CGPoint(x: w - cut, y: h))
        path.addLine(to: CGPoint(x: cut, y: h))
        path.addLine(to: CGPoint(x: 0, y: mid))
        path.closeSubpath()
        return path
    }
}

/// Pointy-top hexagon — vertices at top-center and bottom-center, flat
/// verticals at H/4 and 3H/4. Symmetric, so it reads identically at any
/// aspect ratio.
struct PointyHexShape: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width
        let h = rect.height
        var path = Path()
        path.move(to: CGPoint(x: w / 2, y: 0))
        path.addLine(to: CGPoint(x: w, y: h / 4))
        path.addLine(to: CGPoint(x: w, y: 3 * h / 4))
        path.addLine(to: CGPoint(x: w / 2, y: h))
        path.addLine(to: CGPoint(x: 0, y: 3 * h / 4))
        path.addLine(to: CGPoint(x: 0, y: h / 4))
        path.closeSubpath()
        return path
    }
}
