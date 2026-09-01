import CoreGraphics

/// Port of `player/PlayerChromeMetrics.kt`.
///
/// Every size in the dock is a **fraction of the container width**, not a
/// fixed dp. The Kotlin KDoc explains why: the design brief's numbers are
/// physical pixels at 1920x1080, and sizing off width rather than dp is
/// what makes them land correctly at any density.
///
/// The floors are described there as "an accessibility backstop for small
/// windows, **not part of the design**" — which matters here, because on a
/// phone every one of them binds. At 852pt (iPhone landscape) hero,
/// transport and secondary would all pin to 40/27/25 and the size
/// hierarchy that carries the dock's emphasis would flatten out. So the
/// compact floors below are raised proportionally, keeping the 1.48 : 1.08
/// : 1 ratio the ratios themselves produce. That is a deliberate
/// divergence, licensed by the comment saying the floors are not design.
enum PlayerChromeMetrics {

    struct Dock {
        let hero: CGFloat
        let transport: CGFloat
        let secondary: CGFloat
        let horizontalPadding: CGFloat
        let gap: CGFloat
        let verticalPadding: CGFloat
        let titleFont: CGFloat
    }

    static func dock(width: CGFloat, compact: Bool) -> Dock {
        // Raised floors on compact — see the type comment.
        let heroFloor: CGFloat = compact ? 54 : 40
        let transportFloor: CGFloat = compact ? 39 : 27
        let secondaryFloor: CGFloat = compact ? 36 : 25

        return Dock(
            hero: max(width * 0.0432, heroFloor),
            transport: max(width * 0.0292, transportFloor),
            secondary: max(width * 0.0271, secondaryFloor),
            horizontalPadding: max(width * 0.0146, 10),
            gap: max(width * 0.0078, 5),
            verticalPadding: max(width * 0.0057, 4),
            // The title clamp stays as-is; 15pt is the design ceiling.
            titleFont: min(max(width * 0.017, compact ? 13 : 10), 15)
        )
    }

    /// Width of an elongated word chip (CC / AUDIO / SPEED / FIT).
    ///
    /// `((label.length * 5) + size * 0.6).coerceAtLeast(size)` — a regular
    /// hexagon's flat top is only half its width, so one fixed width would
    /// either clip the long labels or leave the short ones swimming.
    static func chipWidth(label: String, secondary: CGFloat) -> CGFloat {
        max(CGFloat(label.count) * 5 + secondary * 0.6, secondary)
    }
}
