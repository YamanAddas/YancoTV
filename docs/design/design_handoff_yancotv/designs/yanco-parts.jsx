// YancoTV+ — view parts v2. Hex cards, emerald accent, 3D focus.

const { useState, useEffect, useRef, useMemo } = React;

// ---------- LOGO MARK ----------
const LogoMark = ({ accent }) => (
  <div style={{ display:'flex', alignItems:'center', gap:10 }}>
    <svg width="34" height="34" viewBox="0 0 34 34" style={{ filter: `drop-shadow(0 0 8px ${accent}66)` }}>
      <defs>
        <linearGradient id="lg1" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={accent} />
          <stop offset="1" stopColor="#00B872" />
        </linearGradient>
      </defs>
      <polygon points="17,2 31,10 31,24 17,32 3,24 3,10" fill="none" stroke="url(#lg1)" strokeWidth="1.8" />
      <polygon points="17,7 26,12 26,22 17,27 8,22 8,12" fill={accent} opacity="0.15" />
      <path d="M11 12 L17 18 L23 12 M17 18 L17 24" stroke={accent} strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="17" cy="17" r="1.2" fill="#fff" />
    </svg>
    <div style={{ fontFamily:'Inter', fontWeight:800, fontSize:17, letterSpacing:'-0.01em', color:'#F0FFF6' }}>
      YancoTV<span style={{ color: accent }}>+</span>
    </div>
  </div>
);

// ---------- SIDEBAR ----------
const Sidebar = ({ active, accent, onNav }) => (
  <div style={{
    position:'absolute', left:0, top:0, bottom:0, width:260,
    background: 'linear-gradient(180deg, rgba(10,20,16,0.92) 0%, rgba(10,20,16,0.7) 100%)',
    backdropFilter:'blur(24px)', WebkitBackdropFilter:'blur(24px)',
    borderRight:`1px solid ${accent}22`,
    boxShadow:`inset -1px 0 0 ${accent}11, 1px 0 30px rgba(0,0,0,0.4)`,
    padding:'36px 0', zIndex:30,
    display:'flex', flexDirection:'column',
  }}>
    <div style={{ padding:'0 24px 28px' }}>
      <LogoMark accent={accent} />
    </div>

    <div style={{ padding:'0 16px', display:'flex', flexDirection:'column', gap:4 }}>
      {NAV_ITEMS.map(({id,label,Icon})=>{
        const isActive = id === active;
        return (
          <div key={id} onMouseEnter={()=>onNav && onNav(id)} style={{
            position:'relative', height:52, display:'flex', alignItems:'center', gap:14,
            padding:'0 16px', cursor:'pointer',
          }}>
            {isActive && (
              <>
                <div className="hex-2cut" style={{
                  position:'absolute', inset:0,
                  background:`linear-gradient(90deg, ${accent}26 0%, ${accent}10 70%, transparent 100%)`,
                  border:`1px solid ${accent}55`,
                  boxShadow:`inset 0 1px 0 ${accent}33, inset 0 0 20px ${accent}22`,
                }} />
                <div style={{
                  position:'absolute', left:-16, top:12, bottom:12, width:3,
                  background:accent, borderRadius:2,
                  boxShadow:`0 0 10px ${accent}, 0 0 20px ${accent}88`,
                }} />
              </>
            )}
            <div style={{
              position:'relative', color: isActive ? accent : '#7A8A82',
              filter: isActive ? `drop-shadow(0 0 6px ${accent}88)` : 'none',
              display:'flex', alignItems:'center', justifyContent:'center',
              width:24, height:24,
            }}>
              <Icon active={isActive} />
            </div>
            <div style={{
              position:'relative',
              fontSize:15, fontWeight: isActive ? 600 : 500,
              color: isActive ? '#F0FFF6' : '#8A9A92',
              letterSpacing:'-0.005em',
            }}>{label}</div>
          </div>
        );
      })}
    </div>

    <div style={{ flex:1 }} />
    {/* footer — profile */}
    <div style={{ padding:'0 24px', display:'flex', alignItems:'center', gap:12 }}>
      <div className="hex-chip" style={{
        width:40, height:40,
        background:`linear-gradient(135deg, ${accent} 0%, #007A4E 100%)`,
        display:'flex', alignItems:'center', justifyContent:'center',
        fontWeight:700, color:'#04130C', fontSize:14,
      }}>YN</div>
      <div style={{ flex:1 }}>
        <div style={{ fontSize:13, fontWeight:600, color:'#F0FFF6' }}>Yanco</div>
        <div style={{ fontSize:11, color:'#5F7068', fontFamily:'JetBrains Mono', letterSpacing:'0.1em' }}>PREMIUM</div>
      </div>
    </div>
  </div>
);

