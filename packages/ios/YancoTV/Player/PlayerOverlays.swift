import SwiftUI

/// Port of `VodPlayerChrome.kt`'s buffering overlay.
///
/// The Android loader is static — its rotation was deferred. Here it
/// breathes, which the Kotlin KDoc lists as wanted-but-not-done rather
/// than deliberately omitted. It is suppressed under Reduce Motion.
struct BufferingOverlay: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var pulse = false

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        ZStack {
            palette.BackgroundDeep.opacity(0.82).ignoresSafeArea()

            VStack(spacing: 0) {
                badge
                Spacer().frame(height: 24)
                HexChip(label: "BUFFERING", tone: palette.Accent)
                Spacer().frame(height: 18)
                Text("Tuning the stream")
                    .font(.system(size: compact ? 26 : 34, weight: .bold))
                    .foregroundStyle(palette.TextPrimary)
                    .multilineTextAlignment(.center)
                Spacer().frame(height: 10)
                Text("Negotiating the best bitrate for your connection. This usually takes a moment.")
                    .font(.system(size: compact ? 14 : 16))
                    .foregroundStyle(palette.TextSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, compact ? Space.xxl : 56)
            .padding(.vertical, compact ? Space.xxxl : 48)
            .frame(maxWidth: 560)
        }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }

    private var badge: some View {
        HexRowShape(corner: 20)
            .fill(palette.BackgroundRaised)
            .frame(width: 96, height: 96)
            .overlay { HexRowShape(corner: 20).stroke(palette.Accent, lineWidth: 2) }
            .overlay {
                HexRowShape(corner: 12)
                    .fill(palette.Accent)
                    .frame(width: 52, height: 52)
                    .overlay {
                        Text("Y")
                            .font(.system(size: 26, weight: .black))
                            .foregroundStyle(palette.BackgroundDeep)
                    }
            }
            .opacity(pulse ? 0.72 : 1)
    }
}

/// Port of `VodPlayerChrome.kt`'s error overlay.
///
/// Scrollable on purpose (MB-300): the un-scrolled stack measured 564dp
/// against a 444dp budget and Compose handed the action row zero height,
/// leaving the user with no way out of a stream error.
struct PlayerErrorOverlay: View {
    @Environment(\.yancoPalette) private var palette
    @Environment(\.horizontalSizeClass) private var sizeClass

    let message: String
    let title: String
    let onClose: () -> Void

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        ZStack {
            palette.BackgroundDeep.opacity(0.90).ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    icon
                    Spacer().frame(height: 20)
                    HexChip(label: "ERR · PLAYBACK", tone: palette.Error)
                    Spacer().frame(height: 14)
                    Text("Couldn't open this stream")
                        .font(.system(size: compact ? 22 : 26, weight: .bold))
                        .foregroundStyle(palette.TextPrimary)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                    Spacer().frame(height: 10)
                    Text(message)
                        .font(.system(size: compact ? 14 : 16))
                        .foregroundStyle(palette.TextSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer().frame(height: 24)
                    diagnostics
                    Spacer().frame(height: 28)
                    HexCta(title: "Close", symbol: "xmark", primary: true) { onClose() }
                        .fixedSize()
                }
                .padding(.horizontal, compact ? Space.xxl : 56)
                .padding(.vertical, compact ? Space.xxxl : 48)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var icon: some View {
        HexRowShape(corner: 30)
            .fill(palette.BackgroundRaised)
            .frame(width: 96, height: 96)
            .overlay { HexRowShape(corner: 30).stroke(palette.Error.opacity(0.6), lineWidth: 2) }
            .overlay {
                // MB-298 — density-locked. This glyph must not scale with
                // Dynamic Type or it overflows its hex at large sizes.
                Text("×")
                    .font(.system(size: 58, weight: .bold))
                    .foregroundStyle(palette.Error)
            }
    }

    private var diagnostics: some View {
        VStack(alignment: .leading, spacing: 4) {
            diagnosticRow("stream", title)
            diagnosticRow("engine", "AVPlayer")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(palette.BackgroundRaised, in: HexRowShape(corner: 10))
        .overlay { HexRowShape(corner: 10).stroke(palette.BorderSubtle, lineWidth: 1) }
    }

    private func diagnosticRow(_ key: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Text("\(key):")
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(palette.TextMuted)
                .frame(width: 76, alignment: .leading)
            Text(value.isEmpty ? "—" : value)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(palette.TextPrimary)
                .lineLimit(2)
        }
    }
}

/// Small pill used by both overlays.
struct HexChip: View {
    @Environment(\.yancoPalette) private var palette
    let label: String
    let tone: Color

    var body: some View {
        Text(label)
            .font(.system(size: 12, weight: .semibold))
            .tracking(1.6)
            .foregroundStyle(tone)
            .padding(.horizontal, 12)
            .frame(height: 26)
            .background(palette.BackgroundRaised, in: HexRowShape(corner: 6))
            .overlay { HexRowShape(corner: 6).stroke(palette.BorderSubtle, lineWidth: 1) }
    }
}
