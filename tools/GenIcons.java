import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * One-off icon generator: reads yancotv_logo.png at repo root and emits the
 * Android mipmap buckets + TV banner. Run via Java 11+ single-file mode:
 *   java tools/GenIcons.java
 *
 * The logo is center-cropped to a square for launcher icons and to 16:9
 * for the TV banner. No padding — matches desktop's fill-edge treatment.
 */
public class GenIcons {
    public static void main(String[] args) throws Exception {
        File repoRoot = new File(".").getCanonicalFile();
        File src = new File(repoRoot, "yancotv_logo.png");
        if (!src.exists()) {
            throw new IllegalStateException("yancotv_logo.png not found at " + src);
        }
        BufferedImage source = ImageIO.read(src);
        System.out.println("Loaded " + source.getWidth() + "x" + source.getHeight());

        File resRoot = new File(repoRoot, "packages/android/app/src/main/res");

        // ── Launcher icons: center-crop to square, scale per density ──
        int squareSide = Math.min(source.getWidth(), source.getHeight());
        int squareX = (source.getWidth() - squareSide) / 2;
        int squareY = (source.getHeight() - squareSide) / 2;
        BufferedImage square = source.getSubimage(squareX, squareY, squareSide, squareSide);

        String[] densityNames = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] densityPx = {48, 72, 96, 144, 192};
        for (int i = 0; i < densityNames.length; i++) {
            File bucket = new File(resRoot, "mipmap-" + densityNames[i]);
            bucket.mkdirs();
            writePng(scale(square, densityPx[i], densityPx[i]), new File(bucket, "ic_launcher.png"));
            writePng(round(scale(square, densityPx[i], densityPx[i])), new File(bucket, "ic_launcher_round.png"));
        }

        // ── Adaptive icon foreground (API 26+) ──
        // Android composites a 108dp × 108dp canvas but keeps the outer
        // 18dp as "bleed" — only the inner 72×72 is guaranteed visible.
        // To still fill the visible icon we scale the logo to 72 of the
        // 108 canvas (logo fills the safe area, corners stay transparent).
        // 432px (at xxxhdpi foreground density) * 72/108 = 288px logo.
        File adaptForeDir = new File(resRoot, "mipmap-xxxhdpi");
        adaptForeDir.mkdirs();
        BufferedImage fg = new BufferedImage(432, 432, BufferedImage.TYPE_INT_ARGB);
        Graphics2D fgG = fg.createGraphics();
        fgG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        fgG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BufferedImage safeLogo = scale(square, 288, 288);
        fgG.drawImage(safeLogo, 72, 72, null);
        fgG.dispose();
        writePng(fg, new File(adaptForeDir, "ic_launcher_foreground.png"));

        // ── TV banner: center-crop to 16:9 then scale to 320×180 (xhdpi) ──
        double targetRatio = 320.0 / 180.0;
        double sourceRatio = source.getWidth() / (double) source.getHeight();
        int bx, by, bw, bh;
        if (sourceRatio > targetRatio) {
            bh = source.getHeight();
            bw = (int) (bh * targetRatio);
            bx = (source.getWidth() - bw) / 2;
            by = 0;
        } else {
            bw = source.getWidth();
            bh = (int) (bw / targetRatio);
            bx = 0;
            by = (source.getHeight() - bh) / 2;
        }
        BufferedImage bannerCrop = source.getSubimage(bx, by, bw, bh);
        File bannerDir = new File(resRoot, "drawable-xhdpi");
        bannerDir.mkdirs();
        writePng(scale(bannerCrop, 320, 180), new File(bannerDir, "tv_banner.png"));

        System.out.println("Icons + banner written to " + resRoot);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * Round-icon variant — some launchers (notably older Samsung shells)
     * ignore the adaptive icon and fall back to the legacy round PNG.
     * We pre-clip to a circle so the launcher sees an already-rounded
     * image instead of a square packed inside a circle mask.
     */
    private static BufferedImage round(BufferedImage src) {
        int s = src.getWidth();
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, s, s);
        g.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, s, s));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void writePng(BufferedImage img, File out) throws Exception {
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getPath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
