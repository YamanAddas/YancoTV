import SwiftUI

/// Port of `ui/shell/AppSidebar.kt`.
///
/// Always visible, never hidden — only narrowed, 92 ⟷ 260 over a 180ms
/// ease. On TV the width is bound to *sidebar has focus*; there is no
/// focus on iOS, so it is bound to an explicit toggle instead, which is
/// the same animation driven by the one input a touch screen actually has.
///
/// The state rule from the Kotlin comments is preserved exactly:
/// **selected wins on background, press wins on border.** The fill never
/// repaints when a row is pressed — only the 2pt frame appears.
struct AppSidebar: View {
    @Environment(\.yancoPalette) private var palette
    @Binding var section: AppSection
    @Binding var expanded: Bool

    private var width: CGFloat { expanded ? ShellDim.sidebarExpanded : ShellDim.sidebarCollapsed }

    /// One shared alpha for every label, derived from the width — not a
    /// per-row animation. Matches `labelAlpha` in the Kotlin.
    private var labelAlpha: Double {
        Double((width - ShellDim.sidebarCollapsed) / (ShellDim.sidebarExpanded - ShellDim.sidebarCollapsed))
    }

    var body: some View {
        VStack(spacing: 0) {
            BrandMark(expanded: expanded)
                .padding(.horizontal, Space.xs)
                .padding(.vertical, Space.xs)
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() }
                }

            Spacer().frame(height: Space.md)

            ScrollView {
                VStack(spacing: Space.xxs) {
                    ForEach(AppSection.allCases) { item in
                        SidebarRow(
                            section: item,
                            selected: item == section,
                            labelAlpha: labelAlpha
                        ) {
                            section = item
                        }
                    }
                }
            }
            .scrollIndicators(.hidden)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.md)
        .padding(.vertical, Space.md)
        .frame(width: width)
        .frame(maxHeight: .infinity)
        .background(
            LinearGradient(
                colors: [
                    palette.BackgroundElevated.opacity(0.86),
                    palette.BackgroundRaised.opacity(0.78),
                    palette.BackgroundDeep.opacity(0.88),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .overlay {
            Rectangle().stroke(palette.BorderSubtle.opacity(0.4), lineWidth: 1)
        }
        .animation(.easeInOut(duration: 0.18), value: expanded)
    }
}

private struct SidebarRow: View {
    @Environment(\.yancoPalette) private var palette

    let section: AppSection
    let selected: Bool
    let labelAlpha: Double
    let action: () -> Void

    @State private var pressed = false

    private var active: Bool { selected || pressed }

    private var foreground: Color {
        if pressed { return palette.TextPrimary }
        if selected { return palette.Accent }
        return palette.TextSecondary
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 0) {
                // Left accent rail — 4pt, pill-capped, full height when
                // active and inset 8pt top/bottom when not (where it is
                // transparent anyway).
                Capsule()
                    .fill(
                        active
                            ? LinearGradient(
                                colors: [palette.AccentSoft, palette.Accent, palette.AccentDeep],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                            : LinearGradient(colors: [.clear], startPoint: .top, endPoint: .bottom)
                    )
                    .frame(width: 4)
                    .padding(.vertical, active ? 0 : Space.sm)
                    .shadow(color: active ? palette.Accent.opacity(0.6) : .clear, radius: 9)

                HStack(spacing: Space.md) {
                    Image(systemName: section.symbol)
                        .font(.system(size: 17, weight: .regular))
                        .frame(width: 22, height: 22)

                    if labelAlpha > 0 {
                        Text(section.label)
                            .yancoType(selected || pressed ? YancoType.labelStrong : YancoType.label)
                            .lineLimit(1)
                            .opacity(labelAlpha)
                            .fixedSize(horizontal: true, vertical: false)
                    }

                    Spacer(minLength: 0)
                }
                .foregroundStyle(foreground)
                .padding(.leading, Space.sm)
                .padding(.horizontal, Space.md)
                .padding(.vertical, Space.sm)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background {
                    // The 10pt rounded rect is the one surface in the app
                    // that opts out of the cut-corner family — a fixed cut
                    // is 23% of a 92pt row but only 7% of a 260pt one, so
                    // it cannot survive the width morph (MB-113).
                    RoundedRectangle(cornerRadius: 10)
                        .fill(
                            active
                                ? LinearGradient(
                                    colors: [
                                        palette.Accent.opacity(0.28),
                                        palette.Accent.opacity(0.10),
                                        .clear,
                                    ],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                                : LinearGradient(colors: [.clear], startPoint: .leading, endPoint: .trailing)
                        )
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(pressed ? palette.FocusRing : .clear, lineWidth: 2)
                }
                .padding(.leading, Space.sm)
            }
            .frame(height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        ._onPressChange { pressed = $0 }
        .accessibilityLabel(section.label)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }
}

/// The Android sidebar ships `ic_logo` (16:9 lockup) and `ic_logo_mark`
/// (square badge). Neither vector crossed over, so the mark is drawn here
/// from the same primitive the rest of the shell is built on — a pointy
/// hex in the accent gradient — and the wordmark is set in the display
/// role rather than shipped as art.
struct BrandMark: View {
    @Environment(\.yancoPalette) private var palette
    let expanded: Bool

    var body: some View {
        HStack(spacing: Space.sm) {
            PointyHexShape()
                .fill(
                    LinearGradient(
                        colors: [palette.AccentSoft, palette.Accent, palette.AccentDeep],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: 30, height: 34)
                .overlay {
                    Text("Y")
                        .font(.system(size: 17, weight: .black))
                        .foregroundStyle(palette.BackgroundDeep)
                }
                .shadow(color: palette.Accent.opacity(0.45), radius: 10, y: 3)

            if expanded {
                HStack(spacing: 0) {
                    Text("YANCO")
                        .font(.system(size: 19, weight: .heavy))
                        .foregroundStyle(palette.TextPrimary)
                    Text(".TV")
                        .font(.system(size: 19, weight: .heavy))
                        .foregroundStyle(palette.Accent)
                }
                .fixedSize()
                .transition(.opacity)
            }

            Spacer(minLength: 0)
        }
        .frame(height: expanded ? 56 : 44)
        .contentShape(Rectangle())
    }
}
