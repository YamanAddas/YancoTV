import SwiftUI

/// Artwork for the shell.
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

// MARK: - Genre palettes

/// Artwork hue pairs by provider category.
///
/// Generated art keyed purely off a hash gives every title a random
/// colour, which reads as noise on a rail — a thriller next to a cartoon
/// next to a news channel, all unrelated. Keying the *hue* off the genre
/// and the *variation* off the title means a rail of Sci-Fi reads as a
/// family while individual titles stay distinguishable, which is how real
/// key art behaves.
enum GenrePalette {
    /// (primary hue, secondary hue) as 0…1 positions on the wheel.
    static func hues(for group: String) -> (Double, Double) {
        switch group.lowercased() {
        case "sci-fi": return (0.54, 0.76)      // cyan → violet
        case "drama": return (0.07, 0.95)       // amber → rose
        case "crime": return (0.02, 0.09)       // red → ember
        case "thriller": return (0.62, 0.50)    // deep blue → teal
        case "action": return (0.05, 0.99)      // orange → crimson
        case "documentary": return (0.36, 0.48) // green → teal
        case "sports": return (0.42, 0.55)      // emerald → sea
        case "news": return (0.58, 0.66)        // blue → indigo
        case "arabic": return (0.11, 0.05)      // gold → copper
        case "kids": return (0.86, 0.55)        // magenta → cyan
        default:
            let h = Double(ProceduralArt.stableHash(group) % 100) / 100
            return (h, (h + 0.18).truncatingRemainder(dividingBy: 1))
        }
    }
}

/// Deterministic generated key art.
///
/// Everything is derived from a stable hash of the seed, so a given title
/// always draws the same frame across launches and devices. Note Swift's
/// own `hashValue` is per-process salted and would reshuffle the whole
/// library on every launch — hence the explicit FNV-1a below.
///
/// The composition is a colour field (a mesh gradient where the OS has
/// one, a layered radial/linear stack where it does not), a focal bloom,
/// a constellation of hex outlines drawn from the app's own shape
/// library, an edge falloff that makes the frame read as volumetric
/// rather than flat, and the title's initials set as a large low-contrast
/// mark.
struct ProceduralArt: View {
    @Environment(\.yancoPalette) private var palette

    let seed: String
    let monogram: String
    /// Provider category — drives the hue family. See [GenrePalette].
    var group: String = ""
    var lit: Bool = false

    private var hash: UInt64 { ProceduralArt.stableHash(seed) }

    /// Per-title jitter in 0…1. Different `index` values are independent
    /// enough for placement work.
    private func noise(_ index: Int) -> Double {
        Double((hash >> UInt64(index * 7 % 56)) % 1000) / 1000
    }

    private var hues: (Double, Double) { GenrePalette.hues(for: group) }

    /// Nudges the genre hue by ±0.04 so neighbouring titles in the same
    /// category are related but not identical.
    private var hueA: Double { wrap(hues.0 + (noise(1) - 0.5) * 0.08) }
    private var hueB: Double { wrap(hues.1 + (noise(2) - 0.5) * 0.08) }

    private func wrap(_ value: Double) -> Double {
        let v = value.truncatingRemainder(dividingBy: 1)
        return v < 0 ? v + 1 : v
    }

    /// Midpoint of two hues *on the wheel*.
    ///
    /// A plain `(a + b) / 2` is wrong whenever the pair straddles the
    /// 0/1 seam, and it fails loudly: Drama is amber 0.07 + rose 0.95,
    /// two neighbouring warm hues whose linear mean is 0.51 — cyan, the
    /// exact complement of what was asked for. Every Drama tile was
    /// rendering with a cold blue core inside a warm frame.
    private func hueMidpoint(_ a: Double, _ b: Double) -> Double {
        let low = min(a, b)
        let high = max(a, b)
        // Going the short way round: if the direct gap is more than half
        // the wheel, the pair is adjacent across the seam instead.
        return high - low > 0.5 ? wrap((high + low + 1) / 2) : (high + low) / 2
    }

    private var hueCore: Double { hueMidpoint(hueA, hueB) }

    private func tone(_ hue: Double, _ saturation: Double, _ brightness: Double) -> Color {
        Color(hue: hue, saturation: saturation, brightness: brightness)
    }

    /// Nine stops: dark corners, mid edges, a bright core. Baking the
    /// falloff into the field itself — rather than laying a vignette over
    /// a flat fill — is what makes it read as lit from within.
    private var meshColors: [Color] {
        [
            tone(hueA, 0.75, 0.13), tone(hueA, 0.68, 0.24), tone(hueB, 0.72, 0.15),
            tone(hueA, 0.62, 0.30), tone(hueCore, 0.55, 0.62), tone(hueB, 0.62, 0.32),
            tone(hueB, 0.78, 0.12), tone(hueB, 0.70, 0.22), tone(hueA, 0.76, 0.14),
        ]
    }

