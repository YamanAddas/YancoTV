import SwiftUI

/// Port of `CoverflowSectionScreen.HexCta` / `YancoButton.kt`.
///
/// Shape is `ButtonBevel`; padding h20/v12; icon 18pt; label `LabelStrong`
/// with an 8pt gap. The Android state matrix is reproduced exactly, with
/// D-pad focus mapped to the press state — see the note in `HexSurface`
/// on why "lit" replaces "focused" throughout the iOS port.
///
/// | state              | fill                | border          | foreground     |
/// |--------------------|---------------------|-----------------|----------------|
/// | primary + pressed  | AccentGlow          | FocusRing 2pt   | BackgroundDeep |
/// | primary            | Accent              | AccentDeep 1pt  | BackgroundDeep |
/// | pressed            | Accent 22%          | FocusRing 2pt   | TextPrimary    |
/// | highlighted        | Accent 14%          | Accent 55% 1pt  | Accent         |
/// | idle               | BackgroundDeep 60%  | PanelBorder 1pt | TextPrimary    |
struct HexCta: View {
    @Environment(\.yancoPalette) private var palette

    let title: String
    var symbol: String?
    var primary: Bool = false
    /// Persistent "on" state — e.g. an item already in favourites.
    var highlighted: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sm) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 18, weight: .semibold))
                }
                Text(title)
                    .yancoType(YancoType.labelStrong)
            }
        }
        .buttonStyle(
            HexCtaStyle(palette: palette, primary: primary, highlighted: highlighted)
        )
    }
}

private struct HexCtaStyle: ButtonStyle {
    let palette: YancoPalette
    let primary: Bool
    let highlighted: Bool

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed

        let fill: Color = {
            if primary { return pressed ? palette.AccentGlow : palette.Accent }
            if pressed { return palette.Accent.opacity(0.22) }
            if highlighted { return palette.Accent.opacity(0.14) }
            return palette.BackgroundDeep.opacity(0.60)
        }()

        let border: Color = {
            if pressed { return palette.FocusRing }
            if primary { return palette.AccentDeep }
            if highlighted { return palette.Accent.opacity(0.55) }
            return palette.PanelBorder
        }()

        let foreground: Color = {
            if primary { return palette.BackgroundDeep }
            if highlighted { return palette.Accent }
            return palette.TextPrimary
        }()

        return configuration.label
            .foregroundStyle(foreground)
            .padding(.horizontal, Space.xl)
            .padding(.vertical, Space.md)
            .background(fill, in: ButtonBevelShape())
            .overlay {
                ButtonBevelShape().stroke(border, lineWidth: pressed ? 2 : 1)
            }
            // Focus glow on TV becomes a press glow here — 14dp elevation,
            // accent-tinted, same as the Kotlin `shadow(...)` call.
            .shadow(
                color: pressed ? palette.Accent.opacity(0.5) : .clear,
                radius: 11,
                y: 5
            )
            .scaleEffect(pressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: pressed)
    }
}
