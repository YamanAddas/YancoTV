import type { Strings } from './en';

/**
 * Arabic. Partial by design — anything absent falls back to English, so a
 * half-finished translation ships a mixed UI rather than blank labels.
 *
 * Note the plural entries. Arabic has SIX categories (zero, one, two, few,
 * many, other) and `Intl.PluralRules('ar')` selects between them by the actual
 * CLDR rules: 0 → zero, 1 → one, 2 → two, 3–10 → few, 11–99 → many, 100+ →
 * other. A `count === 1 ? singular : plural` shortcut produces grammatically
 * wrong Arabic for most numbers — the sibling Android app shipped exactly that
 * bug for every count in the UI until MB-339.
 */
export const ar: Partial<Strings> = {
  // ── navigation / shell ────────────────────────────────────────────────
  'nav.home': 'الرئيسية',
  'nav.liveTv': 'البث المباشر',
  'nav.guide': 'دليل البرامج',
  'nav.movies': 'أفلام',
  'nav.series': 'مسلسلات',
  'nav.favorites': 'المفضلة',
  'nav.recordings': 'التسجيلات',
  'nav.downloads': 'التنزيلات',
  'nav.settings': 'الإعدادات',
  'nav.search': 'بحث',
  'nav.searchPlaceholder': 'ابحث عن قنوات أو أفلام أو مسلسلات…',

  // ── common actions ────────────────────────────────────────────────────
  'action.play': 'تشغيل',
  'action.cancel': 'إلغاء',
  'action.save': 'حفظ',
  'action.close': 'إغلاق',
  'action.remove': 'إزالة',
  'action.retry': 'إعادة المحاولة',
  'action.refresh': 'تحديث',
  'action.back': 'رجوع',
  'action.unlock': 'فتح القفل',

  // ── parental ──────────────────────────────────────────────────────────
  'parental.enterPin': 'أدخل الرمز',
  'parental.unlockTitle': 'فتح قفل "{title}"',
  'parental.pinTooShort': 'الرمز يجب أن يكون 4 أرقام على الأقل',
  'parental.pinIncorrect': 'الرمز غير صحيح',
  'parental.verifyFailed': 'فشل التحقق',
  'parental.enterToContinue': 'أدخل الرمز للمتابعة',
  'parental.checking': 'جارٍ التحقق…',
  'parental.verify': 'تحقق',

  // ── settings: general ─────────────────────────────────────────────────
  'settings.title': 'الإعدادات',
  'settings.general': 'عام',
  'settings.language': 'اللغة',
  'settings.languageDesc': 'لغة الواجهة. تُطبَّق فوراً.',
  'settings.startPage': 'صفحة البداية',
  'settings.startPageDesc': 'الصفحة التي تظهر عند فتح البرنامج',
  'settings.theme': 'المظهر',
  'settings.startup': 'بدء التشغيل',
  'settings.pinRequired': 'الإعدادات محمية برمز',

  // ── empty / error states ──────────────────────────────────────────────
  'state.loading': 'جارٍ التحميل…',
  'state.noResults': 'لا توجد نتائج',
  'state.noSources': 'لا توجد مصادر بعد',
  'state.error': 'حدث خطأ ما',

  // ── counted things ────────────────────────────────────────────────────
  'count.channels': {
    zero: 'لا توجد قنوات',
    one: 'قناة واحدة',
    two: 'قناتان',
    few: '{count} قنوات',
    many: '{count} قناة',
    other: '{count} قناة',
  },
  'count.results': {
    zero: 'لا توجد نتائج',
    one: 'نتيجة واحدة',
    two: 'نتيجتان',
    few: '{count} نتائج',
    many: '{count} نتيجة',
    other: '{count} نتيجة',
  },
  'count.recordings': {
    zero: 'لا توجد تسجيلات',
    one: 'تسجيل واحد',
    two: 'تسجيلان',
    few: '{count} تسجيلات',
    many: '{count} تسجيلاً',
    other: '{count} تسجيل',
  },
  'count.hiddenChannels': {
    zero: 'لا توجد قنوات مخفية',
    one: 'قناة واحدة مخفية',
    two: 'قناتان مخفيتان',
    few: '{count} قنوات مخفية',
    many: '{count} قناة مخفية',
    other: '{count} قناة مخفية',
  },
};