    var body: some View {
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            ZStack {
                colorField
                bloom(side: side)
                hexConstellation(side: side)
                specular
                edgeFalloff(side: side)
                mark(side: side)
            }
        }
        .clipped()
    }

    // MARK: Layers

    @ViewBuilder
    private var colorField: some View {
        if #available(iOS 18.0, *) {
            MeshGradient(
                width: 3,
                height: 3,
                points: [
                    .init(0, 0), .init(Float(0.35 + noise(3) * 0.3), 0), .init(1, 0),
                    .init(0, Float(0.35 + noise(4) * 0.3)),
                    .init(Float(0.35 + noise(5) * 0.3), Float(0.35 + noise(6) * 0.3)),
                    .init(1, Float(0.4 + noise(7) * 0.25)),
                    .init(0, 1), .init(Float(0.4 + noise(8) * 0.25), 1), .init(1, 1),
                ],
                colors: meshColors,
                smoothsColors: true
            )
        } else {
            // iOS 17 has no MeshGradient. Two crossed gradients plus the
            // bloom below land close enough that the two paths are hard to
            // tell apart at tile size.
            ZStack {
                LinearGradient(
                    colors: [tone(hueA, 0.70, 0.30), tone(hueB, 0.74, 0.13)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [tone(hueB, 0.62, 0.28).opacity(0.7), .clear],
                    startPoint: .bottomLeading,
                    endPoint: .topTrailing
                )
            }
        }
    }

    /// Off-centre focal glow — the thing the eye lands on.
    private func bloom(side: CGFloat) -> some View {
        RadialGradient(
            colors: [
                tone(hueCore, 0.42, 0.88).opacity(0.38),
                .clear,
            ],
            center: UnitPoint(x: 0.28 + noise(9) * 0.44, y: 0.24 + noise(10) * 0.38),
            startRadius: 0,
            endRadius: side * 0.55
        )
        .blendMode(.screen)
    }

    /// Concentric hex outlines radiating from a focal point. Ties the
    /// generated frame to the shape language the cards themselves use, so
    /// it reads as part of the product rather than as generic noise.
    private func hexConstellation(side: CGFloat) -> some View {
        let originX = 0.30 + noise(11) * 0.40
        let originY = 0.28 + noise(12) * 0.36
        let rotation = noise(13) * 40 - 20

        return ZStack {
            ForEach(0..<4, id: \.self) { ring in
                let scale = 0.34 + Double(ring) * 0.30
                PointyHexShape()
                    .stroke(
                        Color.white.opacity(0.10 - Double(ring) * 0.018),
                        lineWidth: ring == 0 ? 1.6 : 1
                    )
                    .frame(width: side * scale, height: side * scale * 1.08)
            }
        }
        .rotationEffect(.degrees(rotation))
        .position(x: side * originX, y: side * originY)
        .blendMode(.plusLighter)
    }

    /// A single soft sweep across the upper-left, the way light falls on
    /// a curved surface. Keeps the frame from looking like printed flat
    /// colour.
    private var specular: some View {
        LinearGradient(
            stops: [
                .init(color: .white.opacity(0.16), location: 0),
                .init(color: .white.opacity(0.04), location: 0.32),
                .init(color: .clear, location: 0.62),
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .blendMode(.softLight)
    }

    /// Darkens toward the edges so the tile reads as a volume with a lit
    /// centre. Lighter than a conventional vignette because the mesh
    /// already carries most of the falloff.
    private func edgeFalloff(side: CGFloat) -> some View {
        RadialGradient(
            colors: [.clear, palette.BackgroundDeep.opacity(0.62)],
            center: .center,
            startRadius: side * 0.30,
            endRadius: side * 0.82
        )
    }

    /// The initials, set as a mark rather than a label: a soft gradient
    /// fill, a dark drop to lift it off the field, and enough restraint
    /// that artwork replacing it later is not a downgrade.
    private func mark(side: CGFloat) -> some View {
        Text(monogram)
            .font(.system(size: side * 0.32, weight: .black, design: .rounded))
            .tracking(-side * 0.012)
            .foregroundStyle(
                LinearGradient(
                    colors: [
                        .white.opacity(lit ? 0.42 : 0.26),
                        .white.opacity(lit ? 0.16 : 0.09),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .shadow(color: .black.opacity(0.45), radius: side * 0.05, y: side * 0.012)
            .minimumScaleFactor(0.4)
            .lineLimit(1)
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
    var group: String = ""
    var lit: Bool = false

    var body: some View {
        ProceduralArt(seed: seed, monogram: monogram, group: group, lit: lit)
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
