// YancoTV+ — Live TV v2. Preview on top, channel coverflow on bottom, toggleable category drawer.

const { useState: useState_LT, useMemo: useMemo_LT, useEffect: useEffect_LT } = React;

const LIVE_CHANNELS = [
  { id:'c1',  name:'4K | 24/7 UHD',     sub:'UHD 3840P',    cat:'4k',     art:art(155,'cool'), show:'Ambient Scenic Loop',           next:'Drone Cities · 23:30' },
  { id:'c2',  name:'4K | RELAX UHD',    sub:'Ambient',      cat:'4k',     art:art(140,'cool'), show:'Forest Rain · 4K Dolby',        next:'Mountain Vistas · 23:45' },
  { id:'c3',  name:'V SPORT UHD',       sub:'Sendeoppheld', cat:'sports', art:art(180,'cool'), show:'Live · PSG vs Lyon',            next:'RedBull TV · 23:30' },
  { id:'c4',  name:'beIN SPORTS 1',     sub:'FRANCE',       cat:'sports', art:art(145,'cool'), show:'Ligue 1 · J30 · Live',          next:'Saint-Etienne vs Bastia' },
  { id:'c5',  name:'beIN SPORTS XTRA',  sub:'UK',           cat:'sports', art:art(160,'cool'), show:'Charlton vs Ipswich',           next:'SkyBet Championship' },
  { id:'c6',  name:'AD SPORTS',         sub:'UAE',          cat:'sports', art:art(200,'cool'), show:'Match Preview · UCL',           next:'Football Daily' },
  { id:'c7',  name:'SSC SPORT 1',       sub:'SAUDI',        cat:'sports', art:art(120,'cool'), show:'Al-Nassr vs Al-Ittihad',        next:'Post-match wrap' },
  { id:'c8',  name:'MBC 1 HD',          sub:'ARABIC',       cat:'arabic', art:art(25,'warm'),  show:'Al Hayba — S5 E12',             next:'Evening News · 00:00' },
  { id:'c9',  name:'MBC ACTION',        sub:'ARABIC',       cat:'arabic', art:art(15,'noir'),  show:'Fast X',                        next:'Top Gun' },
  { id:'c10', name:'AL JAZEERA EN',     sub:'NEWS 24/7',    cat:'news',   art:art(300,'noir'), show:'Headlines',                     next:'Inside Story · 23:30' },
  { id:'c11', name:'EURONEWS LIVE',     sub:'EU · NEWS',    cat:'news',   art:art(220,'cool'), show:'Global Wrap',                   next:'Markets' },
  { id:'c12', name:'DUBAI SPORTS',      sub:'UAE',          cat:'sports', art:art(210,'cool'), show:'Football Hour',                 next:'Highlights' },
];

// -------------------- ORB (channel hex tile used in wheel) --------------------
function ChannelOrb({ item, width, height, focused, ringColor }) {
  return (
    <div className="hex-2cut-lg" style={{
      position:'relative', width, height,
      background: item.art.bg,
      overflow:'hidden',
      boxShadow: focused
        ? `0 30px 60px rgba(0,0,0,0.75), 0 0 60px ${ringColor}bb, 0 0 0 2px ${ringColor}, inset 0 2px 0 rgba(255,255,255,0.14)`
        : `0 14px 26px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06)`,
      filter: focused ? 'brightness(1.1) saturate(1.2)' : 'brightness(0.82) saturate(0.85)',
      transition: 'filter 220ms ease, box-shadow 220ms ease',
    }}>
      <div style={{
        position:'absolute', inset:0,
        background:`linear-gradient(180deg, rgba(5,10,8,0.12) 0%, rgba(5,10,8,0.35) 50%, rgba(5,10,8,0.92) 100%)`,
      }} />
      <div style={{
        position:'absolute', inset:0,
        background:`linear-gradient(115deg, transparent 35%, rgba(255,255,255,${focused?0.18:0.06}) 50%, transparent 65%)`,
        mixBlendMode:'screen',
      }} />
      <div style={{
        position:'absolute', left:0, right:0, top:0, height:1,
        background:`linear-gradient(90deg, transparent, rgba(255,255,255,${focused?0.55:0.22}), transparent)`,
      }} />
      <div style={{
        position:'absolute', top: focused? 14 : 10, right: focused? 14 : 10,
        display:'inline-flex', alignItems:'center', gap:4,
        padding:'3px 8px', borderRadius:100,
        background:'rgba(255,59,59,0.92)',
        fontSize: focused? 10: 8, fontWeight:800, color:'#fff', letterSpacing:'0.14em',
        fontFamily:'JetBrains Mono', whiteSpace:'nowrap',
        boxShadow: focused ? '0 0 14px rgba(255,59,59,0.7)' : 'none',
      }}>
        <span style={{ width: focused? 5: 4, height: focused? 5: 4, borderRadius:3, background:'#fff' }} />
        LIVE
      </div>
      <div style={{
        position:'absolute', left: focused? 18: 12, right: focused? 18: 12, bottom: focused? 18: 12,
      }}>
        <div style={{
          fontSize: focused? 18 : 13, fontWeight:800, color:'#F0FFF6',
          letterSpacing:'-0.01em', whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
          textShadow:'0 2px 8px rgba(0,0,0,0.9)', lineHeight:1.1,
        }}>{item.name}</div>
        <div className="mono" style={{
          fontSize: focused? 11 : 9, color: focused ? ringColor : '#A7B8AF',
          letterSpacing:'0.14em', fontWeight:700, marginTop:3,
          whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
        }}>{item.sub}</div>
      </div>
    </div>
  );
}

