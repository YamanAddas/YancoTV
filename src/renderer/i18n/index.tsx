import { createContext, useCallback, useContext, useMemo, type ReactNode } from 'react';
import { en, type PluralForms, type StringKey } from './locales/en';
import { ar } from './locales/ar';

/**
 * Desktop i18n.
 *
 * Built on the platform's own `Intl` rather than a dependency. The one thing a
 * hand-rolled solution reliably gets wrong is plurals: Arabic has six CLDR
 * categories and selects between them by rules no `n === 1 ?` shortcut can
 * approximate. `Intl.PluralRules` ships in Chromium and Node, already knows
 * those rules, and stays correct for locales added later. The sibling Android
 * app shipped grammatically wrong Arabic counts across its whole UI until
 * MB-339 for exactly this reason.
 *
 * Missing translations fall back to English per key, so a partially translated
 * locale renders a mixed UI instead of blanks.
 */

export const LOCALES = {
  en: { label: 'English', dir: 'ltr' as const, strings: en },
  ar: { label: 'العربية', dir: 'rtl' as const, strings: ar },
};

export type LocaleCode = keyof typeof LOCALES;

export function isLocaleCode(v: string): v is LocaleCode {
  return Object.prototype.hasOwnProperty.call(LOCALES, v);
}

type Vars = Record<string, string | number>;

function isPlural(v: unknown): v is PluralForms {
  return typeof v === 'object' && v !== null && 'other' in v;
}

/** Replace `{name}` placeholders. Unknown placeholders are left as written. */
function interpolate(template: string, vars?: Vars): string {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in vars ? String(vars[name]) : whole,
  );
}

/**
 * Pick the plural form for `count` in `locale`.
 *
 * Falls back through the CLDR category to `other`, because a locale file may
 * legitimately omit a category the rules can still return — English has no
 * `few`, and an Arabic file part-way through translation may not have `two`
 * yet. Returning `other` renders something sensible instead of nothing.
 */
function selectPlural(forms: PluralForms, count: number, locale: LocaleCode): string {
  let category: Intl.LDMLPluralRule = 'other';
  try {
    category = new Intl.PluralRules(locale).select(count);
  } catch {
    // An unknown locale tag should degrade, not crash the whole render.
  }
  // `zero` is not returned by Intl for most locales, so honour an explicit
  // zero form when the count really is 0 — "No channels are hidden" reads far
  // better than "0 channels are hidden".
  if (count === 0 && forms.zero !== undefined) return forms.zero;
  return forms[category] ?? forms.other;
}

/**
 * Translate, as a pure function.
 *
 * Exported separately from the hook so the rules — plural selection, per-key
 * English fallback, interpolation — can be tested directly, without mounting a
 * React tree. The provider is a thin wrapper over this.
 */
export function translate(locale: LocaleCode, key: StringKey, vars?: Vars): string {
  const table = LOCALES[locale]?.strings as Partial<typeof en> | undefined;
  // Per-KEY fallback, not per-file: a partially translated locale shows its
  // translated strings and English for the rest, never a blank.
  const value = table?.[key] ?? en[key];

  if (isPlural(value)) {
    const count = typeof vars?.count === 'number' ? vars.count : 0;
    return interpolate(selectPlural(value, count, locale), vars);
  }
  return interpolate(value as string, vars);
}

interface I18nValue {
  locale: LocaleCode;
  dir: 'ltr' | 'rtl';
  t: (key: StringKey, vars?: Vars) => string;
  /** Locale-aware number formatting — Arabic-Indic digits where appropriate. */
  formatNumber: (n: number) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({
  locale,
  children,
}: {
  locale: LocaleCode;
  children: ReactNode;
}) {
  const dir = LOCALES[locale]?.dir ?? 'ltr';

  const t = useCallback(
    (key: StringKey, vars?: Vars): string => translate(locale, key, vars),
    [locale],
  );

  const formatNumber = useCallback(
    (n: number) => {
      try {
        return new Intl.NumberFormat(locale).format(n);
      } catch {
        return String(n);
      }
    },
    [locale],
  );

  const value = useMemo<I18nValue>(
    () => ({ locale, dir, t, formatNumber }),
    [locale, dir, t, formatNumber],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/**
 * Access the translator.
 *
 * Throws outside a provider rather than silently returning English: a component
 * rendered outside the tree would otherwise look translated in review and be
 * permanently English at runtime.
 */
export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useI18n must be used inside <I18nProvider>');
  return ctx;
}

/** Shorthand for the common case. */
export function useT(): I18nValue['t'] {
  return useI18n().t;
}