// ---------- HERO ----------
const Hero = ({ hero, accent }) => (
  <div style={{ position:'absolute', left:260, right:0, top:0, height:440, overflow:'hidden' }}>
    {/* ambient layer */}
    <div key={hero.id} style={{
      position:'absolute', inset:0,
      background: hero.art.bg,
      backgroundSize:'cover', backgroundPosition:'center',
      filter:'saturate(1.1) brightness(0.85)',
      transition:'opacity 300ms ease-in-out',
    }} />
    {/* strong gradient to blend */}
    <div style={{
      position:'absolute', inset:0,
      background:`
        linear-gradient(180deg, rgba(10,20,16,0.2) 0%, rgba(10,20,16,0.55) 55%, rgba(10,20,16,1) 100%),
        linear-gradient(90deg, rgba(10,20,16,0.85) 0%, rgba(10,20,16,0.25) 45%, rgba(10,20,16,0) 70%)
      `,
    }} />
    {/* faint hex grid */}
    <div style={{
      position:'absolute', inset:0, opacity:0.06, mixBlendMode:'screen',
      backgroundImage:`
        repeating-linear-gradient(60deg, ${accent}22 0 1px, transparent 1px 40px),
        repeating-linear-gradient(-60deg, ${accent}22 0 1px, transparent 1px 40px)
      `,
    }} />

    {/* content */}
    <div style={{ position:'absolute', left:56, top:56, right:500, color:'#F0FFF6' }}>
      <div style={{
        display:'inline-flex', alignItems:'center', gap:8,
        fontSize:11, fontWeight:700, letterSpacing:'0.22em',
        color:accent, marginBottom:12,
        fontFamily:'JetBrains Mono',
      }}>
        <span style={{ width:22, height:1, background:accent, display:'inline-block' }} />
        FEATURED · {hero.kind}
      </div>

      <div style={{
        fontSize:64, fontWeight:900, letterSpacing:'-0.03em', lineHeight:0.95,
        textShadow:'0 4px 32px rgba(0,0,0,0.8)', marginBottom:14,
        background:`linear-gradient(180deg, #F0FFF6 0%, #A7D9BE 100%)`,
        WebkitBackgroundClip:'text', WebkitTextFillColor:'transparent',
        backgroundClip:'text',
      }}>{hero.title}</div>

      <div style={{ display:'flex', gap:8, marginBottom:14, flexWrap:'wrap' }}>
        {hero.tags.map((t,i)=>(
          <div key={i} className="hex-chip" style={{
            padding:'5px 16px', height:28,
            background: i===0 ? `${accent}22` : 'rgba(255,255,255,0.05)',
            border: i===0 ? `1px solid ${accent}55` : '1px solid rgba(255,255,255,0.1)',
            color: i===0 ? accent : '#C5D5CC',
            fontSize:11, fontWeight:600, letterSpacing:'0.08em', textTransform:'uppercase',
            display:'inline-flex', alignItems:'center',
          }}>{t}</div>
        ))}
        <div className="hex-chip" style={{
          padding:'5px 16px', height:28,
          background:'rgba(255,255,255,0.05)', border:'1px solid rgba(255,255,255,0.1)',
          color:'#C5D5CC', fontSize:11, fontWeight:600, letterSpacing:'0.08em',
          display:'inline-flex', alignItems:'center',
        }}>{hero.year}</div>
      </div>

      <div style={{
        fontSize:15, lineHeight:1.45, color:'#C5D5CC',
        maxWidth:560, marginBottom:22, fontWeight:400,
        textShadow:'0 2px 12px rgba(0,0,0,0.6)',
      }}>{hero.synopsis}</div>

      {/* CTAs */}
      <div style={{ display:'flex', gap:14, alignItems:'center' }}>
        <FocusableButton accent={accent} primary autoFocus>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M7 5 L19 12 L7 19 Z" /></svg>
          PLAY NOW
        </FocusableButton>
        <FocusableButton accent={accent}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 20.2 C8.5 17.8 3.5 14.8 3.5 10.2 A4.2 4.2 0 0 1 7.7 6 C9.6 6 11.1 7 12 8.5 C12.9 7 14.4 6 16.3 6 A4.2 4.2 0 0 1 20.5 10.2 C20.5 14.8 15.5 17.8 12 20.2 Z"/>
          </svg>
          FAVORITE
        </FocusableButton>
        <FocusableButton accent={accent}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="9"/><path d="M12 8 L12 12 L15 14"/>
          </svg>
          MORE INFO
        </FocusableButton>
      </div>
    </div>

    {/* right-side meta card */}
    <div className="hex-2cut-lg" style={{
      position:'absolute', right:56, top:56, width:380, padding:'20px 24px',
      background:'rgba(10,20,16,0.72)',
      backdropFilter:'blur(30px) saturate(1.5)', WebkitBackdropFilter:'blur(30px) saturate(1.5)',
      border:`1px solid ${accent}22`,
      boxShadow:`
        0 30px 80px rgba(0,0,0,0.6),
        inset 0 1px 0 rgba(255,255,255,0.06),
        inset 0 0 0 1px rgba(0,226,138,0.1)
      `,
    }}>
      <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center', marginBottom:20 }}>
        <div className="mono" style={{ fontSize:11, color:accent, letterSpacing:'0.2em', fontWeight:700 }}>DETAILS</div>
        <div className="mono" style={{ fontSize:10, color:'#5F7068', letterSpacing:'0.14em' }}>S02 · E04</div>
      </div>
      <Row k="RATING" v={hero.rating} />
      <Row k="YEAR" v={hero.year} />
      <Row k="SEASONS" v={hero.seasons} />
      <Row k="AUDIO" v="TR · AR · EN · FR" />
      <Row k="SUBS" v="TR · EN · AR · FR · DE" />
      <Row k="SOURCE" v="BrittBox · Turkish Diziler" last />
    </div>
  </div>
);
const Row = ({ k, v, last }) => (
  <div style={{
    display:'flex', justifyContent:'space-between', alignItems:'center',
    padding:'7px 0', borderBottom: last ? 'none' : '1px solid rgba(255,255,255,0.05)',
  }}>
    <div className="mono" style={{ fontSize:10, color:'#5F7068', letterSpacing:'0.18em' }}>{k}</div>
    <div style={{ fontSize:12, color:'#F0FFF6', fontWeight:500 }}>{v}</div>
  </div>
);

