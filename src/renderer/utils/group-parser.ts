/**
 * Smart IPTV group name parser.
 *
 * Extracts country/language, content type, quality tier, and flags from any
 * IPTV group name format. Works universally across providers — no hardcoded
 * assumptions about a specific playlist.
 *
 * Parsing pipeline:
 *  1. Strip decorative noise (═══, ★★★, [NEW], etc.)
 *  2. Extract & remove quality tier (FHD, HD, SD, 4K, etc.)
 *  3. Extract & remove flags (PREMIUM, VIP, PPV, 24/7, etc.)
 *  4. Split by separator (" | ", " - ", " / ", " : ", etc.)
 *  5. Match prefix against country/language map
 *  6. Match suffix against multilingual content type keywords
 *  7. Fallback: unparseable groups go to "Other"
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ParsedGroup {
  /** Original raw group_name */
  original: string;
  /** ISO-ish country code if detected (e.g. "AR", "US") */
  country: string | null;
  /** Human-readable language/region name (e.g. "Arabic", "English") */
  language: string | null;
  /** Normalized content type (e.g. "Sports", "Movies") */
  contentType: string | null;
  /** Quality tier if detected (e.g. "FHD", "4K") */
  quality: string | null;
  /** Extracted flags (e.g. ["premium", "ppv"]) */
  flags: string[];
}

// ---------------------------------------------------------------------------
// Country/Language Map
// ---------------------------------------------------------------------------

/** Maps country codes AND full names to { code, language, flag } */
interface CountryInfo {
  code: string;
  language: string;
  flag: string;
}

