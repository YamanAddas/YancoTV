import SwiftUI

/// Port of `ui/components/WheelRail.kt` — the 3D wheel carousel.
///
/// This is the most distinctive behaviour on the home screen and the one
/// piece that makes the rails read as YancoTV rather than as a generic
/// poster row. Cards rotate about Y as they travel away from the centre
/// of the viewport, shrinking and dimming with distance, and the rail
/// centre-snaps rather than left-aligning.
///
/// The Compose transform, reproduced verbatim:
/// ```
/// normalized = clamp((itemCenter - viewportCenter) / (viewportWidth/2), -1, 1)
/// rotationY  = normalized * 38°
/// scale      = 1 - (1 - 0.82) * |normalized|
/// alpha      = 1 - (1 - 0.60) * |normalized|
/// origin.x   = 0.5 + ((normalized < 0 ? 1 : 0) - 0.5) * |normalized|
/// ```
/// The moving anchor is what sells the depth — a card leaving to the left
/// pivots about its right edge and vice versa, so the row reads as a wheel
/// turning rather than a set of independently tilted cards.
///
/// Compose set `cameraDistance = 14 × density`; SwiftUI expresses the same
/// idea as `perspective`, tuned here to match the Android render.
struct WheelRail<Item: Identifiable, Content: View>: View {
    let items: [Item]
    /// 220pt on every home rail (`ShellDim.posterTile`).
    var itemWidth: CGFloat = ShellDim.posterTile
    var spacing: CGFloat = Space.lg
    var minSidePadding: CGFloat = Space.section
    @ViewBuilder var content: (Item) -> Content

    private let maxRotation: Double = 38
    private let minScale: CGFloat = 0.82
    private let minAlpha: CGFloat = 0.60

    var body: some View {
        GeometryReader { outer in
            // `sidePad = max((maxWidth - itemWidth) / 2, minSidePadding)` —
            // wide lanes centre the focused card, narrow ones (a phone in
            // portrait) clamp to the page inset and behave like a normal
            // carousel.
            let sidePad = max((outer.size.width - itemWidth) / 2, minSidePadding)
            let viewportCenter = outer.size.width / 2

            ScrollView(.horizontal) {
                LazyHStack(spacing: spacing) {
                    ForEach(items) { item in
                        content(item)
                            .frame(width: itemWidth)
                            .visualEffect { view, proxy in
                                let itemCenter = proxy.frame(in: .scrollView(axis: .horizontal)).midX
                                let normalized = max(-1, min(1, (itemCenter - viewportCenter) / max(viewportCenter, 1)))
                                let magnitude = abs(normalized)
                                return view
                                    .rotation3DEffect(
                                        .degrees(normalized * maxRotation),
                                        axis: (x: 0, y: 1, z: 0),
                                        anchor: UnitPoint(
                                            x: 0.5 + ((normalized < 0 ? 1.0 : 0.0) - 0.5) * magnitude,
                                            y: 0.5
                                        ),
                                        perspective: 0.42
                                    )
                                    .scaleEffect(1 - (1 - minScale) * magnitude)
                                    .opacity(1 - (1 - minAlpha) * magnitude)
                            }
                    }
                }
                .scrollTargetLayout()
                .padding(.vertical, Space.lg)
            }
            .scrollTargetBehavior(.viewAligned)
            .contentMargins(.horizontal, sidePad, for: .scrollContent)
            .scrollIndicators(.hidden)
        }
    }
}