// ---------- FOCUSABLE BUTTON ----------
const FocusableButton = ({ children, primary, accent, autoFocus }) => {
  const [hot, setHot] = useState(!!autoFocus);
  return (
    <div
      onMouseEnter={()=>setHot(true)} onMouseLeave={()=>setHot(!!autoFocus && false)}
      onFocus={()=>setHot(true)} onBlur={()=>setHot(false)}
      tabIndex={0}
      className="hex-btn"
      style={{
        height:44, padding:'0 26px 0 20px',
        display:'inline-flex', alignItems:'center', gap:10,
        fontSize:13, fontWeight:700, letterSpacing:'0.12em', textTransform:'uppercase',
        cursor:'pointer', outline:'none',
        background: primary
          ? (hot
              ? `linear-gradient(180deg, ${accent} 0%, #00B872 100%)`
              : `linear-gradient(180deg, ${accent}ee 0%, #00A867 100%)`)
          : (hot ? 'rgba(255,255,255,0.14)' : 'rgba(255,255,255,0.06)'),
        color: primary ? '#04130C' : '#F0FFF6',
        border: primary ? 'none' : `1px solid ${hot ? accent+'66' : 'rgba(255,255,255,0.12)'}`,
        boxShadow: primary
          ? (hot ? `0 10px 40px ${accent}88, inset 0 1px 0 rgba(255,255,255,0.5)` : `0 6px 22px ${accent}55, inset 0 1px 0 rgba(255,255,255,0.35)`)
          : (hot ? `0 8px 24px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.08)` : 'none'),
        transform: hot ? 'translateY(-2px)' : 'translateY(0)',
        transition:'all 180ms cubic-bezier(.34,1.56,.64,1)',
      }}
    >
      {children}
    </div>
  );
};

