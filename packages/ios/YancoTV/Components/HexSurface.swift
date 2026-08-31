import SwiftUI

/// Port of `ui/components/HexSurface.kt`.
///
/// Layered hex-inspired surface. Every tile, chip and button in the shell
/// pipes through this primitive so the visual language stays coherent — a
/// shell frame clipped to `shape` with an inner content layer inset by
/// `bevelInset`, producing a bevelled "frame around the content" read
/// instead of a single flat border.
///
/// Depth recipe (unchanged from Android):
///   - outer shell: subtle top-down gradient, tinted accent shadow when lit
///   - inner panel: flat fill (accent wash when lit) reading as recessed
///   - inner rim: 1pt accent stroke when lit, giving the bevel a lit edge
///   - specular top facet: a single hairline of warm white tracing the top
///     edge so the hex reads as machined metal under light
///
/// ### `lit` replaces `focused`
///
/// On TV this state is D-pad focus. There is no focus on a touch screen,
/// so the same three visual signals (scale lift, tinted wash, accent rim)
/// are driven by selection or press instead. The parameter is renamed to
/// `lit` because "focused" would be a lie on iOS — the treatment and its
/// numbers are otherwise identical.
struct HexSurface<S: Shape, Content: View>: View {
    @Environment(\.yancoPalette) private var palette

    let shape: S
    var lit: Bool = false
    var bevelInset: CGFloat = 0
    var liftScale: CGFloat = 1.06
    var lift: CGFloat = 10
    var raised: Bool = true
    @ViewBuilder var content: () -> Content

    /// Compose used `spring(dampingRatio = 0.75, stiffness = 420)`.
    /// response = 2π / √stiffness ≈ 0.31s at the same damping fraction.
    private var springAnimation: Animation { .spring(response: 0.31, dampingFraction: 0.75) }

    private var shellGradient: LinearGradient {
        LinearGradient(
            colors: lit
                ? [palette.Accent, palette.AccentDeep]
                : [palette.BackgroundElevated.opacity(0.78), palette.BackgroundRaised.opacity(0.72)],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private var innerFill: Color {
        lit ? palette.Accent.opacity(0.14) : palette.BackgroundDeep.opacity(0.78)
    }

    // Elevation 6 → 28 as in the Kotlin. SwiftUI's shadow radius isn't
    // Material elevation, so it's mapped rather than copied: ~0.8x for the
    // blur and ~0.5x for the drop, which matches the Android render closely
    // enough side by side.
    private var elevation: CGFloat { lit && raised ? 28 : 6 }

    var body: some View {
        ZStack {
            // Inner content panel — the "recessed" surface. Clipped to the
            // same shape so the bevelled edge survives inside the frame.
            content()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(innerFill)
                .overlay(alignment: .top) {
                    // Specular top facet.
                    LinearGradient(
                        colors: [
                            .clear,
                            .white.opacity(lit ? 0.55 : 0.18),
                            .clear,
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(height: 1)
                }
                .clipShape(shape)
                .overlay {
                    shape.stroke(lit ? palette.Accent.opacity(0.55) : .clear, lineWidth: 1)
                }
                .padding(bevelInset)
        }
        // Outer shell — the "frame". Saturated accent gradient ring when
        // lit; a subtle elevated surface when idle.
        .background(shellGradient)
        .clipShape(shape)
        .overlay {
            shape.stroke(
                lit ? palette.FocusRing : palette.PanelBorder,
                lineWidth: lit ? 2 : 1
            )
        }
        .shadow(
            color: (lit ? palette.Accent : .black).opacity(lit ? 0.45 : 0.55),
            radius: elevation * 0.8,
            x: 0,
            y: elevation * 0.5
        )
        .scaleEffect(lit ? liftScale : 1)
        .offset(y: lit ? -lift : 0)
        .animation(springAnimation, value: lit)
    }
}
