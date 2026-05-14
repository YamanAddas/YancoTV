import path from 'path';
import fs from 'fs';
import { spawn, execFile } from 'child_process';
import log from 'electron-log/main';
import { findFfmpegPath } from './ffmpeg-path';
import { confinePath } from '../utils/safe-path';

/**
 * Extract embedded text subtitles from a finished video file via ffmpeg.
 *
 * Flow:
 *   1. `ffmpeg -i file` prints stream info to stderr (and exits non-zero
 *      because we gave no output). We parse that for subtitle tracks and
 *      their codec + language tag.
 *   2. Skip image-based tracks (PGS, DVBSUB, HDMV_PGS_SUBTITLE, DVD_SUBTITLE)
 *      — ffmpeg can't convert those to SRT.
 *   3. For each text track: `ffmpeg -i file -map 0:s:{idx} -c:s srt out.srt`.
 *
 * Errors are logged, not thrown — subtitle extraction is best-effort and must
 * never fail a completed download.
 */

export interface SubtitleStream {
  /** 0-based index among subtitle streams (what ffmpeg's `-map 0:s:N` wants). */
  subtitleIndex: number;
  codec: string;
  /** ISO-639 language tag if the container carried one; falls back to 'und'. */
  language: string;
  /** true when the codec is text-based and convertible to SRT. */
  textBased: boolean;
}

// Image-based subtitle codecs ffmpeg can't transcode to SRT. Anything not in
// this list is assumed text-based (srt, subrip, mov_text, ass, ssa, webvtt…).
const IMAGE_CODECS = new Set([
  'dvd_subtitle',
  'dvdsub',
  'dvbsub',
  'dvb_subtitle',
  'hdmv_pgs_subtitle',
  'pgssub',
  'xsub',
]);

/**
 * Parse the `Stream #0:N[0xHEX](lang): Subtitle: codec …` lines ffmpeg emits
 * on stderr. Exported so it's testable without spawning ffmpeg.
 */
export function parseSubtitleStreams(ffmpegStderr: string): SubtitleStream[] {
  const out: SubtitleStream[] = [];
  let subIdx = 0;
  const lines = ffmpegStderr.split(/\r?\n/);
  // Matches e.g. "  Stream #0:2(eng): Subtitle: subrip (default)"
  //   or        "  Stream #0:3: Subtitle: hdmv_pgs_subtitle"
  const re = /Stream\s+#\d+:\d+(?:\[[^\]]+\])?(?:\(([^)]*)\))?:\s*Subtitle:\s*([a-z0-9_]+)/i;
  for (const line of lines) {
    const m = line.match(re);
    if (!m) continue;
    const language = (m[1] || 'und').toLowerCase();
    const codec = m[2].toLowerCase();
    out.push({
      subtitleIndex: subIdx++,
      codec,
      language,
      textBased: !IMAGE_CODECS.has(codec),
    });
  }
  return out;
}

/**
 * Decide the on-disk filename for an extracted subtitle, Kodi-style:
 *   movie.en.srt, movie.fr.srt, movie.en.2.srt (when a language repeats).
 * Exported for testing.
 */
export function subtitleFilename(
  baseNoExt: string,
  lang: string,
  used: Set<string>,
): string {
  const safeLang = /^[a-z]{2,3}$/i.test(lang) ? lang.toLowerCase() : 'und';
  let candidate = `${baseNoExt}.${safeLang}.srt`;
  let n = 2;
  while (used.has(candidate.toLowerCase())) {
    candidate = `${baseNoExt}.${safeLang}.${n}.srt`;
    n++;
  }
  used.add(candidate.toLowerCase());
  return candidate;
}

function probe(ffmpegPath: string, videoPath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(
      ffmpegPath,
      ['-hide_banner', '-i', videoPath],
      { timeout: 30_000, maxBuffer: 4 * 1024 * 1024, windowsHide: true },
      (err, _stdout, stderr) => {
        // ffmpeg exits non-zero when there's no output file specified; that's
        // expected here — we only ever wanted the stderr dump.
        if (stderr && stderr.length > 0) resolve(stderr);
        else reject(err ?? new Error('ffmpeg produced no output'));
      },
    );
  });
}