// ---------- CATEGORY CHIPS ----------
const CategoryChips = ({ active, accent }) => (
  <div style={{
    position:'absolute', left:260, right:0, top:440,
    height:52, display:'flex', alignItems:'center',
    paddingLeft:56, zIndex:5,
  }}>
    <div style={{ display:'flex', gap:10, flex:1, overflow:'hidden', position:'relative' }}>
      {CATEGORIES.map((c)=>{
        const isActive = c.id === active;
        return (
          <div key={c.id} className="hex-chip" style={{
            height:34, padding:'0 18px',
            display:'inline-flex', alignItems:'center', gap:8,
            background: isActive
              ? `linear-gradient(180deg, ${accent} 0%, #00B872 100%)`
              : 'rgba(20, 37, 31, 0.7)',
            color: isActive ? '#04130C' : '#A7B8AF',
            fontSize:13, fontWeight: isActive ? 700 : 500, letterSpacing:'0.04em',
            whiteSpace:'nowrap', flexShrink:0,
            boxShadow: isActive
              ? `0 8px 24px ${accent}66, inset 0 1px 0 rgba(255,255,255,0.4)`
              : '0 4px 12px rgba(0,0,0,0.3), inset 0 1px 0 rgba(255,255,255,0.04)',
            border: isActive ? 'none' : '1px solid rgba(255,255,255,0.06)',
          }}>
            {c.glyph && <span style={{ fontSize:13 }}>{c.glyph}</span>}
            {c.label}
          </div>
        );
      })}
      <div style={{
        position:'absolute', right:0, top:0, bottom:0, width:140,
        background:'linear-gradient(90deg, transparent 0%, #0A1410 100%)',
        pointerEvents:'none',
      }} />
    </div>
  </div>
);

