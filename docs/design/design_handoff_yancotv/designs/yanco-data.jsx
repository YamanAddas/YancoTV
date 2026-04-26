// YancoTV+ — sample data (emerald theme). Art = gradient placeholders with dominant hue.

const CATEGORIES = [
  { id: 'favorites', label: 'Favorites', glyph: '★', pinned: true },
  { id: 'all',       label: 'All',       glyph: '▦', pinned: true },
  { id: 'arabic',    label: 'Arabic',    pinned: true },
  { id: '4k',        label: '4K | UHD 3840P', pinned: false },
  { id: 'sports',    label: 'Sports',    pinned: false },
  { id: 'news',      label: 'News',      pinned: false },
  { id: 'kids',      label: 'Kids',      pinned: false },
  { id: 'relax',     label: '4K | RELAX UHD', pinned: false },
  { id: 'movies',    label: 'Movies',    pinned: false },
  { id: 'music',     label: 'Music',     pinned: false },
];

const art = (hue, mood='warm') => {
  const h = hue;
  if (mood === 'warm') return {
    dominant: `oklch(0.6 0.18 ${h})`,
    bg: `
      radial-gradient(120% 80% at 20% 15%, oklch(0.6 0.18 ${h}) 0%, transparent 55%),
      radial-gradient(100% 90% at 85% 90%, oklch(0.35 0.14 ${h+25}) 0%, transparent 60%),
      linear-gradient(135deg, oklch(0.22 0.08 ${h+10}) 0%, oklch(0.12 0.05 ${h-15}) 100%)
    `,
  };
  if (mood === 'cool') return {
    dominant: `oklch(0.58 0.14 ${h})`,
    bg: `
      radial-gradient(110% 80% at 70% 20%, oklch(0.58 0.14 ${h}) 0%, transparent 55%),
      radial-gradient(90% 90% at 15% 85%, oklch(0.32 0.12 ${h+30}) 0%, transparent 60%),
      linear-gradient(160deg, oklch(0.2 0.06 ${h}) 0%, oklch(0.1 0.04 ${h+40}) 100%)
    `,
  };
  return {
    dominant: `oklch(0.45 0.08 ${h})`,
    bg: `
      radial-gradient(130% 90% at 50% 10%, oklch(0.4 0.1 ${h}) 0%, transparent 60%),
      radial-gradient(80% 80% at 90% 100%, oklch(0.25 0.07 ${h+20}) 0%, transparent 60%),
      linear-gradient(180deg, oklch(0.14 0.04 ${h}) 0%, oklch(0.07 0.02 ${h}) 100%)
    `,
  };
};

const HERO = {
  id: 'hero-tozluyaka',
  title: 'Tozluyaka',
  kind: 'SERIES',
  tags: ['Series', 'Turkish Diziler', 'BrittBox'],
  synopsis: 'Four friends uncover a decades-old secret buried beneath their coastal town — and find themselves chased through a web of conspiracies that stretch back generations.',
  rating: 'TV-14',
  year: '2025',
  seasons: '2 Seasons',
  art: art(150, 'warm'),
};

const RAIL_CONTINUE = [
  { id:'cw-1', title:'Roland-Garros: Efsaneyi İnşa', badge:'RESUME', sub:'TR — HBO MAX', progress:0.42, art:art(20,'warm') },
  { id:'cw-2', title:'Death Whisperer',              badge:'120m LEFT', sub:'AR-AS · 2023', progress:0.78, art:art(280,'noir') },
  { id:'cw-3', title:'Devil’s Triangle',             badge:'87m LEFT', sub:'TR — SINEMA', progress:0.52, art:art(265,'cool') },
  { id:'cw-4', title:'Soyut Dışavurumcu Bir',        badge:'RESUME', sub:'TR — SINEMA', progress:0.12, art:art(200,'noir') },
  { id:'cw-5', title:'Bâtir la Légende',             badge:'RESUME', sub:'FR — ARTE', progress:0.31, art:art(50,'warm') },
  { id:'cw-6', title:'Al Hayba — S5 E12',            badge:'RESUME', sub:'AR — MBC', progress:0.88, art:art(15,'noir') },
];

const RAIL_FRESH = [
  { id:'f-1', title:'Devil’s Triangle',    badge:'NEW · 4K', sub:'TR — SINEMA', art:art(265,'cool') },
  { id:'f-2', title:'Tala',                badge:'NEW', sub:'AR — Shahid', art:art(350,'warm') },
  { id:'f-3', title:'Dune: Part Two',      badge:'4K HDR', sub:'EN — HBO MAX', art:art(40,'warm') },
  { id:'f-4', title:'Oppenheimer',         badge:'4K HDR', sub:'EN — Universal', art:art(65,'warm') },
  { id:'f-5', title:'The Northman',        badge:'HD', sub:'EN — Focus', art:art(80,'noir') },
  { id:'f-6', title:'Tenet',               badge:'4K HDR', sub:'EN — WB', art:art(240,'noir') },
];

const RAIL_LIVE = [
  { id:'l-1', title:'beIN Sports 1 HD',  badge:'● LIVE', live:true, sub:'PSG · Lyon', art:art(145,'cool') },
  { id:'l-2', title:'V Sport UHD 3840P', badge:'● LIVE', live:true, sub:'Sendeoppheld', art:art(180,'cool') },
  { id:'l-3', title:'AD Sports Premium', badge:'● LIVE', live:true, sub:'Football Live', art:art(200,'cool') },
  { id:'l-4', title:'4K | 24/7 UHD',     badge:'● LIVE', live:true, sub:'3840P', art:art(160,'cool') },
  { id:'l-5', title:'4K | RELAX UHD',    badge:'● LIVE', live:true, sub:'Ambient', art:art(140,'cool') },
  { id:'l-6', title:'SSC Sport 1',       badge:'● LIVE', live:true, sub:'Saudi Pro League', art:art(155,'cool') },
];

const RAILS = [
  { id: 'continue', kicker: 'FOR YOU', label: 'Continue watching', note: 'Jump back where you left off', items: RAIL_CONTINUE },
  { id: 'fresh',    kicker: 'FRESH',   label: 'Recently added',    note: 'New movies and series in your library', items: RAIL_FRESH },
  { id: 'live',     kicker: 'LIVE',    label: 'On now',            note: 'Jump into a channel', items: RAIL_LIVE },
];

Object.assign(window, { CATEGORIES, HERO, RAILS, art });