const COUNTRY_ENTRIES: [string[], CountryInfo][] = [
  // Arabic
  [['AR', 'ARAB', 'ARABIC', 'ARABE', 'عربي', 'SA', 'AE', 'EG', 'IQ', 'JO', 'KW', 'LB', 'LY', 'MA', 'OM', 'QA', 'SY', 'TN', 'YE', 'BH', 'DZ', 'PS', 'SD'],
    { code: 'AR', language: 'Arabic', flag: '🇸🇦' }],
  // English
  [['US', 'USA', 'EN', 'ENG', 'ENGLISH', 'ANGLAIS'],
    { code: 'US', language: 'English', flag: '🇺🇸' }],
  [['UK', 'GB', 'BRITISH'],
    { code: 'UK', language: 'English (UK)', flag: '🇬🇧' }],
  [['CA', 'CANADA', 'CANADIAN'],
    { code: 'CA', language: 'English (CA)', flag: '🇨🇦' }],
  [['AU', 'AUSTRALIA', 'AUSTRALIAN'],
    { code: 'AU', language: 'English (AU)', flag: '🇦🇺' }],
  [['IE', 'IRELAND', 'IRISH'],
    { code: 'IE', language: 'English (IE)', flag: '🇮🇪' }],
  // French
  [['FR', 'FRANCE', 'FRENCH', 'FRANCAIS', 'FRANÇAIS'],
    { code: 'FR', language: 'French', flag: '🇫🇷' }],
  // German
  [['DE', 'GERMANY', 'GERMAN', 'DEUTSCH', 'ALLEMAND'],
    { code: 'DE', language: 'German', flag: '🇩🇪' }],
  [['AT', 'AUSTRIA'],
    { code: 'AT', language: 'German (AT)', flag: '🇦🇹' }],
  [['CH'],
    { code: 'CH', language: 'Swiss', flag: '🇨🇭' }],
  // Turkish
  [['TR', 'TURKEY', 'TURKISH', 'TURK', 'TÜRK', 'TURC'],
    { code: 'TR', language: 'Turkish', flag: '🇹🇷' }],
  // Spanish
  [['ES', 'SPAIN', 'SPANISH', 'ESPANOL', 'ESPAÑOL', 'ESPAGNOL'],
    { code: 'ES', language: 'Spanish', flag: '🇪🇸' }],
  [['MX', 'MEXICO', 'MEXICAN'],
    { code: 'MX', language: 'Spanish (MX)', flag: '🇲🇽' }],
  // Portuguese
  [['PT', 'PORTUGAL', 'PORTUGUESE', 'PORTUGAIS'],
    { code: 'PT', language: 'Portuguese', flag: '🇵🇹' }],
  [['BR', 'BRAZIL', 'BRAZILIAN', 'BRASIL'],
    { code: 'BR', language: 'Portuguese (BR)', flag: '🇧🇷' }],
  // Italian
  [['IT', 'ITALY', 'ITALIAN', 'ITALIANO', 'ITALIEN'],
    { code: 'IT', language: 'Italian', flag: '🇮🇹' }],
  // Dutch
  [['NL', 'NETHERLANDS', 'DUTCH', 'NEDERLAND', 'NÉERLANDAIS'],
    { code: 'NL', language: 'Dutch', flag: '🇳🇱' }],
  [['BE', 'BELGIUM', 'BELGIAN', 'BELGIQUE'],
    { code: 'BE', language: 'Belgian', flag: '🇧🇪' }],
  // Polish
  [['PL', 'POLAND', 'POLISH', 'POLOGNE', 'POLSKA'],
    { code: 'PL', language: 'Polish', flag: '🇵🇱' }],
  // Romanian
  [['RO', 'ROMANIA', 'ROMANIAN', 'ROUMANIE'],
    { code: 'RO', language: 'Romanian', flag: '🇷🇴' }],
  // Greek
  [['GR', 'GREECE', 'GREEK', 'GRECE', 'GRÈCE'],
    { code: 'GR', language: 'Greek', flag: '🇬🇷' }],
  // Scandinavian
  [['SE', 'SWEDEN', 'SWEDISH', 'SUEDE', 'SUÈDE'],
    { code: 'SE', language: 'Swedish', flag: '🇸🇪' }],
  [['NO', 'NORWAY', 'NORWEGIAN', 'NORVEGE', 'NORVÈGE'],
    { code: 'NO', language: 'Norwegian', flag: '🇳🇴' }],
  [['DK', 'DENMARK', 'DANISH', 'DANEMARK'],
    { code: 'DK', language: 'Danish', flag: '🇩🇰' }],
  [['FI', 'FINLAND', 'FINNISH', 'FINLANDE'],
    { code: 'FI', language: 'Finnish', flag: '🇫🇮' }],
  // Russian / Slavic
  [['RU', 'RUSSIA', 'RUSSIAN', 'RUSSE', 'RUSSIE'],
    { code: 'RU', language: 'Russian', flag: '🇷🇺' }],
  [['UA', 'UKRAINE', 'UKRAINIAN'],
    { code: 'UA', language: 'Ukrainian', flag: '🇺🇦' }],
  [['RS', 'SERBIA', 'SERBIAN', 'SERBIE'],
    { code: 'RS', language: 'Serbian', flag: '🇷🇸' }],
  [['HR', 'CROATIA', 'CROATIAN', 'CROATIE'],
    { code: 'HR', language: 'Croatian', flag: '🇭🇷' }],
  [['BG', 'BULGARIA', 'BULGARIAN', 'BULGARIE'],
    { code: 'BG', language: 'Bulgarian', flag: '🇧🇬' }],
  [['CZ', 'CZECH', 'CZECHIA'],
    { code: 'CZ', language: 'Czech', flag: '🇨🇿' }],
  [['SK', 'SLOVAKIA', 'SLOVAK'],
    { code: 'SK', language: 'Slovak', flag: '🇸🇰' }],
  [['HU', 'HUNGARY', 'HUNGARIAN', 'HONGRIE'],
    { code: 'HU', language: 'Hungarian', flag: '🇭🇺' }],
  // Asian
  [['IN', 'INDIA', 'INDIAN', 'HINDI', 'INDE'],
    { code: 'IN', language: 'Indian', flag: '🇮🇳' }],
  [['PK', 'PAKISTAN', 'PAKISTANI', 'URDU'],
    { code: 'PK', language: 'Pakistani', flag: '🇵🇰' }],
  [['BD', 'BANGLADESH', 'BANGLA', 'BENGALI'],
    { code: 'BD', language: 'Bengali', flag: '🇧🇩' }],
  [['PH', 'PHILIPPINES', 'FILIPINO', 'PINOY'],
    { code: 'PH', language: 'Filipino', flag: '🇵🇭' }],
  [['KR', 'KOREA', 'KOREAN', 'COREE', 'CORÉE'],
    { code: 'KR', language: 'Korean', flag: '🇰🇷' }],
  [['JP', 'JAPAN', 'JAPANESE', 'JAPON'],
    { code: 'JP', language: 'Japanese', flag: '🇯🇵' }],
  [['CN', 'CHINA', 'CHINESE', 'CHINE', 'MANDARIN'],
    { code: 'CN', language: 'Chinese', flag: '🇨🇳' }],
  [['TH', 'THAILAND', 'THAI', 'THAILANDE', 'THAÏLANDE'],
    { code: 'TH', language: 'Thai', flag: '🇹🇭' }],
  [['VN', 'VIETNAM', 'VIETNAMESE'],
    { code: 'VN', language: 'Vietnamese', flag: '🇻🇳' }],
  [['MY', 'MALAYSIA', 'MALAY'],
    { code: 'MY', language: 'Malay', flag: '🇲🇾' }],
  [['ID', 'INDONESIA', 'INDONESIAN'],
    { code: 'ID', language: 'Indonesian', flag: '🇮🇩' }],
  // African
  [['NG', 'NIGERIA', 'NIGERIAN'],
    { code: 'NG', language: 'Nigerian', flag: '🇳🇬' }],
  [['GH', 'GHANA'],
    { code: 'GH', language: 'Ghanaian', flag: '🇬🇭' }],
  [['KE', 'KENYA', 'KENYAN'],
    { code: 'KE', language: 'Kenyan', flag: '🇰🇪' }],
  [['ZA', 'SOUTH AFRICA'],
    { code: 'ZA', language: 'South African', flag: '🇿🇦' }],
  // Persian / Kurdish
  [['IR', 'IRAN', 'PERSIAN', 'FARSI', 'PERSE'],
    { code: 'IR', language: 'Persian', flag: '🇮🇷' }],
  [['AF', 'AFGHANISTAN', 'AFGHAN', 'DARI', 'PASHTO'],
    { code: 'AF', language: 'Afghan', flag: '🇦🇫' }],
  [['KU', 'KURD', 'KURDISH', 'KURDE'],
    { code: 'KU', language: 'Kurdish', flag: '🏳️' }],
  // Other
  [['IL', 'ISRAEL', 'HEBREW', 'ISRAELI'],
    { code: 'IL', language: 'Hebrew', flag: '🇮🇱' }],
  [['AL', 'ALBANIA', 'ALBANIAN', 'ALBANIE', 'SHQIP'],
    { code: 'AL', language: 'Albanian', flag: '🇦🇱' }],
  [['MK', 'MACEDONIA', 'MACEDONIAN'],
    { code: 'MK', language: 'Macedonian', flag: '🇲🇰' }],
  // Latin America (general)
  [['LATINO', 'LATIN', 'LATAM'],
    { code: 'LATAM', language: 'Latino', flag: '🌎' }],
  // Africa (general)
  [['AFRICA', 'AFRICAN', 'AFRIQUE'],
    { code: 'AFR', language: 'African', flag: '🌍' }],
  // International
  [['INT', 'INTERNATIONAL', 'WORLD', 'GLOBAL', 'MONDE'],
    { code: 'INT', language: 'International', flag: '🌐' }],
  // Caribbean / Creole
  [['HT', 'HAITI', 'HAITIAN', 'CREOLE'],
    { code: 'HT', language: 'Haitian', flag: '🇭🇹' }],
  [['JM', 'JAMAICA', 'JAMAICAN'],
    { code: 'JM', language: 'Jamaican', flag: '🇯🇲' }],
  // More European
  [['LT', 'LITHUANIA', 'LITHUANIAN'],
    { code: 'LT', language: 'Lithuanian', flag: '🇱🇹' }],
  [['LV', 'LATVIA', 'LATVIAN'],
    { code: 'LV', language: 'Latvian', flag: '🇱🇻' }],
  [['EE', 'ESTONIA', 'ESTONIAN'],
    { code: 'EE', language: 'Estonian', flag: '🇪🇪' }],
  [['SI', 'SLOVENIA', 'SLOVENIAN'],
    { code: 'SI', language: 'Slovenian', flag: '🇸🇮' }],
  [['BA', 'BOSNIA', 'BOSNIAN'],
    { code: 'BA', language: 'Bosnian', flag: '🇧🇦' }],
  [['ME', 'MONTENEGRO'],
    { code: 'ME', language: 'Montenegrin', flag: '🇲🇪' }],
  [['CY', 'CYPRUS'],
    { code: 'CY', language: 'Cypriot', flag: '🇨🇾' }],
  // Central Asia
  [['AZ', 'AZERBAIJAN', 'AZERI'],
    { code: 'AZ', language: 'Azerbaijani', flag: '🇦🇿' }],
  [['GE', 'GEORGIA', 'GEORGIAN'],
    { code: 'GE', language: 'Georgian', flag: '🇬🇪' }],
  [['AM', 'ARMENIA', 'ARMENIAN'],
    { code: 'AM', language: 'Armenian', flag: '🇦🇲' }],
  [['KZ', 'KAZAKHSTAN', 'KAZAKH'],
    { code: 'KZ', language: 'Kazakh', flag: '🇰🇿' }],
  [['UZ', 'UZBEKISTAN', 'UZBEK'],
    { code: 'UZ', language: 'Uzbek', flag: '🇺🇿' }],
];