// ---------- CARD ----------
const Card = ({ item, focused, onFocus, accent }) => {
  const [sheenKey, setSheenKey] = useState(0);
  useEffect(()=>{ if (focused) setSheenKey(k=>k+1); }, [focused]);

  return (
    <div
      onMouseEnter={()=>onFocus && onFocus(item)}
      style={{
        width:240, height:135, flexShrink:0,
        position:'relative', cursor:'pointer',
        transform: focused ? 'translateY(-10px) scale(1.08)' : 'translateY(0) scale(1)',
        transition:'transform 220ms cubic-bezier(.34,1.56,.64,1)',
        willChange:'transform',
      }}>
      {/* drop shadow layer (outside clip) */}
      <div style={{
        position:'absolute', inset:0,
        filter: focused
          ? `drop-shadow(0 20px 40px rgba(0,0,0,0.7)) drop-shadow(0 0 30px ${accent}66)`
          : 'drop-shadow(0 6px 14px rgba(0,0,0,0.5))',
        transition:'filter 180ms ease-out',
      }}>
        {/* outer glow ring (slightly larger clipped hex) */}
        {focused && (
          <div className="hex-2cut" style={{
            position:'absolute', inset:-3,
            background:`linear-gradient(180deg, ${accent} 0%, #00B872 100%)`,
            boxShadow:`0 0 30px ${accent}aa`,
          }} />
        )}
        {/* main card */}
        <div className="hex-2cut" style={{
          position:'absolute', inset:0,
          background: item.art.bg,
          overflow:'hidden',
          filter: focused ? 'brightness(1.15) saturate(1.2)' : 'brightness(0.9)',
          transition:'filter 180ms ease-out',
        }}>
          {/* facet highlight top */}
          <div style={{
            position:'absolute', left:0, right:0, top:0, height:1,
            background:`linear-gradient(90deg, transparent, ${focused ? 'rgba(255,255,255,0.5)' : 'rgba(255,255,255,0.2)'}, transparent)`,
          }} />
          {/* facet highlight bottom-right diagonal */}
          <div style={{
            position:'absolute', right:-1, top:0, width:1, height:'100%',
            background:`linear-gradient(180deg, ${focused ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.1)'}, transparent)`,
          }} />

          {/* sheen sweep on focus */}
          {focused && (
            <div key={sheenKey} style={{
              position:'absolute', top:0, bottom:0, left:0, width:'40%',
              background:'linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.35) 50%, transparent 100%)',
              animation:'sheen-sweep 900ms ease-out forwards',
              pointerEvents:'none',
            }} />
          )}

          {/* bottom gradient */}
          <div style={{
            position:'absolute', left:0, right:0, bottom:0, height:90,
            background:'linear-gradient(180deg, transparent 0%, rgba(5,10,8,0.9) 70%, #050A08 100%)',
          }} />

          {/* top-right badge */}
          <div className="hex-chip" style={{
            position:'absolute', top:12, right:12,
            padding:'5px 12px', height:22,
            background: item.live ? 'rgba(255,59,59,0.9)' : 'rgba(5,10,8,0.75)',
            backdropFilter:'blur(10px)',
            border: item.live ? 'none' : '1px solid rgba(255,255,255,0.08)',
            color: item.live ? '#fff' : accent,
            fontSize:10, fontWeight:700, letterSpacing:'0.1em',
            fontFamily:'JetBrains Mono',
            display:'inline-flex', alignItems:'center',
          }}>{item.badge}</div>

          {/* progress */}
          {typeof item.progress === 'number' && item.progress > 0 && (
            <div style={{
              position:'absolute', left:14, right:14, bottom:44, height:3,
              background:'rgba(255,255,255,0.15)', borderRadius:2,
            }}>
              <div style={{
                height:'100%', width:`${item.progress*100}%`,
                background:accent, borderRadius:2,
                boxShadow:`0 0 8px ${accent}`,
              }} />
            </div>
          )}

          {/* title + sub */}
          <div style={{ position:'absolute', left:14, right:14, bottom:10 }}>
            <div style={{
              fontSize:14, fontWeight:700, color:'#F0FFF6',
              letterSpacing:'-0.005em', marginBottom:2,
              textShadow:'0 1px 4px rgba(0,0,0,0.9)',
              whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
            }}>{item.title}</div>
            {item.sub && (
              <div className="mono" style={{
                fontSize:10, color:'#A7B8AF', letterSpacing:'0.1em',
                whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
              }}>{item.sub}</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

// ---------- RAIL ----------
const Rail = ({ rail, focusedId, onFocus, accent }) => (
  <div style={{ marginBottom:2 }}>
    <div style={{
      display:'flex', alignItems:'baseline', gap:12,
      paddingLeft:56, paddingRight:56, marginBottom:4,
    }}>
      <div className="mono" style={{
        fontSize:10, color:accent, letterSpacing:'0.22em', fontWeight:700,
      }}>{rail.kicker}</div>
      <div style={{
        fontSize:18, fontWeight:700, color:'#F0FFF6',
        letterSpacing:'-0.015em',
      }}>{rail.label}</div>
      <div style={{ fontSize:12, color:'#5F7068', fontWeight:400 }}>{rail.note}</div>
      <div style={{
        flex:1, height:1,
        background:`linear-gradient(90deg, ${accent}22 0%, transparent 60%)`,
      }} />
      <div className="mono" style={{ fontSize:10, color:'#5F7068', letterSpacing:'0.16em' }}>
        {rail.items.length} ›
      </div>
    </div>
    <div style={{
      display:'flex', gap:18, paddingLeft:56, paddingRight:56,
      paddingTop:8, paddingBottom:8,
    }}>
      {rail.items.map((item)=>(
        <Card key={item.id} item={item}
          focused={focusedId===item.id}
          onFocus={onFocus}
          accent={accent}
        />
      ))}
    </div>
  </div>
);

// ---------- REMOTE HINT BAR ----------
const RemoteBar = ({ accent }) => {
  const keys = [
    { k:'▲▼◀▶', label:'NAVIGATE' },
    { k:'OK',     label:'SELECT' },
    { k:'◀',      label:'BACK' },
    { k:'☰',      label:'MENU' },
    { k:'⏻',      label:'POWER' },
  ];
  return (
    <div style={{
      position:'absolute', left:260, right:0, bottom:0, height:56,
      display:'flex', alignItems:'center', justifyContent:'center', gap:24,
      background:'linear-gradient(180deg, transparent 0%, rgba(5,10,8,0.85) 50%, rgba(5,10,8,0.95) 100%)',
      borderTop:`1px solid ${accent}11`,
      zIndex:20,
    }}>
      {keys.map((kk,i)=>(
        <div key={i} style={{ display:'flex', alignItems:'center', gap:8 }}>
          <div className="hex-chip" style={{
            height:26, padding:'0 12px',
            background:'rgba(20,37,31,0.85)',
            border:`1px solid ${accent}33`,
            color:accent, fontSize:11, fontWeight:700,
            fontFamily:'JetBrains Mono',
            display:'inline-flex', alignItems:'center', letterSpacing:'0.08em',
          }}>{kk.k}</div>
          <div className="mono" style={{ fontSize:10, color:'#5F7068', letterSpacing:'0.16em', fontWeight:600 }}>{kk.label}</div>
        </div>
      ))}
    </div>
  );
};

// ---------- TOP BAR HUD ----------
const TopHUD = ({ accent }) => {
  const [clock, setClock] = useState('21:38');
  useEffect(()=>{
    const tick=()=>{ const d=new Date(); const p=n=>String(n).padStart(2,'0'); setClock(`${p(d.getHours())}:${p(d.getMinutes())}`); };
    tick(); const id=setInterval(tick,15000); return ()=>clearInterval(id);
  },[]);
  return (
    <div style={{
      position:'absolute', right:28, top:24, zIndex:40,
      display:'flex', alignItems:'center', gap:10,
    }}>
      <div className="hex-chip" style={{
        height:34, padding:'0 16px',
        background:'rgba(20,37,31,0.75)', backdropFilter:'blur(20px)', WebkitBackdropFilter:'blur(20px)',
        border:`1px solid ${accent}22`,
        display:'inline-flex', alignItems:'center', gap:12,
        fontFamily:'JetBrains Mono', fontSize:11, color:'#A7B8AF',
        letterSpacing:'0.12em', fontWeight:600,
      }}>
        <span style={{ display:'inline-flex', alignItems:'center', gap:6, color:accent }}>
          <span style={{ width:6, height:6, borderRadius:3, background:accent, boxShadow:`0 0 8px ${accent}` }} />
          ONLINE
        </span>
        <span style={{ opacity:0.3 }}>│</span>
        <span>4K · HDR10+</span>
        <span style={{ opacity:0.3 }}>│</span>
        <span style={{ color:'#F0FFF6' }}>{clock}</span>
      </div>
    </div>
  );
};

Object.assign(window, { Sidebar, Hero, CategoryChips, Card, Rail, RemoteBar, TopHUD, LogoMark });
