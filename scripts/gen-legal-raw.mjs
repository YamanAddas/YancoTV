/**
 * Generates the app's bundled privacy policy and terms of use, in every
 * language the app ships, from the SAME dictionary the website renders.
 *
 *   node scripts/gen-legal-raw.mjs [path-to-legal-strings.js]
 *
 * The point is that "the copy in the app and the copy on the site are the
 * same document" is a fact enforced by a script, not a promise in a comment.
 * Re-run it whenever the site's legal text changes and commit the result.
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SRC = process.argv[2] || "D:/yancotv-releases/assets/legal-strings.js";
const RES = join(ROOT, "packages/android/app/src/main/res");

// raw/ is the default (English); the rest are locale-qualified.
const DIRS = { en: "raw", ar: "raw-ar", es: "raw-es", fr: "raw-fr" };

const win = {};
new Function("window", readFileSync(SRC, "utf8"))(win);
const D = win.YANCO_PAGE_DICT;
if (!D) throw new Error("YANCO_PAGE_DICT not found in " + SRC);

/** Markup in the dictionary is for the web. Flatten it to readable text. */
const plain = (s) =>
  s
    .replace(/<a href='mailto:([^']*)'>([^<]*)<\/a>/g, "$2")
    // keep the destination when the link text isn't itself the address
    .replace(/<a href='([^']*)'>([^<]*)<\/a>/g, (_m, url, text) =>
      url.includes(text.replace(/^https?:\/\//, "")) ? text : `${text} (${url})`)
    .replace(/<\/?strong>/g, "")
    .replace(/<[^>]+>/g, "")
    .trim();

// Paragraphs are emitted unwrapped on purpose: the viewer wraps, and hard
// wrapping at a fixed column mangles Arabic.
const doc = (lines) => lines.filter((l) => l !== null).join("\n").replace(/\n{3,}/g, "\n\n").trim() + "\n";

for (const [lang, dir] of Object.entries(DIRS)) {
  const t = D[lang];
  if (!t) throw new Error("missing language: " + lang);
  const stamp = `${t["legal.updated"]}: ${t["legal.date"]}`;

  const privacy = doc([
    plain(t["pv.title"]), "", stamp, "", plain(t["pv.lede"]), "",
    plain(t["pv.h1"]), "", plain(t["pv.p1"]), "",
    plain(t["pv.h2"]), "",
    ...["pv.l1", "pv.l2", "pv.l3", "pv.l4"].flatMap((k) => ["- " + plain(t[k]), ""]),
    plain(t["pv.h3"]), "", plain(t["pv.p3"]), "",
    plain(t["pv.h4"]), "", plain(t["pv.p4"]), "",
    plain(t["pv.h5"]), "", plain(t["pv.p5"]), "",
    plain(t["pv.h6"]), "", plain(t["pv.p6"]), "",
    plain(t["pv.h7"]), "", plain(t["pv.p7"]),
  ]);

  const terms = doc([
    plain(t["tm.title"]), "", stamp, "", plain(t["tm.lede"]), "",
    ...[1, 2, 3, 4, 5, 6, 7].flatMap((n) => [
      `${n}. ${plain(t["tm." + n + "h"])}`, "", plain(t["tm." + n + "p"]), "",
    ]),
  ]);

  mkdirSync(join(RES, dir), { recursive: true });
  writeFileSync(join(RES, dir, "privacy_policy.txt"), privacy, "utf8");
  writeFileSync(join(RES, dir, "terms_of_use.txt"), terms, "utf8");
  console.log(`${dir}: privacy ${privacy.length}B, terms ${terms.length}B`);
}
