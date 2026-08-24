# YancoTV — Privacy Policy

**This file is not the policy.** It is a pointer, so that nobody edits a copy
that nothing reads.

## Where the live policy is

| Copy | Location |
|---|---|
| Source of truth (all four languages) | `assets/legal-strings.js` in the [yancotv-releases](https://github.com/YamanAddas/yancotv-releases) repo, keys `pv.*` |
| Published | <https://yamanaddas.github.io/yancotv-releases/privacy.html> |
| Bundled in the app | `packages/android/app/src/main/res/raw{,-ar,-es,-fr}/privacy_policy.txt` — Settings → About → Privacy policy |

The bundled copies are **generated**, not written:

```bash
node scripts/gen-legal-raw.mjs
```

That reads the site's dictionary and rewrites all eight text files, so the
published page and the text inside the app cannot disagree. Change the wording
on the site, re-run it, commit the result.

## What happened to the old draft

Until 2026-08-24 this file held a 148-line draft dated 2026-04-29, written
around GDPR / CCPA / KCDPA disclosure clauses for a Kentucky-based developer.
It had drifted: it described a shorter policy than the one actually shipping,
and it instructed you to update a `PRIVACY_POLICY_URL` constant that MB-372
had already deleted — the documents now open in an in-app viewer rather than a
browser, because Fire TV ships no browser to open them in.

It is not lost. `git show d75047aa:PRIVACY.md` is the last version of it.

## The part of that draft still worth acting on

The live policy is written to be true and readable, not to satisfy a store
reviewer. Before any public store submission:

- Have a lawyer in your jurisdiction read it. Nothing here is legal advice.
- Google Play's Data Safety form and Apple's privacy nutrition labels ask
  questions this document does not answer in their vocabulary — see
  [CONTENT_RATING.md](CONTENT_RATING.md).
- Decide whether the app needs an explicit governing-law and dispute-venue
  clause. The old draft selected Kentucky law with a small-claims carve-out;
  the current [TERMS.md](TERMS.md) pointer says the same.
