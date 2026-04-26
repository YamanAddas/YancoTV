// Shared primitives used by every page. Loaded as a Babel script; exports to window.

const { useState: uS, useEffect: uE, useRef: uR, useMemo: uM, useCallback: uC } = React;

// === Scale stage to fit viewport =====================================
function Stage({ children, design=[1920,1080] }) {
  const ref = uR(null);
  uE(()=>{
    const el = ref.current;
    if (!el) return;
    const fit = () => {
      const vw = window.innerWidth, vh = window.innerHeight;
      const s = Math.min(vw/design[0], vh/design[1]);
      const sx = (vw - design[0]*s)/2;
      const sy = (vh - design[1]*s)/2;
      el.style.transform = `translate(${sx}px, ${sy}px) scale(${s})`;
    };
    fit(); window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);
  return (
    <div className="stage-root" style={{ display:'block' }}>
      <div ref={ref} className="stage-1080" style={{ transformOrigin:'0 0', position:'absolute', top:0, left:0 }}>{children}</div>
    </div>
  );
}

// === Crumb nav (present on every screen) =============================
function CrumbBar({ here }) {
  return (
    <div className="crumb-bar">
      <a className="crumb-home y-chip" href="index.html">◀ YANCOTV TOC</a>
      <span className="y-chip">{here}</span>
    </div>
  );
}

// === Hex icon — line-style only =======================================
const Icon = ({ path, size=22, stroke=1.8, fill='none' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round">{path}</svg>
);

const I = {
  home:       <><path d="M3 11 L12 3 L21 11 V20 a1 1 0 0 1-1 1 h-5 V14 h-4 v7 H4 a1 1 0 0 1-1-1 Z"/></>,
  livetv:     <><rect x="2" y="5" width="20" height="13" rx="1"/><path d="M8 21 h8"/><path d="M12 18 v3"/></>,
  grid:       <><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></>,
  movies:     <><rect x="3" y="4" width="18" height="16" rx="1"/><path d="M3 8h18 M7 4v16 M17 4v16"/></>,
  series:     <><rect x="3" y="5" width="18" height="14" rx="1"/><path d="M8 2l4 3 4-3"/></>,
  favorites:  <><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 1 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></>,
  search:     <><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></>,
  settings:   <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></>,
  play:       <path d="M6 4 L20 12 L6 20 Z" fill="currentColor" stroke="none"/>,
  pause:      <><rect x="6" y="5" width="4" height="14"/><rect x="14" y="5" width="4" height="14"/></>,
  back10:     <><path d="M12 5 a7 7 0 1 0 0 14 M12 5 L16 5 M12 5 L12 9"/><text x="8" y="15" fontSize="6" fill="currentColor" stroke="none" fontFamily="monospace" fontWeight="700">10</text></>,
  fwd10:      <><path d="M12 5 a7 7 0 1 1 0 14 M12 5 L8 5 M12 5 L12 9"/><text x="8" y="15" fontSize="6" fill="currentColor" stroke="none" fontFamily="monospace" fontWeight="700">10</text></>,
  skip:       <><path d="M5 4 L15 12 L5 20 Z" fill="currentColor" stroke="none"/><path d="M19 4 V20" strokeWidth="2.4"/></>,
  cc:         <><rect x="3" y="5" width="18" height="14" rx="1"/><path d="M7 15 h3 M15 15 h3 M7 11 h-.5 M14.5 11 h-.5"/></>,
  audio:      <><path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M16 8a4 4 0 0 1 0 8 M19 5a8 8 0 0 1 0 14"/></>,
  speed:      <><circle cx="12" cy="13" r="7"/><path d="M12 13 L15 10 M9 3 h6"/></>,
  aspect:     <><rect x="3" y="7" width="18" height="10" rx="1"/><path d="M3 12 h18"/></>,
  menu:       <><path d="M4 6h16 M4 12h16 M4 18h16"/></>,
  star:       <polygon points="12,3 14.6,9 21,9.3 16,13.6 17.6,20 12,16.5 6.4,20 8,13.6 3,9.3 9.4,9"/>,
  cast:       <><path d="M2 16v4h4 M2 12v2a8 8 0 0 1 8 8h2 M2 8v2a12 12 0 0 1 12 12h2 M21 4 H3 a1 1 0 0 0-1 1 v3"/></>,
  external:   <><path d="M14 4 h6 v6 M20 4 L10 14 M10 4 H4 v16 h16 v-6"/></>,
  record:     <circle cx="12" cy="12" r="5" fill="currentColor" stroke="none"/>,
  sleep:      <><path d="M12 2a10 10 0 1 0 10 10A8 8 0 0 1 12 2Z"/></>,
  clock:      <><circle cx="12" cy="12" r="9"/><path d="M12 7 V12 L15 14"/></>,
  info:       <><circle cx="12" cy="12" r="9"/><path d="M12 8 V8.01 M12 12 V16"/></>,
  retry:      <><path d="M3 12 a9 9 0 0 1 9-9 c4 0 7.5 2.5 9 6 M21 3 V9 H15"/></>,
  chevR:      <path d="M9 6 L15 12 L9 18"/>,
  chevL:      <path d="M15 6 L9 12 L15 18"/>,
  chevUp:     <path d="M6 15 L12 9 L18 15"/>,
  chevDn:     <path d="M6 9 L12 15 L18 9"/>,
  close:      <><path d="M5 5 L19 19 M19 5 L5 19"/></>,
  plus:       <><path d="M12 5 V19 M5 12 H19"/></>,
  check:      <><path d="M4 12 L10 18 L20 6"/></>,
  drag:       <><circle cx="9" cy="6" r="1.2"/><circle cx="15" cy="6" r="1.2"/><circle cx="9" cy="12" r="1.2"/><circle cx="15" cy="12" r="1.2"/><circle cx="9" cy="18" r="1.2"/><circle cx="15" cy="18" r="1.2"/></>,
  more:       <><circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/></>,
  trash:      <><path d="M3 6 H21 M8 6 V4 H16 V6 M6 6 L7 20 H17 L18 6"/></>,
  edit:       <><path d="M4 20 H8 L20 8 L16 4 L4 16 Z M14 6 L18 10"/></>,
  sync:       <><path d="M4 4 V10 H10 M20 20 V14 H14 M20 10 A9 9 0 0 0 5 6 M4 14 A9 9 0 0 0 19 18"/></>,
  signal:     <><path d="M3 18 h2 V13 H3 Z M8 18 h2 V9 H8 Z M13 18 h2 V5 H13 Z M18 18 h2 V1 H18 Z"/></>,
  folder:     <><path d="M3 6 a1 1 0 0 1 1-1 h6 L12 7 H20 a1 1 0 0 1 1 1 V18 a1 1 0 0 1-1 1 H4 a1 1 0 0 1-1-1 Z"/></>,
  lock:       <><rect x="5" y="11" width="14" height="9" rx="1"/><path d="M8 11 V7 a4 4 0 0 1 8 0 V11"/></>,
  shield:     <><path d="M12 3 L20 6 V12 a8 8 0 0 1 -8 9 a8 8 0 0 1 -8 -9 V6 Z"/></>,
  bell:       <><path d="M6 16 V11 a6 6 0 0 1 12 0 V16 L20 18 H4 Z M10 21 h4"/></>,
  hdd:        <><rect x="2" y="6" width="20" height="12" rx="1"/><path d="M6 12 H18 M6 15 H10"/></>,
  key:        <><circle cx="8" cy="15" r="4"/><path d="M11 12 L22 1 M19 4 L22 7 M16 7 L19 10"/></>,
  link:       <><path d="M10 14 a5 5 0 0 0 7 0 l3-3 a5 5 0 0 0 -7 -7 l-1 1 M14 10 a5 5 0 0 0 -7 0 l-3 3 a5 5 0 0 0 7 7 l1-1"/></>,
  mac:        <><rect x="2" y="5" width="20" height="14" rx="1"/><path d="M2 10 H22"/></>,
  globe:      <><circle cx="12" cy="12" r="9"/><path d="M3 12 h18 M12 3 a13 13 0 0 1 0 18 M12 3 a13 13 0 0 0 0 18"/></>,
  guide:      <><rect x="3" y="5" width="18" height="14" rx="1"/><path d="M3 10 H21 M9 5 V19"/></>,
  cloud:      <><path d="M6 18 H18 a4 4 0 0 0 0-8 a6 6 0 0 0-11-1 A4 4 0 0 0 6 18 Z"/></>,
  trend:      <><path d="M3 17 L9 11 L13 15 L21 7"/><path d="M15 7 H21 V13"/></>,
  volume:     <><path d="M4 9v6h4l5 4V5L8 9H4z M16 8a4 4 0 0 1 0 8"/></>,
  lang:       <><path d="M4 5 H11 M7.5 5 V19 M4 12 H11 M14 19 L17 9 L20 19 M15 16 H19"/></>,
  theme:      <><circle cx="12" cy="12" r="9"/><path d="M12 3 A9 9 0 0 0 12 21" fill="currentColor" stroke="none"/></>,
  type:       <><path d="M4 6 H20 M12 6 V19 M9 19 H15"/></>,
};

// === Hex button ======================================================
function HexBtn({ children, primary, width, height=44, icon, onClick, focused, style }) {
  return (
    <div onClick={onClick} className={`y-btn ${focused?'focus-ring':''}`} style={{
      height, width, padding:'0 22px',
      display:'inline-flex', alignItems:'center', justifyContent:'center', gap:10,
      cursor:'pointer',
      background: primary
        ? 'linear-gradient(180deg, var(--accent) 0%, var(--accent-deep) 100%)'
        : 'rgba(20,37,31,0.82)',
      color: primary ? '#04130C' : 'var(--text-primary)',
      border: primary ? 'none' : '1px solid var(--border-subtle)',
      fontSize:12, fontWeight: primary? 800 : 700, letterSpacing:'0.14em', textTransform:'uppercase',
      boxShadow: primary ? '0 10px 24px rgba(0,226,138,0.4), inset 0 1px 0 rgba(255,255,255,0.4)' : '0 6px 14px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.06)',
      whiteSpace:'nowrap',
      ...style,
    }}>
      {icon && <Icon path={icon} size={14} />}
      {children}
    </div>
  );
}

// === Hex chip ========================================================
function HexChip({ children, active, tone='default', icon, style, onClick }) {
  const palette = {
    default: { bg:'rgba(20,37,31,0.6)', col:'var(--text-secondary)', bd:'var(--border-subtle)' },
    active:  { bg:'linear-gradient(180deg, var(--accent) 0%, var(--accent-deep) 100%)', col:'#04130C', bd:'transparent' },
    muted:   { bg:'rgba(20,37,31,0.4)', col:'var(--text-muted)', bd:'var(--border-subtle)' },
    live:    { bg:'rgba(255,59,59,0.2)', col:'var(--error)', bd:'rgba(255,59,59,0.5)' },
    premium: { bg:'rgba(215,179,106,0.14)', col:'var(--premium)', bd:'rgba(215,179,106,0.4)' },
  };
  const p = palette[active ? 'active' : tone];
  return (
    <div onClick={onClick} className="y-chip" style={{
      display:'inline-flex', alignItems:'center', gap:6,
      padding:'5px 14px', height:30,
      background: p.bg, color: p.col, border: `1px solid ${p.bd}`,
      fontSize:11, fontWeight: active?800:700, letterSpacing:'0.12em', textTransform:'uppercase',
      whiteSpace:'nowrap', fontFamily:'var(--font-mono)',
      cursor: onClick?'pointer':'default',
      boxShadow: active ? '0 6px 16px rgba(0,226,138,0.35), inset 0 1px 0 rgba(255,255,255,0.35)' : 'none',
      ...style,
    }}>
      {icon && <Icon path={icon} size={12} />}
      {children}
    </div>
  );
}

// === Hex pill (larger, centered) =====================================
function HexPill({ children, active, icon, onClick, style, width }) {
  return (
    <div onClick={onClick} className="y-pill" style={{
      display:'inline-flex', alignItems:'center', gap:10,
      padding:'0 24px', height:44, width,
      background: active ? 'linear-gradient(180deg, var(--accent) 0%, var(--accent-deep) 100%)' : 'rgba(20,37,31,0.7)',
      color: active ? '#04130C' : 'var(--text-primary)',
      border: active ? 'none' : '1px solid var(--border-subtle)',
      fontSize:13, fontWeight: active?800:600, letterSpacing:'0.1em',
      cursor: onClick?'pointer':'default',
      boxShadow: active ? '0 10px 24px rgba(0,226,138,0.4), inset 0 1px 0 rgba(255,255,255,0.38)' : 'none',
      whiteSpace:'nowrap',
      ...style,
    }}>
      {icon && <Icon path={icon} size={15} />}
      {children}
    </div>
  );
}

// === Card (cut-corner) ===============================================
function YCard({ children, large, style, glow }) {
  return (
    <div className={large?'y-card-l':'y-card'} style={{
      background:'rgba(10,20,16,0.82)',
      border:'1px solid var(--panel-border)',
      boxShadow: glow ? '0 30px 60px rgba(0,0,0,0.6), 0 0 0 1px rgba(0,226,138,0.3), inset 0 1px 0 rgba(255,255,255,0.06)' : '0 20px 40px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.04)',
      position:'relative', overflow:'hidden',
      ...style,
    }}>{children}</div>
  );
}

// === Tiny toggle =====================================================
function Toggle({ value, onChange }) {
  return (
    <div onClick={()=>onChange && onChange(!value)} className="y-chip" style={{
      width:52, height:28, padding:2, cursor:'pointer',
      background: value ? 'linear-gradient(90deg, var(--accent-deep) 0%, var(--accent) 100%)' : 'rgba(0,0,0,0.5)',
      border:'1px solid var(--border-subtle)',
      position:'relative', display:'flex', alignItems:'center',
      boxShadow: value ? '0 0 14px rgba(0,226,138,0.4), inset 0 1px 0 rgba(255,255,255,0.25)' : 'inset 0 1px 3px rgba(0,0,0,0.5)',
    }}>
      <div className="y-chip" style={{
        width:22, height:22,
        background: value ? '#04130C' : 'var(--text-muted)',
        transform: `translateX(${value? 22 : 0}px)`,
        transition:'transform 180ms ease',
      }} />
    </div>
  );
}

// === Hero sidebar placeholder (shared by settings + others when needed)
function YancoAppBar() {
  return (
    <div style={{
      position:'absolute', left:0, top:0, bottom:0, width:100,
      background:'rgba(10,20,16,0.7)',
      backdropFilter:'blur(16px)',
      borderRight:'1px solid var(--border-subtle)',
      display:'flex', flexDirection:'column', alignItems:'center', padding:'24px 0', gap:8,
      zIndex:40,
    }}>
      <div className="y-chip" style={{
        width:48, height:48, background:'linear-gradient(180deg, var(--accent) 0%, var(--accent-deep) 100%)',
        display:'grid', placeItems:'center', color:'#04130C', marginBottom:18,
        fontFamily:'var(--font-mono)', fontSize:22, fontWeight:900,
      }}>Y</div>
      {[
        { i:I.home, l:'HOME' },
        { i:I.livetv, l:'LIVE' },
        { i:I.guide, l:'GUIDE' },
        { i:I.movies, l:'MOVIES' },
        { i:I.series, l:'SERIES' },
        { i:I.favorites, l:'FAV' },
        { i:I.search, l:'SEARCH' },
      ].map((x,idx)=>(
        <div key={idx} style={{
          width:56, height:56, display:'grid', placeItems:'center',
          color:'var(--text-muted)', marginTop: idx===0? 0 : 4,
        }}><Icon path={x.i} size={22} /></div>
      ))}
      <div style={{flex:1}} />
      <div className="y-chip" style={{
        width:56, height:56, display:'grid', placeItems:'center',
        color:'var(--accent)',
        background:'rgba(0,226,138,0.12)',
        border:'1px solid rgba(0,226,138,0.3)',
      }}><Icon path={I.settings} size={22} /></div>
    </div>
  );
}

Object.assign(window, {
  Stage, CrumbBar, Icon, I, HexBtn, HexChip, HexPill, YCard, Toggle, YancoAppBar,
  uS, uE, uR, uM, uC,
});
