import Shared
import SwiftUI

/// MK.iOS.0 — proof-of-bridge screen.
///
/// Two things have to be true before any real screen is worth building,
/// and this view demonstrates both on launch:
///  1. The Kotlin `Shared` framework links and its platform actual runs
///     (`Platform().name` comes from UIDevice on this side).
///  2. Real business logic crosses the bridge — the sample playlist below
///     goes through the same `parseM3u` the Android app ships, and the
///     parsed entries render from Kotlin data classes.
struct ContentView: View {
    private let platformName = Platform().name

    private let parsed: M3uParseResult = {
        let sample = """
        #EXTM3U url-tvg="https://example.com/guide.xml"
        #EXTINF:-1 tvg-id="yanco.one" tvg-logo="" group-title="Live Demo",Yanco One
        http://example.com/live/1.m3u8
        #EXTINF:-1 tvg-id="yanco.sports" group-title="Live Demo",Yanco Sports
        http://example.com/live/2.m3u8
        #EXTINF:-1 group-title="Movies Demo",Big Buck Bunny (2008)
        http://example.com/vod/bbb.mp4
        """
        return M3uParserKt.parseM3u(content: sample, logger: LoggerKt.NOOP_LOGGER)
    }()

    var body: some View {
        ZStack {
            YancoColor.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    wordmark
                    statusCard
                    parsedSection
                    Spacer(minLength: 0)
                    footer
                }
                .padding(24)
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var wordmark: some View {
        HStack(spacing: 0) {
            Text("YANCO")
                .font(.system(size: 40, weight: .heavy, design: .default))
                .foregroundStyle(YancoColor.textPrimary)
            Text(".TV")
                .font(.system(size: 40, weight: .heavy, design: .default))
                .foregroundStyle(YancoColor.teal)
        }
        .padding(.top, 12)
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            label("SHARED FRAMEWORK LINKED")
            HStack(spacing: 8) {
                Circle()
                    .fill(YancoColor.teal)
                    .frame(width: 8, height: 8)
                Text(platformName)
                    .font(.system(size: 15, design: .monospaced))
                    .foregroundStyle(YancoColor.textPrimary)
            }
            Text("Platform() resolved by the Kotlin iosMain actual via UIDevice.")
                .font(.system(size: 13))
                .foregroundStyle(YancoColor.textMuted)
        }
        .card()
    }

    private var parsedSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            label("PARSE M3U — SHARED BUSINESS LOGIC")
            ForEach(parsed.entries, id: \.streamUrl) { entry in
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text(entry.title)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(YancoColor.textPrimary)
                        Text(entry.groupTitle)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(YancoColor.teal)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(YancoColor.teal.opacity(0.12), in: Capsule())
                    }
                    Text(entry.streamUrl)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(YancoColor.textMuted)
                }
                .padding(.vertical, 6)
            }
            if let epg = parsed.epgUrl {
                Text("EPG: \(epg)")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(YancoColor.textMuted)
            }
        }
        .card()
    }

    private var footer: some View {
        Text("MK.iOS.0 — Xcode scaffold · Kotlin shared framework · SwiftUI")
            .font(.system(size: 12))
            .foregroundStyle(YancoColor.textDim)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.top, 8)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .bold))
            .kerning(1.4)
            .foregroundStyle(YancoColor.teal)
    }
}

extension View {
    fileprivate func card() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(YancoColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(YancoColor.hairline, lineWidth: 1)
            )
    }
}

/// Placeholder palette for the scaffold — the real theme system lands with
/// the MK.iOS.1 shell. Teal `#00E5C1` on near-black is the Yanco family
/// through-line.
enum YancoColor {
    static let background = Color(red: 0.043, green: 0.051, blue: 0.071)
    static let surface = Color(red: 0.078, green: 0.094, blue: 0.125)
    static let hairline = Color.white.opacity(0.07)
    static let teal = Color(red: 0.0, green: 0.898, blue: 0.757)
    static let textPrimary = Color(white: 0.94)
    static let textMuted = Color(white: 0.58)
    static let textDim = Color(white: 0.40)
}

#Preview {
    ContentView()
}
