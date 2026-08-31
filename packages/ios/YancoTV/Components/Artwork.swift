import SwiftUI

/// Artwork for the MK.iOS.1 shell.
///
/// Real artwork comes from the provider (Xtream `stream_icon` /
/// `cover_big`, M3U `tvg-logo`) and lands with MK.iOS.2 — [TileArt] keeps
/// the remote-loading path wired so that swap is a URL change. Until then
/// these return `nil` and every frame falls through to [ProceduralArt].
///
/// A placeholder image *host* was tried first and rejected: it adds a
/// network dependency to a screen that has no business needing one, it
/// renders nothing when the user is offline, and the one used during
/// development (`picsum.photos`) was returning 503 within the hour. Drawn
/// artwork has none of those failure modes and — because it is built from
/// the app's own hex vocabulary — actually looks like YancoTV.
enum Artwork {
    /// 16:9 — channel tiles, rail cards, hero backdrops.
    static func backdrop(_ seed: String, width: Int = 640) -> URL? { nil }

    /// 2:3 — VOD posters on the detail page and browse grid.
    static func poster(_ seed: String, width: Int = 400) -> URL? { nil }
}

/// Deterministic generated key art.
///
/// Everything is derived from a stable hash of the seed, so a given title
/// always draws the same frame across launches and devices. Note Swift's
/// own `hashValue` is per-process salted and would reshuffle the whole
/// library on every launch — hence the explicit FNV-1a below.
///
/// The composition is four layers: a two-tone diagonal ground, an
/// off-centre radial bloom, a scatter of hex outlines from the shape
/// library, and a vignette, with the monogram set as a large low-contrast
/// watermark.
struct ProceduralArt: View {
    @Environment(\.yancoPalette) private var palette

    let seed: String
    let monogram: String
    var lit: Bool = false

    private var hash: UInt64 { ProceduralArt.stableHash(seed) }
    /// Hue drifts across the full wheel but stays in the app's dim,
    /// desaturated register so no card competes with the accent.
    private var hue: Double { Double(hash % 360) / 360 }
    private var tilt: Double { Double((hash >> 8) % 40) - 20 }
    private var bloomX: Double { 0.2 + Double((hash >> 16) % 60) / 100 }
    private var bloomY: Double { 0.15 + Double((hash >> 24) % 50) / 100 }

    /// Two stops a third of the wheel apart, so a frame has some internal
    /// colour movement instead of reading as one flat wash. Brightness is
    /// deliberately low — these sit under scrims and behind text — but not
    /// so low that every card collapses to the same near-black, which is
    /// what a first pass at 0.19 did.
    private var deep: Color { Color(hue: hue, saturation: 0.62, brightness: 0.26) }
    private var mid: Color { Color(hue: (hue + 0.08).truncatingRemainder(dividingBy: 1), saturation: 0.50, brightness: 0.46) }

    var body: some View {
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            ZStack {
                LinearGradient(
                    colors: [mid, deep, palette.BackgroundDeep],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .rotationEffect(.degrees(tilt))
                .scaleEffect(1.6)

                RadialGradient(
                    colors: [palette.AccentSoft.opacity(0.22), .clear],
                    center: UnitPoint(x: bloomX, y: bloomY),
                    startRadius: 0,
                    endRadius: side * 0.75
                )

                hexMotif(side: side)

                RadialGradient(
                    colors: [.clear, palette.BackgroundDeep.opacity(0.55)],
                    center: .center,
                    startRadius: side * 0.3,
                    endRadius: side * 0.95
                )

                Text(monogram)
                    .font(.system(size: side * 0.30, weight: .black))
                    .foregroundStyle(
                        (lit ? palette.Accent : palette.TextPrimary).opacity(lit ? 0.30 : 0.16)
                    )
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
            }
        }
        .clipped()
    }

    /// Three hex outlines from the shape library, sized and placed off the
    /// seed. Keeps the generated art inside the same visual family as the
    /// cards it sits in rather than reading as generic noise.
    private func hexMotif(side: CGFloat) -> some View {
        ZStack {
            ForEach(0..<3, id: \.self) { index in
                hexBlob(index: index, side: side)
            }
        }
    }

    private func hexBlob(index: Int, side: CGFloat) -> some View {
        // Split out of `hexMotif` and pre-computed into locals: as one
        // chained expression the type checker gave up on it.
        let a = Double((hash >> UInt64(4 * index + 2)) % 100) / 100
        let b = Double((hash >> UInt64(3 * index + 5)) % 100) / 100
        let size: CGFloat = side * CGFloat(0.30 + a * 0.45)
        let dx: CGFloat = side * CGFloat((a - 0.5) * 0.9)
        let dy: CGFloat = side * CGFloat((b - 0.5) * 0.7)

        return PointyHexShape()
            .stroke(palette.Accent.opacity(0.10), lineWidth: 1.5)
            .frame(width: size, height: size * 1.1)
            .rotationEffect(.degrees(a * 30 - 15))
            .offset(x: dx, y: dy)
    }

    /// FNV-1a. Stable across processes, unlike `Hashable.hashValue`.
    static func stableHash(_ string: String) -> UInt64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in string.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        return hash
    }
}

/// Port of `HomeContent.TileArt`.
///
/// Renders provider artwork when there is any, and the generated frame
/// beneath it otherwise — which is also the Android behaviour, where a
/// missing `tvg-logo` falls back to the title's initials on a gradient.
/// A meaningful share of provider logo URLs 404, so this is a live path,
/// not just a loading state.
struct TileArt: View {
    let url: URL?
    /// The item's own artwork seed — not the monogram, so two titles that
    /// share initials still draw different frames.
    let seed: String
    let monogram: String
    var lit: Bool = false

    var body: some View {
        ProceduralArt(seed: seed, monogram: monogram, lit: lit)
            .overlay {
                AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.24))) { phase in
                    if case .success(let image) = phase {
                        image
                            .resizable()
                            .scaledToFill()
                            .transition(.opacity)
                    }
                    // .empty and .failure both fall through to the art
                    // beneath — no spinner, no broken-image glyph.
                }
            }
            .clipped()
            .contentShape(Rectangle())
    }
}