// -------------------- CATEGORY DRAWER — vertical list of wide horizontal hex orbs --------------------
function CategoryDrawer({ active, setActive, accent, open, setOpen }) {
  const cats = CATEGORIES;
  return (
    <>
      {/* Toggle button — always visible */}
      <button onClick={()=>setOpen(!open)} className="hex-2cut" style={{
        position:'absolute', left:276, top:86, zIndex:30,
        width:44, height:44, border:'none', cursor:'pointer',
        background: open ? `linear-gradient(180deg, ${accent} 0%, #00B872 100%)` : 'rgba(20,37,31,0.88)',
        color: open ? '#04130C' : '#F0FFF6',
        display:'flex', alignItems:'center', justifyContent:'center',
        boxShadow: open
          ? `0 8px 20px ${accent}88, inset 0 1px 0 rgba(255,255,255,0.42)`
          : '0 8px 18px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.06), 0 0 0 1px rgba(255,255,255,0.06)',
        transition:'all 240ms ease',
      }}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
          {open ? (
            <><path d="M6 6L18 18"/><path d="M6 18L18 6"/></>
          ) : (
            <><path d="M4 7h16"/><path d="M4 12h16"/><path d="M4 17h10"/></>
          )}
        </svg>
      </button>
      {!open && (
        <div className="mono" style={{
          position:'absolute', left:332, top:98, zIndex:30,
          fontSize:10, color:'#A7B8AF', letterSpacing:'0.22em', fontWeight:700,
          pointerEvents:'none',
        }}>CATEGORIES</div>
      )}

      {/* Drawer */}
      <div style={{
        position:'absolute', left:276, top:148, bottom:24, width:288,
        zIndex:25,
        transform: open ? 'translateX(0)' : 'translateX(-320px)',
        opacity: open ? 1 : 0,
        transition:'transform 360ms cubic-bezier(.22,1,.36,1), opacity 240ms ease',
        pointerEvents: open ? 'auto' : 'none',
      }}>
        {/* drawer backdrop */}
        <div className="hex-2cut-lg" style={{
          position:'absolute', inset:0,
          background:'rgba(8,18,14,0.72)',
          backdropFilter:'blur(22px)', WebkitBackdropFilter:'blur(22px)',
          border:`1px solid ${accent}22`,
          boxShadow:'0 20px 60px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.04)',
        }} />
        {/* header */}
        <div style={{ position:'absolute', top:16, left:22, right:22, display:'flex', alignItems:'baseline', gap:10 }}>
          <div className="mono" style={{ fontSize:10, color:accent, letterSpacing:'0.22em', fontWeight:700 }}>CATEGORIES</div>
          <div style={{ flex:1, height:1, background:`linear-gradient(90deg, ${accent}44 0%, transparent 100%)` }} />
          <div className="mono" style={{ fontSize:9, color:'#5F7068', letterSpacing:'0.14em' }}>{cats.length}</div>
        </div>

        {/* list of horizontal hex orbs */}
        <div style={{
          position:'absolute', top:48, left:16, right:16, bottom:16,
          display:'flex', flexDirection:'column', gap:8, overflowY:'auto',
        }}>
          {cats.map(c => {
            const isActive = c.id === active;
            return (
              <div key={c.id}
                onClick={()=>setActive(c.id)}
                style={{
                  position:'relative', height:50, flexShrink:0, cursor:'pointer',
                  transition:'transform 180ms ease',
                }}
                onMouseEnter={e=>{ if(!isActive) e.currentTarget.style.transform='translateX(4px)'; }}
                onMouseLeave={e=>{ e.currentTarget.style.transform='translateX(0)'; }}
              >
                <div className="hex-2cut" style={{
                  position:'absolute', inset:0,
                  background: isActive
                    ? `linear-gradient(90deg, ${accent} 0%, #00B872 100%)`
                    : 'rgba(20, 37, 31, 0.75)',
                  border: isActive ? 'none' : '1px solid rgba(255,255,255,0.06)',
                  boxShadow: isActive
                    ? `0 10px 24px ${accent}66, inset 0 1px 0 rgba(255,255,255,0.36)`
                    : '0 4px 10px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.04)',
                  display:'flex', alignItems:'center', padding:'0 18px', gap:12,
                  color: isActive ? '#04130C' : '#F0FFF6',
                }}>
                  {c.glyph && (
                    <div style={{
                      width:22, height:22, flexShrink:0,
                      display:'flex', alignItems:'center', justifyContent:'center',
                      fontSize:14, fontWeight:800,
                    }}>{c.glyph}</div>
                  )}
                  <div style={{
                    flex:1, textTransform:'uppercase',
                    fontSize:12, fontWeight: isActive ? 800 : 600,
                    letterSpacing:'0.1em', whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
                  }}>{c.label}</div>
                  {isActive && (
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M9 6 L15 12 L9 18 Z"/>
                    </svg>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* invisible dim layer when open — click to close */}
      {open && (
        <div onClick={()=>setOpen(false)} style={{
          position:'absolute', left:260, right:0, top:0, bottom:0,
          zIndex:20, pointerEvents:'auto',
        }} />
      )}
    </>
  );
}

// -------------------- TOP PREVIEW PANEL (wide, short) --------------------
function LivePreviewTop({ channel, accent }) {
  return (
    <div className="hex-2cut-lg" style={{
      position:'absolute', left:340, right:56, top:88, height:424,
      background: channel.art.bg,
      overflow:'hidden',
      boxShadow:`0 40px 100px rgba(0,0,0,0.7), 0 0 0 1px ${accent}33, inset 0 1px 0 rgba(255,255,255,0.08)`,
    }}>
      {/* cinema gradient */}
      <div style={{
        position:'absolute', inset:0,
        background:`
          linear-gradient(180deg, rgba(5,10,8,0.2) 0%, rgba(5,10,8,0.55) 60%, rgba(5,10,8,0.96) 100%),
          linear-gradient(90deg, rgba(5,10,8,0.72) 0%, rgba(5,10,8,0.3) 45%, transparent 70%)
        `,
      }} />
      {/* facet line */}
      <div style={{ position:'absolute', left:0, right:0, top:0, height:1, background:`linear-gradient(90deg, transparent, rgba(255,255,255,0.35), transparent)` }} />

      {/* top-left: LIVE CHANNEL + ON AIR pill */}
      <div style={{ position:'absolute', top:26, left:34, display:'flex', alignItems:'center', gap:12 }}>
        <div className="mono" style={{ fontSize:11, color:'#A7B8AF', letterSpacing:'0.24em', fontWeight:700 }}>LIVE CHANNEL</div>
        <div style={{
          display:'inline-flex', alignItems:'center', gap:6, padding:'5px 11px', borderRadius:100,
          background:'rgba(255,59,59,0.2)', border:'1px solid rgba(255,59,59,0.55)',
          color:'#FF6B6B', fontSize:10, fontWeight:800, letterSpacing:'0.16em', whiteSpace:'nowrap',
        }}>
          <span style={{ width:6, height:6, borderRadius:3, background:'#FF3B3B', boxShadow:'0 0 10px #FF3B3B', animation:'live-blink 1.6s ease-in-out infinite' }} />
          ON AIR
        </div>
      </div>

      {/* top-right: NOW / NEXT */}
      <div style={{
        position:'absolute', top:24, right:28,
        display:'flex', gap:28, alignItems:'flex-start', maxWidth:440,
      }}>
        <div style={{ textAlign:'right' }}>
          <div className="mono" style={{ fontSize:9, color:accent, letterSpacing:'0.22em', fontWeight:700, marginBottom:4 }}>NOW · 22:45</div>
          <div style={{ fontSize:13, color:'#F0FFF6', fontWeight:600, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', maxWidth:220 }}>{channel.show}</div>
        </div>
        <div style={{ width:1, height:32, background:'rgba(255,255,255,0.12)' }} />
        <div style={{ textAlign:'right' }}>
          <div className="mono" style={{ fontSize:9, color:'#5F7068', letterSpacing:'0.22em', fontWeight:700, marginBottom:4 }}>NEXT · 23:30</div>
          <div style={{ fontSize:13, color:'#A7B8AF', fontWeight:500, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', maxWidth:220 }}>{channel.next}</div>
        </div>
      </div>

      {/* bottom: big title + meta + CTAs */}
      <div style={{ position:'absolute', left:34, bottom:32, right:34 }}>
        <div className="mono" style={{ fontSize:10, color:accent, letterSpacing:'0.22em', fontWeight:700, marginBottom:12 }}>
          {channel.cat.toUpperCase()} · {channel.sub}
        </div>
        <div style={{
          fontSize:64, fontWeight:900, letterSpacing:'-0.025em', lineHeight:0.95,
          color:'#F0FFF6', textShadow:'0 4px 32px rgba(0,0,0,0.8)',
          marginBottom:20,
        }}>{channel.name}</div>
        <div style={{ display:'flex', gap:12 }}>
          <div className="hex-btn" style={{
            height:44, padding:'0 28px 0 22px',
            background:`linear-gradient(180deg, ${accent} 0%, #00B872 100%)`,
            color:'#04130C',
            display:'inline-flex', alignItems:'center', gap:10,
            fontSize:12, fontWeight:800, letterSpacing:'0.14em',
            boxShadow:`0 10px 26px ${accent}88, inset 0 1px 0 rgba(255,255,255,0.44)`,
            cursor:'pointer', whiteSpace:'nowrap',
          }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M7 5 L19 12 L7 19 Z"/></svg>
            OPEN FULLSCREEN
          </div>
          <div className="hex-btn" style={{
            height:44, padding:'0 22px',
            background:'rgba(255,255,255,0.06)', border:'1px solid rgba(255,255,255,0.14)',
            color:'#F0FFF6',
            display:'inline-flex', alignItems:'center', gap:8,
            fontSize:12, fontWeight:700, letterSpacing:'0.14em',
            cursor:'pointer', whiteSpace:'nowrap',
          }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="12,3 14.6,9 21,9.3 16,13.6 17.6,20 12,16.5 6.4,20 8,13.6 3,9.3 9.4,9" fill="none"/>
            </svg>
            FAVORITE
          </div>
          <div className="hex-btn" style={{
            height:44, padding:'0 22px',
            background:'rgba(255,255,255,0.06)', border:'1px solid rgba(255,255,255,0.14)',
            color:'#F0FFF6',
            display:'inline-flex', alignItems:'center', gap:8,
            fontSize:12, fontWeight:700, letterSpacing:'0.14em',
            cursor:'pointer', whiteSpace:'nowrap',
          }}>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="5" width="18" height="14" rx="1"/><path d="M3 10h18"/>
            </svg>
            TV GUIDE
          </div>
        </div>
      </div>
    </div>
  );
}

// -------------------- BOTTOM 3D COVERFLOW WHEEL --------------------
function ChannelWheelBottom({ channels, focusedIdx, setFocusedIdx, accent }) {
  const ORB_W = 160;
  const ORB_H = 220;
  const GAP = 172;

  return (
    <div style={{
      position:'absolute', left:260, right:0, bottom:0, height:540,
      overflow:'visible',
    }}>
      {/* heading row */}
      <div style={{
        position:'absolute', left:80, right:56, top:0,
        display:'flex', alignItems:'baseline', gap:12,
      }}>
        <div className="mono" style={{ fontSize:10, color:accent, letterSpacing:'0.22em', fontWeight:700, whiteSpace:'nowrap' }}>CHANNELS</div>
        <div style={{ fontSize:18, fontWeight:800, color:'#F0FFF6', letterSpacing:'-0.015em', whiteSpace:'nowrap' }}>On air now</div>
        <div style={{ flex:1, height:1, background:`linear-gradient(90deg, ${accent}33 0%, transparent 70%)` }} />
        <div className="mono" style={{ fontSize:10, color:'#5F7068', letterSpacing:'0.18em', whiteSpace:'nowrap' }}>
          {String(focusedIdx+1).padStart(2,'0')} / {String(channels.length).padStart(2,'0')}
        </div>
      </div>

      {/* wheel */}
      <div style={{
        position:'absolute', left:0, right:0, top:40, bottom:60,
        perspective:'1400px',
        perspectiveOrigin:'50% 50%',
      }}>
        <div style={{
          position:'absolute', left:'50%', top:'50%',
          transformStyle:'preserve-3d',
        }}>
          {channels.map((ch, i) => {
            const delta = i - focusedIdx;
            const absd = Math.abs(delta);
            if (absd > 5) return null;
            const focused = delta === 0;
            const tx = delta * GAP;
            const tz = focused ? 80 : -60 * absd;
            const rotY = delta * -28;
            const ty = absd * 14;
            const scale = focused ? 1.18 : 1 - absd * 0.06;
            const opacity = focused ? 1 : Math.max(0.22, 1 - absd * 0.2);

            return (
              <div key={ch.id}
                onMouseEnter={()=>setFocusedIdx(i)}
                onClick={()=>setFocusedIdx(i)}
                style={{
                  position:'absolute', left:-ORB_W/2, top:-ORB_H/2,
                  width:ORB_W, height:ORB_H,
                  transform:`translate3d(${tx}px, ${ty}px, ${tz}px) rotateY(${rotY}deg) scale(${scale})`,
                  transformStyle:'preserve-3d',
                  transition:'transform 520ms cubic-bezier(.22,1,.36,1), opacity 320ms ease',
                  opacity,
                  cursor:'pointer',
                  zIndex: 20 - absd,
                }}>
                <ChannelOrb item={ch} width={ORB_W} height={ORB_H} focused={focused} ringColor={accent} />
                {focused && (
                  <>
                    <div style={{
                      position:'absolute', left:0, right:0, top:ORB_H, height:90,
                      background: ch.art.bg,
                      transform:'scaleY(-1)',
                      opacity:0.28,
                      maskImage:'linear-gradient(180deg, rgba(0,0,0,0.7) 0%, transparent 90%)',
                      WebkitMaskImage:'linear-gradient(180deg, rgba(0,0,0,0.7) 0%, transparent 90%)',
                      filter:'blur(1px)',
                    }} />
                    <div style={{
                      position:'absolute', left:'50%', top:ORB_H+12,
                      transform:'translate(-50%,0)',
                      width:ORB_W*1.4, height:44, borderRadius:'50%',
                      background:`radial-gradient(ellipse at center, ${accent}bb 0%, transparent 70%)`,
                      filter:'blur(14px)',
                    }} />
                  </>
                )}
              </div>
            );
          })}
        </div>

        {/* edge fades */}
        <div style={{ position:'absolute', left:0, top:0, bottom:0, width:120, background:'linear-gradient(90deg, #050A08 0%, transparent 100%)', pointerEvents:'none', zIndex:40 }} />
        <div style={{ position:'absolute', right:0, top:0, bottom:0, width:120, background:'linear-gradient(270deg, #050A08 0%, transparent 100%)', pointerEvents:'none', zIndex:40 }} />
      </div>

      {/* nav controls */}
      <div style={{
        position:'absolute', left:0, right:0, bottom:8,
        display:'flex', justifyContent:'center', gap:12,
      }}>
        <button onClick={()=>setFocusedIdx(Math.max(0, focusedIdx-1))}
          className="hex-btn" style={{
          width:44, height:44, border:'none',
          background:'rgba(20,37,31,0.82)', color:'#F0FFF6',
          display:'flex', alignItems:'center', justifyContent:'center',
          cursor:'pointer', boxShadow:'0 8px 18px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.06)',
        }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M15 18 L9 12 L15 6"/></svg>
        </button>
        <div style={{
          display:'flex', alignItems:'center', gap:5,
          padding:'0 16px', height:44,
          background:'rgba(20,37,31,0.5)',
          clipPath:'polygon(10px 0, calc(100% - 10px) 0, 100% 50%, calc(100% - 10px) 100%, 10px 100%, 0 50%)',
        }}>
          {channels.map((_,i)=>{
            const isActive = i===focusedIdx;
            return <div key={i} style={{
              width: isActive? 18 : 6, height:6, borderRadius:3,
              background: isActive? accent : 'rgba(255,255,255,0.18)',
              transition:'all 280ms ease',
              boxShadow: isActive? `0 0 8px ${accent}` : 'none',
            }} />;
          })}
        </div>
        <button onClick={()=>setFocusedIdx(Math.min(channels.length-1, focusedIdx+1))}
          className="hex-btn" style={{
          width:44, height:44, border:'none',
          background:'rgba(20,37,31,0.82)', color:'#F0FFF6',
          display:'flex', alignItems:'center', justifyContent:'center',
          cursor:'pointer', boxShadow:'0 8px 18px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.06)',
        }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M9 6 L15 12 L9 18"/></svg>
        </button>
      </div>
    </div>
  );
}

// -------------------- LIVE TV SCREEN --------------------
function LiveTV({ accent }) {
  const [focusedIdx, setFocusedIdx] = useState_LT(3);
  const [activeCat, setActiveCat] = useState_LT('favorites');
  const [drawerOpen, setDrawerOpen] = useState_LT(false);

  const filtered = useMemo_LT(() => {
    if (activeCat === 'favorites' || activeCat === 'all') return LIVE_CHANNELS;
    return LIVE_CHANNELS.filter(c => c.cat.toLowerCase() === activeCat.toLowerCase());
  }, [activeCat]);
  const safeIdx = Math.min(focusedIdx, Math.max(0, filtered.length-1));
  const focused = filtered[safeIdx] || LIVE_CHANNELS[0];

  useEffect_LT(() => {
    const onKey = (e) => {
      if (e.key === 'ArrowRight') setFocusedIdx(i => Math.min(filtered.length-1, i+1));
      else if (e.key === 'ArrowLeft') setFocusedIdx(i => Math.max(0, i-1));
      else if (e.key === 'Escape' && drawerOpen) setDrawerOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [filtered.length, drawerOpen]);

  return (
    <>
      {/* AMBIENT BACKGROUND */}
      <div key={focused.id} style={{
        position:'absolute', left:260, right:0, top:0, bottom:0,
        background: focused.art.bg,
        filter:'blur(80px) saturate(1.3)',
        opacity:0.5,
        animation:'ambient-fade 600ms ease-out',
        pointerEvents:'none',
      }} />
      <div style={{
        position:'absolute', left:260, right:0, top:0, bottom:0,
        background:`
          radial-gradient(60% 60% at 50% 30%, transparent 0%, rgba(5,10,8,0.78) 100%),
          linear-gradient(180deg, rgba(5,10,8,0.35) 0%, rgba(5,10,8,0.9) 100%)
        `,
        pointerEvents:'none',
      }} />

      {/* Header */}
      <div style={{
        position:'absolute', left:336, top:40, display:'flex', alignItems:'baseline', gap:14, zIndex:5,
      }}>
        <div className="mono" style={{ fontSize:11, color:accent, letterSpacing:'0.26em', fontWeight:700 }}>LIVE TV</div>
        <div style={{ fontSize:26, fontWeight:900, color:'#F0FFF6', letterSpacing:'-0.02em' }}>{filtered.length} of 3,174 channels</div>
      </div>

      {/* Preview on top */}
      <LivePreviewTop channel={focused} accent={accent} />

      {/* Wheel on bottom */}
      <ChannelWheelBottom channels={filtered} focusedIdx={safeIdx} setFocusedIdx={setFocusedIdx} accent={accent} />

      {/* Category drawer — on top, toggleable */}
      <CategoryDrawer active={activeCat} setActive={(id)=>{ setActiveCat(id); setFocusedIdx(0); }}
        accent={accent} open={drawerOpen} setOpen={setDrawerOpen} />
    </>
  );
}

Object.assign(window, { LiveTV, LIVE_CHANNELS });
