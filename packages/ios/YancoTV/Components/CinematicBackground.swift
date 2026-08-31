import SwiftUI

/// Port of `ui/components/CinematicBackground.kt`.
///
/// Ambient app-wide background — a radial wash plus a subtle vertical
/// gradient so the shell doesn't read as a flat fill. The accent tint is
/// kept very low (5%): atmosphere, not brand noise.
///
/// The Compose original places the radial centre at `Offset(260, 220)`
/// with `radius = 1400` — those are raw pixels, and the Fire TV runs at
/// density 2, so in density-independent units the centre is (130, 110)
/// and the radius 700. Those are the numbers used here, since SwiftUI
/// works in points.
struct CinematicBackground: View {
    @Environment(\.yancoPalette) private var palette

    var body: some View {
        palette.BackgroundDeep
            .overlay {
                // The centre is an absolute point, so it is converted to a
                // UnitPoint against the real size rather than offsetting
                // the gradient view — offsetting moves the layer's own
                // bounds too, which left a visible horizontal seam where
                // the shifted layer stopped covering the canvas.
                GeometryReader { geo in
                    RadialGradient(
                        colors: [palette.Accent.opacity(0.05), palette.BackgroundDeep.opacity(0)],
                        center: UnitPoint(
                            x: 130 / max(geo.size.width, 1),
                            y: 110 / max(geo.size.height, 1)
                        ),
                        startRadius: 0,
                        endRadius: 700
                    )
                }
            }
            .overlay {
                LinearGradient(
                    colors: [palette.BackgroundDeep.opacity(0), palette.BackgroundDeep.opacity(0.35)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .ignoresSafeArea()
    }
}