/** Fast lookup: uppercase key → CountryInfo */
export const COUNTRY_LANGUAGE_MAP = new Map<string, CountryInfo>();
for (const [keys, info] of COUNTRY_ENTRIES) {
  for (const key of keys) {
    COUNTRY_LANGUAGE_MAP.set(key.toUpperCase(), info);
  }
}

// ---------------------------------------------------------------------------
// Content Type Keywords (multilingual)
// ---------------------------------------------------------------------------

const CONTENT_TYPE_ENTRIES: [string[], string][] = [
  // Sports
  [['SPORTS', 'SPORT', 'DEPORTES', 'DEPORTE', 'SPOR', 'ESPORTE', 'ESPORTES', 'SPORTIF',
    'FOOTBALL', 'SOCCER', 'FUTBOL', 'FÚTBOL', 'CALCIO',
    'BASKETBALL', 'TENNIS', 'RUGBY', 'CRICKET', 'BOXING', 'MMA', 'UFC', 'WWE', 'WRESTLING',
    'GOLF', 'F1', 'FORMULA', 'MOTORSPORT', 'RACING', 'BEINSPORT', 'BEIN'],
    'Sports'],
  // Movies
  [['MOVIES', 'MOVIE', 'CINEMA', 'CINÉMA', 'FILMS', 'FILM', 'PELICULAS', 'PELÍCULA', 'PELICOLA',
    'AFLAM', 'أفلام', 'VOD', 'KINO'],
    'Movies'],
  // Series / TV Shows
  [['SERIES', 'SÉRIE', 'SÉRIES', 'SERIE', 'TV SHOWS', 'TV SHOW', 'SHOWS', 'SHOW',
    'MOSALSAL', 'مسلسلات', 'DIZI', 'NOVELA', 'NOVELAS', 'TELENOVELA'],
    'Series'],
  // News
  [['NEWS', 'NOTICIAS', 'ACTUALITES', 'ACTUALITÉS', 'AKHBAR', 'أخبار', 'HABER', 'HABERLER',
    'NACHRICHTEN', 'NYHETER', 'NOTIZIE', 'INFORMACAO', 'INFORMAÇÃO'],
    'News'],
  // Kids
  [['KIDS', 'KID', 'CHILDREN', 'CHILD', 'CARTOON', 'CARTOONS', 'INFANTIL', 'ENFANTS', 'ENFANT',
    'ATFAL', 'أطفال', 'ÇOCUK', 'COCUK', 'KINDER', 'KINDEREN', 'BAMBINI', 'BARN', 'DZIECIĘCY',
    'ANIMÉ', 'ANIME', 'ANIMATION', 'ANIMATED', 'BABY', 'FAMILY'],
    'Kids'],
  // Documentary
  [['DOCUMENTARY', 'DOCUMENTARIES', 'DOCS', 'DOC', 'DOCUMENTAIRE', 'DOCUMENTAIRES',
    'DOCUMENTAL', 'DOCUMENTARIO', 'DOCUMENTÁRIO', 'BELGESEL', 'WATHAEQI', 'وثائقي'],
    'Documentary'],
  // Entertainment
  [['ENTERTAINMENT', 'DIVERTISSEMENT', 'ENTRETENIMIENTO', 'INTRATTENIMENTO',
    'UNTERHALTUNG', 'EGLENCE', 'EĞLENCE', 'UNDERHOLDNING', 'ROZRYWKA',
    'VARIETY', 'GENERAL', 'GÉNÉRALISTE', 'GENERALISTE'],
    'Entertainment'],
  // Music
  [['MUSIC', 'MUSICA', 'MÚSICA', 'MUSIQUE', 'MUSIK', 'MÜZIK', 'MUZIK',
    'MUSIQI', 'موسيقى', 'CONCERT', 'CONCERTS', 'MTV'],
    'Music'],
  // Religious
  [['RELIGIOUS', 'RELIGION', 'ISLAMIC', 'CHRISTIAN', 'SPIRITUAL', 'DINI',
    'ISLAMI', 'إسلامي', 'ديني', 'GOSPEL', 'CHURCH', 'MOSQUE', 'QURAN',
    'BIBLE', 'FAITH'],
    'Religious'],
  // Education
  [['EDUCATION', 'EDUCATIONAL', 'EDUCACION', 'ÉDUCATION', 'LEARNING',
    'SCIENCE', 'DISCOVERY', 'NATIONAL GEOGRAPHIC', 'NAT GEO', 'CULTURA',
    'CULTURE', 'KULTUREL'],
    'Education'],
  // Lifestyle / Cooking / Travel
  [['COOKING', 'FOOD', 'CUISINE', 'COCINA', 'CUCINA', 'YEMEK',
    'TRAVEL', 'LIFESTYLE', 'NATURE', 'GARDEN', 'HOME', 'FASHION',
    'HEALTH', 'FITNESS', 'WELLNESS'],
    'Lifestyle'],
  // Drama genres (for VOD)
  [['ACTION', 'AKSIYON'],
    'Action'],
  [['COMEDY', 'KOMEDI', 'COMEDIE', 'COMÉDIE', 'COMEDIA'],
    'Comedy'],
  [['DRAMA', 'DRAME', 'DRAM'],
    'Drama'],
  [['HORROR', 'HORREUR', 'KORKU'],
    'Horror'],
  [['THRILLER', 'SUSPENSE', 'GERILIM'],
    'Thriller'],
  [['ROMANCE', 'ROMANTIC', 'ROMANTIK', 'ROMANTIQUE'],
    'Romance'],
  [['SCI-FI', 'SCIFI', 'SCIENCE FICTION', 'BILIMKURGU'],
    'Sci-Fi'],
  [['WESTERN'],
    'Western'],
  // Premium / VIP (treated as content type if no other match)
  [['PREMIUM', 'VIP', 'GOLD', 'PLATINUM', 'DIAMOND', 'EXCLUSIVE'],
    'Premium'],
  // Countries (regional channels grouped together)
  [['COUNTRIES', 'COUNTRY', 'LOCAL', 'REGIONAL', 'NATIONAL', 'PAYS', 'PAIS'],
    'Regional'],
  // PPV / Events
  [['PPV', 'PAY PER VIEW', 'EVENTS', 'EVENT', 'PAY-PER-VIEW'],
    'PPV / Events'],
  // 24/7
  [['24/7', '24-7', '24H', 'ALWAYS ON', 'NON STOP', 'NON-STOP'],
    '24/7'],
];

