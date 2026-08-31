import SwiftUI

/// Port of `ui/shell/CategoryChipBar.kt` — the horizontal category strip.
///
/// Order: **Favorites → All → separator → provider groups**. The
/// separator is a literal 1x20pt `BorderSubtle` spacer, not a divider
/// view.
///
/// State matrix (note it differs from the vertical rail's, deliberately):
/// the chip's selected wash is stronger (32%/22% vs the rail's 22%/14%),
/// and a selected chip gets **no border at all** — border is reserved
/// exclusively for the focus/press signal (MB-112).
struct CategoryChipBar: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let kind: ContentKind
    @Bindable var state: ShellState

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Space.sm) {
                chip(
                    label: "Favorites",
                    symbol: "star.fill",
                    selected: state.group(for: kind) == SpecialGroup.favorites
                ) {
                    state.setGroup(SpecialGroup.favorites, for: kind)
                }

                chip(
                    label: "All",
                    symbol: "square.grid.2x2",
                    selected: state.group(for: kind) == nil
                ) {
                    state.setGroup(nil, for: kind)
                }

                Rectangle()
                    .fill(palette.BorderSubtle)
                    .frame(width: 1, height: 20)

                ForEach(SampleContent.groups(for: kind), id: \.self) { group in
                    chip(label: group, symbol: nil, selected: state.group(for: kind) == group) {
                        state.setGroup(group, for: kind)
                    }
                }
            }
            .padding(.horizontal, compact ? Space.xl : Space.page)
            .padding(.vertical, Space.xs)
        }
        .scrollIndicators(.hidden)
    }

    private func chip(
        label: String,
        symbol: String?,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: Space.xs) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 14, weight: .semibold))
                } else if selected {
                    // No icon and selected → a 6pt accent pip. Unlike the
                    // vertical rail's pip, this one stays Accent even when
                    // pressed.
                    Circle()
                        .fill(palette.Accent)
                        .frame(width: 6, height: 6)
                }
                Text(label)
                    .yancoType(selected ? YancoType.labelStrong : YancoType.label)
                    .lineLimit(1)
            }
        }
        .buttonStyle(ChipStyle(palette: palette, selected: selected))
    }
}

private struct ChipStyle: ButtonStyle {
    let palette: YancoPalette
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed

        let fill: LinearGradient = {
            if pressed {
                return LinearGradient(
                    colors: [palette.Accent, palette.AccentDeep],
                    startPoint: .top, endPoint: .bottom
                )
            }
            if selected {
                return LinearGradient(
                    colors: [palette.Accent.opacity(0.32), palette.AccentDeep.opacity(0.22)],
                    startPoint: .top, endPoint: .bottom
                )
            }
            return LinearGradient(
                colors: [palette.BackgroundDeep.opacity(0.55), palette.BackgroundDeep.opacity(0.55)],
                startPoint: .top, endPoint: .bottom
            )
        }()

        let foreground: Color = {
            if pressed { return .black }
            if selected { return palette.Accent }
            return palette.TextSecondary
        }()

        return configuration.label
            .foregroundStyle(foreground)
            .padding(.horizontal, Space.lg)
            .padding(.vertical, Space.sm)
            .frame(height: 38)
            .background(fill, in: ChipBevelShape())
            .overlay {
                // Border is the press signal only — never the selection.
                ChipBevelShape().stroke(pressed ? palette.FocusRing : .clear, lineWidth: 2)
            }
            .shadow(color: pressed ? palette.Accent.opacity(0.5) : .clear, radius: 11, y: 4)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: pressed)
    }
}
