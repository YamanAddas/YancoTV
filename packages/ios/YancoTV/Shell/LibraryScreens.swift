import SwiftUI

/// Favorites, Search, and the sections whose real implementations land in
/// later milestones (Guide MK.iOS.4, Recordings and Settings after that).
///
/// The placeholders are styled, not blank: an unbuilt section still has to
/// look like part of the product, and the Android app's own empty states
/// set the pattern — overline, title, body, all inside a `HexSurface`.

/// Grid of cut-corner cards. Used by Favorites and Search results.
struct ItemGrid: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let items: [YancoItem]
    @Bindable var state: ShellState

    private var compact: Bool { sizeClass == .compact }
    private var tileWidth: CGFloat { compact ? 160 : 200 }

    var body: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: tileWidth), spacing: Space.lg)],
                spacing: Space.xl
            ) {
                ForEach(items) { item in
                    RailTile(
                        item: item,
                        style: .poster,
                        width: tileWidth,
                        isFavorite: state.isFavorite(item)
                    ) {
                        state.detailItem = item
                    }
                }
            }
            .padding(.horizontal, compact ? Space.xl : Space.section)
            .padding(.vertical, Space.xl)
        }
    }
}

struct FavoritesScreen: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Bindable var state: ShellState

    var body: some View {
        VStack(alignment: .leading, spacing: Space.md) {
            RailHeader(
                eyebrow: "YOUR LIBRARY",
                title: "Favorites",
                caption: "Everything you starred"
            )
            .padding(.horizontal, sizeClass == .compact ? Space.xl : Space.section)
            .padding(.top, Space.xl)

            if state.favoriteItems.isEmpty {
                SectionPlaceholder(
                    overline: "EMPTY",
                    title: "No favorites yet",
                    message: "Star a channel, movie or series and it lands here for one-tap access."
                )
            } else {
                ItemGrid(items: state.favoriteItems, state: state)
            }
        }
    }
}

struct SearchScreen: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Bindable var state: ShellState

    private var inset: CGFloat { sizeClass == .compact ? Space.xl : Space.section }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.lg) {
            RailHeader(
                eyebrow: "FIND",
                title: "Search",
                caption: "Channels, movies and series"
            )
            .padding(.horizontal, inset)
            .padding(.top, Space.xl)

            HStack(spacing: Space.sm) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(palette.TextMuted)
                TextField(
                    "",
                    text: $state.searchQuery,
                    prompt: Text("Search your library").foregroundStyle(palette.TextFaint)
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(palette.TextPrimary)
                if !state.searchQuery.isEmpty {
                    Button {
                        state.searchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(palette.TextMuted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Space.lg)
            .frame(height: 44)
            .background(palette.BackgroundDeep.opacity(0.6), in: YancoShapes.hexPill)
            .overlay { YancoShapes.hexPill.stroke(palette.PanelBorder, lineWidth: 1) }
            .padding(.horizontal, inset)

            if state.searchQuery.isEmpty {
                SectionPlaceholder(
                    overline: "START TYPING",
                    title: "Search your library",
                    message: "Results span live channels, movies and series across every source."
                )
            } else if state.searchResults.isEmpty {
                SectionPlaceholder(
                    overline: "NO MATCHES",
                    title: "Nothing found",
                    message: "Try a different title, channel name or category."
                )
            } else {
                ItemGrid(items: state.searchResults, state: state)
            }
        }
    }
}

/// Guide / Recordings / Settings until their milestones land.
struct SectionPlaceholder: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let overline: String
    let title: String
    let message: String

    var body: some View {
        VStack {
            HexSurface(shape: YancoShapes.cutCornerCard, lit: false, bevelInset: 4) {
                VStack(alignment: .leading, spacing: Space.sm) {
                    Text(overline)
                        .yancoType(YancoType.overline)
                        .foregroundStyle(palette.Accent)
                    Text(title)
                        .yancoType(YancoType.displayS)
                        .foregroundStyle(palette.TextPrimary)
                    Text(message)
                        .yancoType(YancoType.bodyLong)
                        .foregroundStyle(palette.TextSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.xxxl)
                .padding(.vertical, Space.xxxl)
                .background(
                    LinearGradient(
                        colors: [palette.BackgroundRaised, palette.BackgroundElevated],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, sizeClass == .compact ? Space.xl : Space.section)

            Spacer(minLength: 0)
        }
        .padding(.top, Space.xl)
    }
}
