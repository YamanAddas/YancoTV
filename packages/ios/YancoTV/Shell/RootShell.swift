import SwiftUI

/// The app shell — port of `HomeScreen.kt`'s root composition.
///
/// Android z-order, preserved here:
/// ```
/// ZStack
/// ├─ [z0] CinematicBackground          full-bleed under the safe area
/// ├─ [z1] sidebar + section content
/// └─ [z2] detail overlay
/// ```
///
/// ### Sidebar on a phone
///
/// The TV shell is always a `Row(sidebar, content)`. A 92pt rail is 24% of
/// an iPhone's width, so at the compact size class the same destinations
/// move to a bottom bar built from the same vocabulary — hex chips, accent
/// fill for selection. Regular width (iPad, and iPhone landscape on the
/// larger devices) gets the real sidebar, morphing 92 ⟷ 260 exactly as on
/// TV. Nothing is removed in either case; `AppSection.compactOverflow`
/// keeps the remaining four destinations one tap away.
struct RootShell: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var state = ShellState()
    @State private var sidebarExpanded = true
    @State private var showsOverflow = false

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        ZStack {
            CinematicBackground()

            if compact {
                compactLayout
            } else {
                regularLayout
            }

            if let item = state.detailItem {
                DetailScreen(item: item, state: state) {
                    withAnimation(.easeOut(duration: 0.2)) { state.detailItem = nil }
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
                .zIndex(2)
            }
        }
        .environment(\.yancoPalette, .frostedEmerald)
        .preferredColorScheme(.dark)
        .animation(.easeOut(duration: 0.2), value: state.detailItem)
    }

    // MARK: - Layouts

    private var regularLayout: some View {
        HStack(spacing: 0) {
            AppSidebar(section: $state.section, expanded: $sidebarExpanded)
            sectionContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var compactLayout: some View {
        VStack(spacing: 0) {
            compactTopBar
            sectionContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            CompactTabBar(section: $state.section, showsOverflow: $showsOverflow)
        }
        .sheet(isPresented: $showsOverflow) {
            OverflowSheet(section: $state.section)
                .presentationDetents([.height(320)])
                .presentationBackground(YancoPalette.frostedEmerald.BackgroundRaised)
        }
    }

    private var compactTopBar: some View {
        HStack {
            BrandMark(expanded: true)
            Spacer()
        }
        .padding(.horizontal, Space.xl)
        .padding(.bottom, Space.sm)
    }

    @ViewBuilder
    private var sectionContent: some View {
        switch state.section {
        case .home:
            HomeScreen(state: state)
        case .liveTv:
            CoverflowScreen(kind: .live, state: state)
        case .movies:
            CoverflowScreen(kind: .movie, state: state)
        case .series:
            CoverflowScreen(kind: .series, state: state)
        case .favorites:
            FavoritesScreen(state: state)
        case .search:
            SearchScreen(state: state)
        case .guide:
            SectionPlaceholder(
                overline: "MK.iOS.4",
                title: "TV Guide",
                message: "The full EPG grid with catch-up and timeshift lands with the EPG milestone, reusing the XMLTV parser the Android app already ships."
            )
        case .recordings:
            SectionPlaceholder(
                overline: "LATER",
                title: "Recordings",
                message: "Scheduled and completed recordings, once playback and the recording pipeline are wired on iOS."
            )
        case .settings:
            SectionPlaceholder(
                overline: "MK.iOS.2",
                title: "Settings",
                message: "Sources, credentials in the iOS Keychain, parental controls, appearance and playback preferences."
            )
        }
    }
}

/// Bottom bar for the compact size class. Same language as the sidebar —
/// accent fill for the selected destination, hex silhouette, line icons.
private struct CompactTabBar: View {
    @Environment(\.yancoPalette) private var palette
    @Binding var section: AppSection
    @Binding var showsOverflow: Bool

    var body: some View {
        HStack(spacing: Space.xs) {
            ForEach(AppSection.compactPrimary) { item in
                tab(symbol: item.symbol, label: item.label, selected: section == item) {
                    section = item
                }
            }
            tab(
                symbol: "ellipsis",
                label: "More",
                selected: AppSection.compactOverflow.contains(section)
            ) {
                showsOverflow = true
            }
        }
        .padding(.horizontal, Space.md)
        .padding(.top, Space.sm)
        .background(
            LinearGradient(
                colors: [
                    palette.BackgroundRaised.opacity(0.92),
                    palette.BackgroundDeep.opacity(0.96),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .bottom)
        )
        .overlay(alignment: .top) {
            Rectangle()
                .fill(palette.BorderSubtle)
                .frame(height: 1)
        }
    }

    private func tab(
        symbol: String,
        label: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: selected ? .semibold : .regular))
                Text(label)
                    .font(.system(size: 10, weight: selected ? .bold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .foregroundStyle(selected ? palette.Accent : palette.TextMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, Space.sm)
            .background {
                if selected {
                    ChipBevelShape()
                        .fill(palette.Accent.opacity(0.14))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }
}

/// The four destinations that don't fit the compact bar.
private struct OverflowSheet: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.dismiss) private var dismiss
    @Binding var section: AppSection

    var body: some View {
        VStack(alignment: .leading, spacing: Space.md) {
            Text("MORE")
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.Accent)
                .padding(.top, Space.xl)

            ForEach(AppSection.compactOverflow) { item in
                Button {
                    section = item
                    dismiss()
                } label: {
                    HStack(spacing: Space.md) {
                        Image(systemName: item.symbol)
                            .font(.system(size: 17))
                            .frame(width: 22)
                        Text(item.label)
                            .yancoType(YancoType.label)
                        Spacer()
                    }
                    .foregroundStyle(section == item ? palette.Accent : palette.TextSecondary)
                    .padding(.horizontal, Space.lg)
                    .frame(height: 52)
                    .background {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(section == item ? palette.Accent.opacity(0.14) : .clear)
                    }
                }
                .buttonStyle(.plain)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.xl)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.BackgroundRaised)
        .environment(\.yancoPalette, .frostedEmerald)
    }
}
