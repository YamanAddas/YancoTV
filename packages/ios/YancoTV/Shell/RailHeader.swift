import SwiftUI

/// Port of `HomeContent.RailHeader`.
///
/// Accent overline, a 2pt gap, then the title and its caption on a shared
/// bottom alignment — the caption carries a 3pt bottom nudge so the two
/// baselines line up despite the 23pt/12pt size gap.
struct RailHeader: View {
    @Environment(\.yancoPalette) private var palette

    let eyebrow: String
    let title: String
    let caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(eyebrow)
                .yancoType(YancoType.overline)
                .foregroundStyle(palette.Accent)

            Spacer().frame(height: Space.xxs)

            HStack(alignment: .bottom, spacing: Space.md) {
                Text(title)
                    .yancoType(YancoType.titleL)
                    .foregroundStyle(palette.TextPrimary)
                Text(caption)
                    .yancoType(YancoType.caption)
                    .foregroundStyle(palette.TextMuted)
                    .padding(.bottom, 3)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
