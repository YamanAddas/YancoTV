import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Android brand-asset generator. Run via Java 11+ single-file mode from the
 * repo root:
 *
 *   java tools/GenIcons.java
 *
 * Emits, into packages/android/app/src/main/res:
 *   drawable[-xhdpi]/ic_logo_mark.png            badge alone, square, transparent
 *   mipmap-DPI/ic_launcher_foreground.png        adaptive foreground, transparent
 *   mipmap-DPI/ic_launcher[_round].png           legacy flat icons on yanco_bg
 *   drawable[-xhdpi]/tv_banner.png               badge + wordmark on banner black
 *
 * ## Source
 *
 * `drawable-xhdpi/ic_logo.png` (640x360 RGBA), NOT `yancotv_logo.png` at the
 * repo root. The root file is larger (1536x1024) but it is a flat render on
 * an opaque grey backdrop with a wide green glow — `getbbox` on its alpha
 * returns the whole canvas. `ic_logo.png` is the background-removed version
 * of the same artwork and is the only source with a real alpha cut. The
 * badge in it is ~148x153, so the 432px adaptive foreground is a ~1.7x
 * upscale; checked at 1:1 and it holds.
 *
 * ## MK.29.5 — what this used to do wrong
 *
 * The previous version center-cropped the source to a square and scaled
 * that into every output. Three consequences, all shipped:
 *
 *   1. A center crop of a 16:9 badge+wordmark lockup is not the badge. It
 *      is a square of whatever sits mid-canvas, background included — which
 *      is why `ic_launcher_foreground.png` was an opaque 168x224 dark
 *      RECTANGLE with the hex sitting on it. Every adaptive mask then
 *      clipped that plate's corners instead of the artwork, and the hex's
 *      own points were cropped flush against the plate edge.
 *   2. The TV banner was center-cropped to 16:9, which threw away the
 *      wordmark and left the badge alone in a slot wide enough for both.
 *   3. Nothing emitted a badge-only asset at all, so square UI slots (the
 *      collapsed sidebar, the About tab, the notification small icon) had
 *      to fit the wide lockup and rendered the badge at a quarter size.
 *
 * The rule this file now encodes: <b>the asset follows the shape of the
 * slot.</b> Square or near-square gets the badge alone; wide gets the badge
 * plus the wordmark. Nothing is ever anisotropically scaled.
 */
public class GenIcons {

    /** @color/yanco_bg — baked into the legacy icons, which have no adaptive background layer. */
    private static final java.awt.Color YANCO_BG = new java.awt.Color(0x0B, 0x0F, 0x14);
    /** Sampled from the shipped tv_banner; slightly deeper than yanco_bg. */
    private static final java.awt.Color BANNER_BG = new java.awt.Color(0x06, 0x09, 0x0B);

    /**
     * The badge is symmetric about this column of the 640x360 source (its
     * top and bottom points both centre there). The wordmark's opening
     * flourish passes BEHIND the badge and re-emerges past its lower-right
     * edge, so no vertical cut separates the two — cropping at the badge's
     * right tip still leaves clipped stubs hanging off the corner.
     *
     * So the mark is built by mirroring the flourish-free LEFT half across
     * this axis, pixels and all. Masking alpha alone was tried first and
     * rejected: the flourish runs through the badge's outer glow where both
     * the mirrored silhouette and the original have coverage, leaving a
     * visible diagonal hairline at icon size. The cost is that the interior
     * leaf engraving becomes exactly symmetric; on a heraldic badge that
     * reads as deliberate. `ic_logo` itself is untouched and keeps the
     * artist's original asymmetry wherever the slot is wide.
     */
    private static final int HEX_AXIS_X = 137;

    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    /** Adaptive foreground canvas: 108dp per density bucket. */
    private static final int[] ADAPTIVE_PX = {108, 162, 216, 324, 432};
    /** Legacy launcher: 48dp per density bucket. */
    private static final int[] LEGACY_PX = {48, 72, 96, 144, 192};

    /**
     * Adaptive icons reserve the outer 18dp of the 108dp canvas as bleed —
     * only the inner 72dp is guaranteed visible, and the recommended
     * keyline for the artwork itself is 66dp. 66/108 = 0.61.
     */
    private static final double ADAPTIVE_COVERAGE = 0.61;
    /** Legacy icons are the final pixel grid: no mask eats the edges. */
    private static final double LEGACY_COVERAGE = 0.78;
    /** The mark IS the asset here, so it nearly fills its box; callers pad. */
    private static final double MARK_COVERAGE = 0.88;

