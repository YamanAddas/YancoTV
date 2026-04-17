// Generate src/assets/icon.ico from src/assets/icon.png.
// Trims surrounding transparent/white space, then crops a square from the
// left edge (the hex "Y" mark) so the icon fills the canvas edge-to-edge
// instead of showing the wide wordmark as a tiny strip.

const path = require('path');
const fs = require('fs');
const sharp = require('sharp');
const pngToIco = require('png-to-ico').default;

const SRC = path.resolve(__dirname, '../src/assets/icon.png');
const OUT = path.resolve(__dirname, '../src/assets/icon.ico');
const SIZES = [16, 24, 32, 48, 64, 128, 256];
const MARGIN_RATIO = 0; // fill the icon canvas edge-to-edge

async function main() {
  const input = sharp(SRC).ensureAlpha();
  const { data, info } = await input
    .raw()
    .toBuffer({ resolveWithObject: true });
  const { width, height, channels } = info;

  // Find tight bounding box of non-white, non-transparent pixels.
  let minX = width, minY = height, maxX = -1, maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * channels;
      const r = data[i], g = data[i + 1], b = data[i + 2];
      const a = channels === 4 ? data[i + 3] : 255;
      // Treat near-white and fully-transparent as background.
      const isWhite = r > 245 && g > 245 && b > 245;
      if (a < 10 || isWhite) continue;
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) throw new Error('No content found in icon.png');

  const cropW = maxX - minX + 1;
  const cropH = maxY - minY + 1;
  console.log(`content bbox: ${cropW}x${cropH} at (${minX},${minY})`);

  // The source logo is a hex "Y" mark on the left + "YancoTV" wordmark on the
  // right. For the app icon we want just the hex. Detect where the hex ends
  // by scanning column density within the bbox: walk from the left peak and
  // stop at the first sustained low-density gap (the space between hex and
  // wordmark). That gives us the hex's right edge; crop square from there.
  const colDensity = new Array(cropW).fill(0);
  for (let x = 0; x < cropW; x++) {
    for (let y = 0; y < cropH; y++) {
      const i = ((minY + y) * width + (minX + x)) * channels;
      const r = data[i], g = data[i + 1], b = data[i + 2];
      const a = channels === 4 ? data[i + 3] : 255;
      const isWhite = r > 245 && g > 245 && b > 245;
      if (a >= 10 && !isWhite) colDensity[x]++;
    }
  }
  // Walk right from the leftmost dense column (the hex) and stop at the
  // local minimum before density rises again (that's the gap before the
  // wordmark begins). The hex's right edge is the peak just before that gap.
  const peak = Math.max(...colDensity);
  let hexPeakX = 0;
  for (let x = 0; x < cropW; x++) {
    if (colDensity[x] === peak) { hexPeakX = x; break; }
  }
  let valleyX = cropW;
  let valleyVal = Infinity;
  for (let x = hexPeakX; x < cropW; x++) {
    if (colDensity[x] < valleyVal) {
      valleyVal = colDensity[x];
      valleyX = x;
    } else if (colDensity[x] > valleyVal * 2 && colDensity[x] > peak * 0.3) {
      // Density climbing back up — the wordmark is starting. Lock in valley.
      break;
    }
  }
  // Hex right edge: walk back from valley to where density last exceeded a
  // "hex body" threshold — that's the true edge of the hex shape.
  const hexEdgeThreshold = peak * 0.55;
  let hexRight = hexPeakX;
  for (let x = hexPeakX; x <= valleyX; x++) {
    if (colDensity[x] >= hexEdgeThreshold) hexRight = x;
  }
  const hexWidth = hexRight + 1;

  // Also tighten vertical bounds to just the hex (top/bottom of bbox may
  // include flourishes that extend past the hex itself).
  const rowDensity = new Array(cropH).fill(0);
  for (let y = 0; y < cropH; y++) {
    for (let x = 0; x < hexWidth; x++) {
      const i = ((minY + y) * width + (minX + x)) * channels;
      const r = data[i], g = data[i + 1], b = data[i + 2];
      const a = channels === 4 ? data[i + 3] : 255;
      const isWhite = r > 245 && g > 245 && b > 245;
      if (a >= 10 && !isWhite) rowDensity[y]++;
    }
  }
  const rowPeak = Math.max(...rowDensity);
  const rowThresh = rowPeak * 0.08;
  let hexTop = 0, hexBottom = cropH - 1;
  for (let y = 0; y < cropH; y++) { if (rowDensity[y] >= rowThresh) { hexTop = y; break; } }
  for (let y = cropH - 1; y >= 0; y--) { if (rowDensity[y] >= rowThresh) { hexBottom = y; break; } }
  const hexHeight = hexBottom - hexTop + 1;

  console.log(`hex detected: ${hexWidth}x${hexHeight} at (${minX},${minY + hexTop})`);

  const cropped = await sharp(SRC)
    .extract({ left: minX, top: minY + hexTop, width: hexWidth, height: hexHeight })
    .toBuffer();

  // Build each target size: fit cropped content into a square canvas with margin.
  const pngs = await Promise.all(
    SIZES.map(async (size) => {
      const margin = Math.round(size * MARGIN_RATIO);
      const inner = size - margin * 2;
      const resized = await sharp(cropped)
        .resize(inner, inner, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .toBuffer();
      return await sharp({
        create: {
          width: size,
          height: size,
          channels: 4,
          background: { r: 0, g: 0, b: 0, alpha: 0 },
        },
      })
        .composite([{ input: resized, gravity: 'center' }])
        .png()
        .toBuffer();
    }),
  );

  const ico = await pngToIco(pngs);
  fs.writeFileSync(OUT, ico);
  console.log(`wrote ${OUT} (${ico.length} bytes, ${SIZES.length} sizes)`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