function extractOne(
  ffmpegPath: string,
  videoPath: string,
  subtitleIndex: number,
  outPath: string,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const args = [
      '-hide_banner',
      '-loglevel', 'error',
      '-y',
      '-i', videoPath,
      '-map', `0:s:${subtitleIndex}`,
      '-c:s', 'srt',
      outPath,
    ];
    // ffmpegPath has been validated by the caller (extractEmbeddedSubtitles)
    // as an absolute, on-disk path — see the guard there. Semgrep's
    // detect-child-process rule fires on any function-arg path going
    // into spawn; the marker documents that this site is gated.
    // nosemgrep: javascript.lang.security.detect-child-process.detect-child-process
    const child = spawn(ffmpegPath, args, { windowsHide: true });
    let stderr = '';
    child.stderr.on('data', (d: Buffer) => {
      stderr += d.toString('utf8');
    });
    const onAbort = (): void => {
      try {
        child.kill('SIGKILL');
      } catch {
        // ignored
      }
    };
    if (signal) {
      if (signal.aborted) onAbort();
      else signal.addEventListener('abort', onAbort, { once: true });
    }
    child.on('error', (err) => {
      if (signal) signal.removeEventListener('abort', onAbort);
      reject(err);
    });
    child.on('close', (code) => {
      if (signal) signal.removeEventListener('abort', onAbort);
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg exited ${code}: ${stderr.trim().slice(0, 300)}`));
    });
  });
}

export interface ExtractResult {
  attempted: number;
  extracted: string[];
  skipped: Array<{ language: string; codec: string; reason: string }>;
}

/**
 * Extract every text-based embedded subtitle track from `videoPath` next to it.
 * Returns a summary. Never throws — logs + returns `{ attempted: 0, ... }` if
 * ffmpeg is missing or the probe fails.
 */
export async function extractEmbeddedSubtitles(
  videoPath: string,
  signal?: AbortSignal,
): Promise<ExtractResult> {
  const result: ExtractResult = { attempted: 0, extracted: [], skipped: [] };
  if (!fs.existsSync(videoPath)) return result;

  const ffmpegPath = findFfmpegPath();
  if (!ffmpegPath) {
    log.warn('Skipping subtitle extraction: ffmpeg not available');
    return result;
  }
  // `findFfmpegPath` resolves to either the bundled binary or a path
  // the user configured in Settings. Validate it's a real, absolute
  // path on disk before handing it to `spawn` — a relative or
  // non-existent path would otherwise let `child_process` fall back to
  // PATH lookup, which broadens the trust surface unnecessarily.
  if (!path.isAbsolute(ffmpegPath) || !fs.existsSync(ffmpegPath)) {
    log.warn(`Skipping subtitle extraction: invalid ffmpeg path '${ffmpegPath}'`);
    return result;
  }

  let stderr: string;
  try {
    stderr = await probe(ffmpegPath, videoPath);
  } catch (err) {
    log.warn(`Subtitle probe failed for ${videoPath}:`, err);
    return result;
  }

  const streams = parseSubtitleStreams(stderr);
  if (streams.length === 0) return result;

  const dir = path.dirname(videoPath);
  const baseNoExt = path.basename(videoPath, path.extname(videoPath));
  const used = new Set<string>();

  for (const s of streams) {
    if (!s.textBased) {
      result.skipped.push({
        language: s.language,
        codec: s.codec,
        reason: 'image-based subtitle — cannot convert to SRT',
      });
      continue;
    }
    result.attempted++;
    const outName = subtitleFilename(baseNoExt, s.language, used);
    let outPath: string;
    try {
      outPath = confinePath(dir, outName);
    } catch (err) {
      result.skipped.push({
        language: s.language,
        codec: s.codec,
        reason: `unsafe output path: ${String((err as Error).message)}`,
      });
      continue;
    }
    try {
      await extractOne(ffmpegPath, videoPath, s.subtitleIndex, outPath, signal);
      // Empty output means the track had no parseable cues; delete it.
      if (fs.existsSync(outPath) && fs.statSync(outPath).size === 0) {
        fs.unlinkSync(outPath);
        result.skipped.push({
          language: s.language,
          codec: s.codec,
          reason: 'extracted file was empty',
        });
        continue;
      }
      result.extracted.push(outPath);
    } catch (err) {
      log.warn(`Subtitle extraction failed (${s.language}/${s.codec}):`, err);
      // Clean up half-written output
      try {
        if (fs.existsSync(outPath)) fs.unlinkSync(outPath);
      } catch {
        // ignored
      }
      result.skipped.push({
        language: s.language,
        codec: s.codec,
        reason: String((err as Error).message ?? err),
      });
    }
  }

  return result;
}