/** Fast lookup: uppercase keyword → normalized content type */
export const CONTENT_TYPE_KEYWORDS = new Map<string, string>();
for (const [keywords, normalizedType] of CONTENT_TYPE_ENTRIES) {
  for (const kw of keywords) {
    CONTENT_TYPE_KEYWORDS.set(kw.toUpperCase(), normalizedType);
  }
}

// ---------------------------------------------------------------------------
// Script-based language detection (Arabic, CJK, Devanagari, Thai, etc.)
// ---------------------------------------------------------------------------

interface ScriptDetection {
  language: string;
  country: string;
  flag: string;
}

/**
 * Unicode range → language mapping.
 * We check what percentage of the text is in each script range.
 * If >30% of non-space chars are in a script, we classify the text as that language.
 */
const SCRIPT_RANGES: [RegExp, ScriptDetection][] = [
  // Arabic (includes Farsi/Urdu extended ranges)
  [/[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/,
    { language: 'Arabic', country: 'AR', flag: '🇸🇦' }],
  // CJK Chinese
  [/[\u4E00-\u9FFF\u3400-\u4DBF\u{20000}-\u{2A6DF}]/u,
    { language: 'Chinese', country: 'CN', flag: '🇨🇳' }],
  // Korean Hangul
  [/[\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F]/,
    { language: 'Korean', country: 'KR', flag: '🇰🇷' }],
  // Japanese Hiragana + Katakana (CJK kanji shared with Chinese, so check kana first)
  [/[\u3040-\u309F\u30A0-\u30FF\u31F0-\u31FF]/,
    { language: 'Japanese', country: 'JP', flag: '🇯🇵' }],
  // Devanagari (Hindi, Marathi, Nepali)
  [/[\u0900-\u097F]/,
    { language: 'Indian', country: 'IN', flag: '🇮🇳' }],
  // Thai
  [/[\u0E00-\u0E7F]/,
    { language: 'Thai', country: 'TH', flag: '🇹🇭' }],
  // Cyrillic (Russian, Ukrainian, Bulgarian, Serbian)
  [/[\u0400-\u04FF\u0500-\u052F]/,
    { language: 'Russian', country: 'RU', flag: '🇷🇺' }],
  // Greek
  [/[\u0370-\u03FF\u1F00-\u1FFF]/,
    { language: 'Greek', country: 'GR', flag: '🇬🇷' }],
  // Georgian
  [/[\u10A0-\u10FF\u2D00-\u2D2F]/,
    { language: 'Georgian', country: 'GE', flag: '🇬🇪' }],
  // Armenian
  [/[\u0530-\u058F]/,
    { language: 'Armenian', country: 'AM', flag: '🇦🇲' }],
  // Hebrew
  [/[\u0590-\u05FF\uFB1D-\uFB4F]/,
    { language: 'Hebrew', country: 'IL', flag: '🇮🇱' }],
  // Bengali
  [/[\u0980-\u09FF]/,
    { language: 'Bengali', country: 'BD', flag: '🇧🇩' }],
  // Tamil
  [/[\u0B80-\u0BFF]/,
    { language: 'Tamil', country: 'IN', flag: '🇮🇳' }],
  // Telugu
  [/[\u0C00-\u0C7F]/,
    { language: 'Telugu', country: 'IN', flag: '🇮🇳' }],
  // Urdu (uses Arabic script but extended — handled by Arabic range above)
  // Turkish special chars don't have their own block (Latin-based), detected by keywords instead
];

/** Detect language from Unicode script. Returns null if text is predominantly Latin. */
function detectScript(text: string): ScriptDetection | null {
  if (!text) return null;
  // Count non-space, non-digit, non-punctuation chars
  const stripped = text.replace(/[\s\d\p{P}\p{S}]/gu, '');
  if (stripped.length === 0) return null;

  for (const [regex, detection] of SCRIPT_RANGES) {
    const matches = stripped.match(new RegExp(regex.source, regex.flags + 'g'));
    if (matches && matches.length / stripped.length > 0.3) {
      return detection;
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Native-language content type keywords (non-Latin scripts)
// ---------------------------------------------------------------------------

/** Maps native-language words to normalized English content types */
const NATIVE_CONTENT_KEYWORDS: [RegExp, string][] = [
  // Arabic content types
  [/أفلام|افلام|فيلم/u, 'Movies'],
  [/مسلسلات|مسلسل/u, 'Series'],
  [/رياض[ةي]|كرة|سبورت/u, 'Sports'],
  [/أخبار|اخبار|نيوز/u, 'News'],
  [/أطفال|اطفال|كرتون|كارتون/u, 'Kids'],
  [/وثائقي|وثائقية/u, 'Documentary'],
  [/ترفيه|منوعات/u, 'Entertainment'],
  [/موسيق[ىي]|أغاني|اغاني/u, 'Music'],
  [/إسلامي|اسلامي|ديني[ة]?|قرآن|قران/u, 'Religious'],
  [/طبخ|مطبخ|طعام/u, 'Lifestyle'],
  [/أكشن|اكشن/u, 'Action'],
  [/كوميدي[ا]?/u, 'Comedy'],
  [/درام[اي]/u, 'Drama'],
  [/رعب/u, 'Horror'],
  [/رومانسي[ة]?/u, 'Romance'],
  [/تركي[ة]?/u, 'Series'],  // "Turkish" in Arabic usually means Turkish series
  [/هندي[ة]?/u, 'Movies'],  // "Indian" in Arabic usually means Bollywood
  [/عربي[ة]?/u, 'General'],  // "Arabic" — generic marker
  [/قنوات/u, 'General'],  // "Channels" — generic
  [/بث مباشر|بث حي|لايف/u, 'Live'],
  [/بريميوم|حصري/u, 'Premium'],
  // Russian content types
  [/фильм[ыи]?|кино/u, 'Movies'],
  [/сериал[ыи]?/u, 'Series'],
  [/спорт/u, 'Sports'],
  [/новост[ьи]/u, 'News'],
  [/детск[иое][ейм]?|мультфильм/u, 'Kids'],
  [/музык[аи]/u, 'Music'],
  [/развлечени[ея]/u, 'Entertainment'],
  // Turkish content types (Latin-based but helpful)
  [/dizi(?:ler)?/i, 'Series'],
  [/çizgi\s*film/i, 'Kids'],
  [/belgesel/i, 'Documentary'],
  [/müzik/i, 'Music'],
  [/haber(?:ler)?/i, 'News'],
];

/** Try to match content type from native-language keywords */
function matchNativeContentType(text: string): string | null {
  for (const [regex, type] of NATIVE_CONTENT_KEYWORDS) {
    if (regex.test(text)) return type;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Quality tiers
// ---------------------------------------------------------------------------

const QUALITY_REGEX = /\b(4K|8K|UHD|ULTRA\s*HD|FHD|FULL\s*HD|HD|SD|HEVC|H\.?265|H\.?264|HDR(?:10)?|IPTV)\b/gi;

// ---------------------------------------------------------------------------
// Flag keywords
// ---------------------------------------------------------------------------

const FLAG_REGEX = /\b(PREMIUM|VIP|GOLD|PLATINUM|DIAMOND|EXCLUSIVE|PPV|PAY[- ]PER[- ]VIEW|24\/7|24-7|NEW|BACKUP|MULTI|TRIAL)\b/gi;

// ---------------------------------------------------------------------------
// Decorative noise
// ---------------------------------------------------------------------------

/** Strips decorative characters and formatting noise from group names */
function stripNoise(raw: string): string {
  let s = raw;
  // Remove flag emojis (country flags are regional indicator symbols)
  s = s.replace(/[\u{1F1E0}-\u{1F1FF}]{2}/gu, '');
  // Remove other common emojis
  s = s.replace(/[\u{2600}-\u{27BF}\u{1F300}-\u{1F9FF}\u{FE00}-\u{FE0F}\u{200D}\u{20E3}\u{E0020}-\u{E007F}]/gu, '');
  // Remove decorative line chars
  s = s.replace(/[═─━┄┅┈┉╌╍╶╺▬▭◽◾◻◼⬛⬜]/g, '');
  // Remove decorative markers
  s = s.replace(/[★☆✦✧✩✪✫✬✭✮✯✰⭐⚡🔥💎👑●■□▪▫◆◇○◎►▸▹▶◀◄▻▷▽△▼▲❖⬥⬦♦♢⊹※•]/gu, '');
  // Remove bracketed tags: [NEW], [HD], (PREMIUM), {VIP}, etc.
  s = s.replace(/[\[({][^\])}\n]{1,20}[\])}]/g, '');
  // Remove leading/trailing decoration: ---, ===, ***, <<<, >>>, ###
  s = s.replace(/^[-=~#*<>|/\\.\s]+|[-=~#*<>|/\\.\s]+$/g, '');
  // Collapse multiple spaces
  s = s.replace(/\s{2,}/g, ' ');
  return s.trim();
}

// ---------------------------------------------------------------------------
// Separator detection
// ---------------------------------------------------------------------------

const SEPARATORS = [' | ', ' - ', ' / ', ' : ', '| ', '- ', ': '];

function splitBySeparator(text: string): { prefix: string; suffix: string } | null {
  for (const sep of SEPARATORS) {
    const idx = text.indexOf(sep);
    if (idx > 0) {
      const prefix = text.slice(0, idx).trim();
      const suffix = text.slice(idx + sep.length).trim();
      if (prefix.length > 0 && suffix.length > 0) {
        return { prefix, suffix };
      }
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Content type matching
// ---------------------------------------------------------------------------

/** Try to match a string against content type keywords. Checks multi-word phrases first. */
function matchContentType(text: string): string | null {
  const upper = text.toUpperCase().trim();

  // Direct match
  const direct = CONTENT_TYPE_KEYWORDS.get(upper);
  if (direct) return direct;

  // Check if the text contains any known keyword (for compound names like "SPORTS FHD")
  // Try multi-word keywords first (longer matches take priority)
  for (const [keyword, type] of CONTENT_TYPE_KEYWORDS) {
    if (keyword.includes(' ') && upper.includes(keyword)) return type;
  }
  // Then single-word keywords — match whole words only
  for (const [keyword, type] of CONTENT_TYPE_KEYWORDS) {
    if (!keyword.includes(' ')) {
      const regex = new RegExp(`\\b${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i');
      if (regex.test(upper)) return type;
    }
  }

  return null;
}

// ---------------------------------------------------------------------------
// Main parser
// ---------------------------------------------------------------------------

export function parseGroupName(raw: string): ParsedGroup {
  if (!raw || !raw.trim()) {
    return { original: raw, country: null, language: null, contentType: null, quality: null, flags: [] };
  }

  const original = raw;

  // Step 1: Strip decorative noise
  let cleaned = stripNoise(raw);
  if (!cleaned) {
    return { original, country: null, language: null, contentType: null, quality: null, flags: [] };
  }

  // Step 2: Extract quality tier
  let quality: string | null = null;
  const qualityMatch = cleaned.match(QUALITY_REGEX);
  if (qualityMatch) {
    quality = normalizeQuality(qualityMatch[0]);
    cleaned = cleaned.replace(QUALITY_REGEX, '').replace(/\s{2,}/g, ' ').trim();
  }

  // Step 3: Extract flags
  const flags: string[] = [];
  const flagMatches = cleaned.matchAll(FLAG_REGEX);
  for (const m of flagMatches) {
    const flag = m[0].toLowerCase().replace(/\s+/g, '-');
    if (!flags.includes(flag)) flags.push(flag);
  }
  cleaned = cleaned.replace(FLAG_REGEX, '').replace(/\s{2,}/g, ' ').trim();

  // Step 4: Detect script-based language (Arabic, CJK, Cyrillic, etc.)
  const scriptDetect = detectScript(cleaned);

  // Step 5: Split by separator
  const split = splitBySeparator(cleaned);

  let country: string | null = null;
  let language: string | null = null;
  let contentType: string | null = null;

  if (split) {
    // Step 6: Check prefix for country/language
    const prefixUpper = split.prefix.toUpperCase();
    const countryInfo = COUNTRY_LANGUAGE_MAP.get(prefixUpper);

    if (countryInfo) {
      country = countryInfo.code;
      language = countryInfo.language;
      // Check suffix for content type (try native keywords first, then English)
      contentType = matchNativeContentType(split.suffix) || matchContentType(split.suffix);
      if (!contentType) {
        contentType = titleCase(split.suffix);
      }
    } else {
      // Prefix isn't a country — check if suffix is a country (reversed format: "Sports | AR")
      const suffixUpper = split.suffix.toUpperCase();
      const suffixCountry = COUNTRY_LANGUAGE_MAP.get(suffixUpper);
      if (suffixCountry) {
        country = suffixCountry.code;
        language = suffixCountry.language;
        contentType = matchNativeContentType(split.prefix) || matchContentType(split.prefix);
        if (!contentType) {
          contentType = titleCase(split.prefix);
        }
      } else {
        // Neither part is a country code — but check script detection
        if (scriptDetect) {
          country = scriptDetect.country;
          language = scriptDetect.language;
          // Try native content keywords on both parts
          const prefixNative = matchNativeContentType(split.prefix);
          const suffixNative = matchNativeContentType(split.suffix);
          const prefixEn = matchContentType(split.prefix);
          const suffixEn = matchContentType(split.suffix);
          contentType = suffixNative || suffixEn || prefixNative || prefixEn;
          if (!contentType) {
            // Use the whole cleaned string as content type label
            contentType = cleaned;
          }
        } else {
          // Neither part is a country — try both as content types
          const prefixType = matchContentType(split.prefix);
          const suffixType = matchContentType(split.suffix);
          if (prefixType && suffixType) {
            language = prefixType;
            contentType = suffixType;
          } else if (prefixType) {
            contentType = prefixType;
          } else if (suffixType) {
            contentType = suffixType;
            language = titleCase(split.prefix);
          } else {
            language = titleCase(split.prefix);
            contentType = titleCase(split.suffix);
          }
        }
      }
    }
  } else {
    // No separator found — try the whole string
    const wholeUpper = cleaned.toUpperCase();
    const wholeCountry = COUNTRY_LANGUAGE_MAP.get(wholeUpper);
    if (wholeCountry) {
      country = wholeCountry.code;
      language = wholeCountry.language;
    } else {
      // Try native content keywords first (handles "أفلام أكشن", "مسلسلات", etc.)
      const nativeType = matchNativeContentType(cleaned);
      if (nativeType) {
        contentType = nativeType;
        // If script detected a language, assign it
        if (scriptDetect) {
          country = scriptDetect.country;
          language = scriptDetect.language;
        }
      } else {
        contentType = matchContentType(cleaned);
        if (!contentType) {
          // Script detection as last resort — assign language even without content type
          if (scriptDetect) {
            country = scriptDetect.country;
            language = scriptDetect.language;
            contentType = cleaned; // Use raw text as label
          } else {
            contentType = titleCase(cleaned);
          }
        }
      }
    }
  }

  return { original, country, language, contentType, quality, flags };
}

// ---------------------------------------------------------------------------
// Batch parse helper
// ---------------------------------------------------------------------------

/** Parse all group names at once and return a Map for quick lookup */
export function parseAllGroups(groupNames: string[]): Map<string, ParsedGroup> {
  const map = new Map<string, ParsedGroup>();
  for (const name of groupNames) {
    if (name && !map.has(name)) {
      map.set(name, parseGroupName(name));
    }
  }
  return map;
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

function normalizeQuality(raw: string): string {
  const u = raw.toUpperCase().replace(/\s+/g, '');
  if (u === '4K' || u === 'UHD' || u === 'ULTRAHD') return '4K';
  if (u === '8K') return '8K';
  if (u === 'FHD' || u === 'FULLHD') return 'FHD';
  if (u === 'HD') return 'HD';
  if (u === 'SD') return 'SD';
  if (u === 'HEVC' || u === 'H265' || u === 'H.265') return 'HEVC';
  if (u === 'H264' || u === 'H.264') return 'H.264';
  if (u.startsWith('HDR')) return 'HDR';
  return raw.toUpperCase();
}

function titleCase(text: string): string {
  if (!text) return '';
  return text
    .split(' ')
    .map((word) => {
      if (word.length <= 2 && word === word.toUpperCase()) return word; // Keep short acronyms
      if (word.includes('-')) {
        return word
          .split('-')
          .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
          .join('-');
      }
      return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
    })
    .join(' ');
}

/** Get the flag emoji for a country code */
export function getCountryFlag(countryCode: string | null): string | null {
  if (!countryCode) return null;
  for (const [, info] of COUNTRY_ENTRIES) {
    if (info.code === countryCode) return info.flag;
  }
  return null;
}

/** Get language name for a country code */
export function getLanguageName(countryCode: string): string | null {
  for (const [, info] of COUNTRY_ENTRIES) {
    if (info.code === countryCode) return info.language;
  }
  return null;
}