    public static void main(String[] args) throws Exception {
        File repoRoot = new File(".").getCanonicalFile();
        File resRoot = new File(repoRoot, "packages/android/app/src/main/res");
        File src = new File(resRoot, "drawable-xhdpi/ic_logo.png");
        if (!src.exists()) {
            throw new IllegalStateException("ic_logo.png not found at " + src);
        }
        BufferedImage source = ImageIO.read(src);
        System.out.println("Loaded " + source.getWidth() + "x" + source.getHeight());

        BufferedImage mark = cleanHex(source);
        System.out.println("Clean hex: " + mark.getWidth() + "x" + mark.getHeight());

        // ── Square brand mark, for square slots in the app UI ──
        writePng(centred(mark, 96, MARK_COVERAGE, null), new File(resRoot, "drawable/ic_logo_mark.png"));
        writePng(centred(mark, 192, MARK_COVERAGE, null), new File(resRoot, "drawable-xhdpi/ic_logo_mark.png"));

        for (int i = 0; i < DENSITIES.length; i++) {
            File bucket = new File(resRoot, "mipmap-" + DENSITIES[i]);
            bucket.mkdirs();

            // ── Adaptive foreground: transparent, so the adaptive
            // <background> colour shows through and the launcher's mask
            // cuts the colour rather than the artwork.
            writePng(centred(mark, ADAPTIVE_PX[i], ADAPTIVE_COVERAGE, null),
                    new File(bucket, "ic_launcher_foreground.png"));

            // ── Legacy flat icons (pre-API 26): background baked in.
            BufferedImage flat = centred(mark, LEGACY_PX[i], LEGACY_COVERAGE, YANCO_BG);
            writePng(flat, new File(bucket, "ic_launcher.png"));
            writePng(round(flat), new File(bucket, "ic_launcher_round.png"));
        }

        // ── TV banner: a 16:9 slot, so it carries badge + wordmark. Uses
        // the untouched source, not the mirrored mark.
        BufferedImage full = trimToAlpha(source);
        writePng(banner(full, 320, 180), new File(resRoot, "drawable/tv_banner.png"));
        writePng(banner(full, 640, 360), new File(resRoot, "drawable-xhdpi/tv_banner.png"));

        System.out.println("Brand assets written to " + resRoot);
    }

    /** The hex badge alone: flourish removed by mirroring, tight-cropped. */
    private static BufferedImage cleanHex(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x <= HEX_AXIS_X; x++) {
                int argb = source.getRGB(x, y);
                out.setRGB(x, y, argb);
                int mirrorX = 2 * HEX_AXIS_X - x;
                if (mirrorX > HEX_AXIS_X && mirrorX < w) out.setRGB(mirrorX, y, argb);
            }
        }
        return trimToAlpha(out);
    }

    /** Crop to the tight bounding box of non-transparent pixels. */
    private static BufferedImage trimToAlpha(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) == 0) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return img;
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Fit {@code mark} into a square canvas at {@code coverage} of its edge,
     * centred on both axes. Scale is {@code min} of the two axes, so the
     * artwork keeps its aspect and is never stretched.
     */
    private static BufferedImage centred(BufferedImage mark, int canvas, double coverage, java.awt.Color bg) {
        double target = canvas * coverage;
        double scale = Math.min(target / mark.getWidth(), target / mark.getHeight());
        int w = Math.max(1, (int) Math.round(mark.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(mark.getHeight() * scale));
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out.createGraphics());
        if (bg != null) {
            g.setColor(bg);
            g.fillRect(0, 0, canvas, canvas);
        }
        g.drawImage(mark, (canvas - w) / 2, (canvas - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /** Badge + wordmark centred on the banner background, aspect preserved. */
    private static BufferedImage banner(BufferedImage full, int w, int h) {
        double scale = Math.min((w * 0.82) / full.getWidth(), (h * 0.72) / full.getHeight());
        int fw = (int) Math.round(full.getWidth() * scale);
        int fh = (int) Math.round(full.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(out.createGraphics());
        g.setColor(BANNER_BG);
        g.fillRect(0, 0, w, h);
        g.drawImage(full, (w - fw) / 2, (h - fh) / 2, fw, fh, null);
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
        Graphics2D g = quality(out.createGraphics());
        g.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, s, s));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    private static void writePng(BufferedImage img, File out) throws Exception {
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getPath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
