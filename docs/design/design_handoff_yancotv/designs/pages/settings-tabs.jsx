// Settings page content — 14 tabs. Each tab is a function returning its content region.
// Shared layout in settings.html.

const { useState: sUS } = React;

// === Reusable Settings primitives ======================================
function Section({ title, sub, children, right }) {
  return (
    <div style={{ marginBottom:36 }}>
      <div style={{ display:'flex', alignItems:'baseline', gap:14, marginBottom: sub? 4 : 18 }}>
        <div style={{ fontSize:22, fontWeight:800, letterSpacing:'-0.01em', color:'var(--text-primary)' }}>{title}</div>
        <div style={{ flex:1, height:1, background:'linear-gradient(90deg, rgba(0,226,138,0.18) 0%, transparent 70%)' }} />
        {right}
      </div>
      {sub && <div style={{ fontSize:13, color:'var(--text-muted)', lineHeight:1.55, maxWidth:780, marginBottom:20 }}>{sub}</div>}
      {children}
    </div>
  );
}

function Row({ label, hint, right, kicker, children }) {
  return (
    <div className="y-card" style={{
      background:'rgba(10,20,16,0.5)',
      border:'1px solid var(--border-subtle)',
      padding:'18px 22px',
      display:'flex', alignItems:'center', gap:22, marginBottom:10,
    }}>
      <div style={{ flex:1, minWidth:0 }}>
        {kicker && <div className="kicker-d" style={{ marginBottom:4 }}>{kicker}</div>}
        <div style={{ fontSize:15, fontWeight:700, color:'var(--text-primary)' }}>{label}</div>
        {hint && <div style={{ fontSize:12, color:'var(--text-muted)', marginTop:4, lineHeight:1.5 }}>{hint}</div>}
        {children && <div style={{ marginTop:12 }}>{children}</div>}
      </div>
      {right && <div style={{ flexShrink:0, display:'flex', alignItems:'center', gap:10 }}>{right}</div>}
    </div>
  );
}

function ChipRow({ options, value, onChange }) {
  return (
    <div style={{ display:'flex', gap:8, flexWrap:'wrap' }}>
      {options.map(o => {
        const label = typeof o === 'string' ? o : o.label;
        const val = typeof o === 'string' ? o : o.value;
        return <HexChip key={val} active={val===value} onClick={()=>onChange && onChange(val)}>{label}</HexChip>;
      })}
    </div>
  );
}

function Slider({ value, min, max, step=1, unit, onChange, presets }) {
  const pct = ((value - min) / (max - min)) * 100;
  return (
    <div>
      <div style={{ display:'flex', alignItems:'center', gap:14 }}>
        <div style={{ position:'relative', flex:1, height:36, display:'flex', alignItems:'center' }}>
          <div style={{ position:'absolute', left:0, right:0, height:4, background:'rgba(255,255,255,0.08)', borderRadius:2 }}/>
          <div style={{ position:'absolute', left:0, width:`${pct}%`, height:4, background:'linear-gradient(90deg, var(--accent-deep) 0%, var(--accent) 100%)', borderRadius:2, boxShadow:'0 0 10px rgba(0,226,138,0.5)' }}/>
          <div className="y-chip" style={{
            position:'absolute', left:`calc(${pct}% - 12px)`, top:'50%', transform:'translateY(-50%)',
            width:24, height:24, background:'var(--accent)', boxShadow:'0 0 14px rgba(0,226,138,0.6), inset 0 1px 0 rgba(255,255,255,0.3)',
          }}/>
          <input type="range" min={min} max={max} step={step} value={value}
            onChange={e=>onChange(Number(e.target.value))}
            style={{ position:'absolute', inset:0, opacity:0, cursor:'pointer' }}/>
        </div>
        <div className="mono tab-nums" style={{ fontSize:16, fontWeight:700, color:'var(--accent)', minWidth:70, textAlign:'right' }}>{value}{unit||''}</div>
      </div>
      {presets && (
        <div style={{ display:'flex', gap:6, marginTop:10 }}>
          {presets.map(p => <HexChip key={p} tone="muted" onClick={()=>onChange(p)}>{p}{unit||''}</HexChip>)}
        </div>
      )}
    </div>
  );
}

function Select({ value, options, onChange }) {
  return (
    <div className="y-pill" style={{
      height:40, padding:'0 18px', display:'inline-flex', alignItems:'center', gap:10,
      background:'rgba(20,37,31,0.8)', border:'1px solid var(--border-subtle)',
      fontSize:13, color:'var(--text-primary)', fontWeight:600, cursor:'pointer', minWidth:220,
    }}>
      <span style={{flex:1}}>{value}</span>
      <Icon path={I.chevDn} size={13} />
    </div>
  );
}

function TextField({ value, placeholder, mono }) {
  return (
    <div style={{
      padding:'12px 16px', background:'rgba(0,0,0,0.3)',
      border:'1px solid var(--border-subtle)',
      fontFamily: mono? 'var(--font-mono)' : 'inherit',
      fontSize: mono? 12 : 14, color:value?'var(--text-primary)':'var(--text-muted)',
      clipPath:'polygon(10px 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%, 0 10px)',
    }}>{value || placeholder}</div>
  );
}

// === GENERAL =============================================================
function TabGeneral() {
  const [openOn, setOpenOn] = sUS('HOME');
  const [sidebar, setSidebar] = sUS('COLLAPSED');
  const [startLast, setStartLast] = sUS(true);
  const [autoLaunch, setAutoLaunch] = sUS(false);
  const [clock, setClock] = sUS(true);
  const [exitConfirm, setExitConfirm] = sUS(true);
  return (
    <div>
      <Section title="Language & region">
        <Row label="App language" hint="Used everywhere in the UI. Numbers always render Latin regardless of script."
          right={<Select value="English (United States)" />}>
          <div style={{ display:'flex', gap:6, marginTop:4 }}>
            <HexChip tone="muted" icon={I.lang}>EN-US</HexChip>
            <HexChip tone="muted">العربية</HexChip>
            <HexChip tone="muted">FR-FR</HexChip>
            <HexChip tone="muted">DE-DE</HexChip>
            <HexChip tone="muted">ES-ES</HexChip>
            <HexChip tone="muted">TR-TR</HexChip>
          </div>
        </Row>
        <Row label="Region / locale" hint="Affects date, time, number formatting."
          right={<Select value="United States" />} />
      </Section>

      <Section title="Startup">
        <Row label="Open app on" hint="Screen shown when YancoTV launches."
          right={<ChipRow value={openOn} onChange={setOpenOn} options={['HOME','LIVE TV','LAST USED']} />} />
        <Row label="Start on last channel" hint="Live TV resumes the last watched channel automatically."
          right={<Toggle value={startLast} onChange={setStartLast} />} />
        <Row label="Auto-launch on boot" hint="Android TV only. Opens YancoTV when the TV starts." kicker="TV ONLY"
          right={<Toggle value={autoLaunch} onChange={setAutoLaunch} />} />
      </Section>

      <Section title="Shell behaviour">
        <Row label="Sidebar behaviour" hint="Affects Home and Browse surfaces."
          right={<ChipRow value={sidebar} onChange={setSidebar} options={['COLLAPSED','EXPAND ON FOCUS']} />} />
        <Row label="Clock in player" hint="Tabular digits, top-right, 50% opacity."
          right={<Toggle value={clock} onChange={setClock} />} />
        <Row label="Exit confirmation" hint="Prompt before leaving the app via Back on the root screen."
          right={<Toggle value={exitConfirm} onChange={setExitConfirm} />} />
        <Row label="Channel number format" hint="How channel numbers are shown in the zap bar and guide."
          right={<ChipRow value="3-DIGIT" onChange={()=>{}} options={['NONE','3-DIGIT','GROUPED']} />} />
      </Section>
    </div>
  );
}

// === APPEARANCE ==========================================================
function TabAppearance() {
  const [theme, setTheme] = sUS('Frosted Emerald');
  const [accent, setAccent] = sUS('Emerald');
  const [fontScale, setFontScale] = sUS('100%');
  const [icon, setIcon] = sUS('Hex');

  const themes = [
    { id:'Frosted Emerald', hues:['#00E28A','#0A1410','#050A08'], desc:'Default · green-tinted canvas' },
    { id:'Midnight Sapphire', hues:['#3B6FE2','#0A1020','#050814'], desc:'Cool blue gradients' },
    { id:'Warm Amber', hues:['#E29B00','#14100A','#0A0806'], desc:'Golden highlights on near-black' },
    { id:'Monochrome', hues:['#E0E0E0','#1A1A1A','#0A0A0A'], desc:'No hue · high-contrast gray' },
  ];
  const accents = [
    { id:'Emerald', c:'#00E28A' },
    { id:'Sapphire', c:'#3B6FE2' },
    { id:'Amber', c:'#E29B00' },
    { id:'Monochrome', c:'#E0E0E0' },
  ];

  return (
    <div>
      <Section title="Theme" sub="Applied app-wide. Switching re-renders all surfaces.">
        <div style={{ display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap:14 }}>
          {themes.map(t => {
            const isActive = t.id === theme;
            return (
              <div key={t.id} onClick={()=>setTheme(t.id)} className="y-card" style={{
                height:180, cursor:'pointer', position:'relative',
                background: `linear-gradient(135deg, ${t.hues[1]} 0%, ${t.hues[2]} 100%)`,
                border: isActive ? `2px solid var(--accent)` : '1px solid var(--border-subtle)',
                boxShadow: isActive ? '0 20px 48px rgba(0,0,0,0.5), 0 0 0 1px var(--accent), 0 0 28px rgba(0,226,138,0.35)' : '0 12px 28px rgba(0,0,0,0.5)',
                padding:18,
              }}>
                {/* mini preview bars */}
                <div style={{ display:'flex', gap:4, marginBottom:10 }}>
                  <div style={{ width:20, height:4, background:t.hues[0], borderRadius:2 }}/>
                  <div style={{ width:10, height:4, background:'rgba(255,255,255,0.2)', borderRadius:2 }}/>
                </div>
                <div style={{ fontSize:14, fontWeight:800, color:'var(--text-primary)', marginBottom:4 }}>{t.id}</div>
                <div style={{ fontSize:10, color:'var(--text-muted)', lineHeight:1.5 }}>{t.desc}</div>
                <div style={{ position:'absolute', left:18, bottom:18, right:18, height:30, background:`linear-gradient(90deg, ${t.hues[0]}33 0%, ${t.hues[0]}11 100%)`, border:`1px solid ${t.hues[0]}66`, clipPath:'polygon(10px 0, calc(100% - 10px) 0, 100% 50%, calc(100% - 10px) 100%, 10px 100%, 0 50%)' }} />
                {isActive && <div style={{ position:'absolute', top:12, right:12, width:20, height:20, borderRadius:'50%', background:t.hues[0], display:'grid', placeItems:'center' }}><Icon path={I.check} size={12} stroke="2.4"/></div>}
              </div>
            );
          })}
        </div>
      </Section>

      <Section title="Accent">
        <Row label="Colour" hint="Tint applied to focus rings, progress, chips.">
          <div style={{ display:'flex', gap:10, marginTop:4 }}>
            {accents.map(a => (
              <div key={a.id} onClick={()=>setAccent(a.id)} className="y-hex" style={{
                padding:'0 18px', height:44, display:'inline-flex', alignItems:'center', gap:10,
                background: accent===a.id ? `linear-gradient(180deg, ${a.c}, ${a.c}88)` : 'rgba(20,37,31,0.7)',
                border: accent===a.id ? 'none' : '1px solid var(--border-subtle)',
                color: accent===a.id ? '#04130C' : 'var(--text-primary)',
                fontSize:12, fontWeight:700, letterSpacing:'0.1em', cursor:'pointer',
                boxShadow: accent===a.id ? `0 10px 22px ${a.c}66` : 'none',
              }}>
                <div style={{ width:14, height:14, borderRadius:'50%', background:a.c, boxShadow:`0 0 8px ${a.c}` }}/>
                {a.id}
              </div>
            ))}
          </div>
        </Row>
      </Section>

      <Section title="Type &amp; icons">
        <Row label="Font scale" hint="Multiplies base body size. 100% default.">
          <ChipRow value={fontScale} onChange={setFontScale} options={['90%','100%','110%','125%']} />
        </Row>
        <Row label="App icon">
          <div style={{ display:'flex', gap:10 }}>
            {['Hex','Shield','Wordmark'].map(i => (
              <div key={i} onClick={()=>setIcon(i)} className="y-card" style={{
                width:96, height:96, display:'grid', placeItems:'center', cursor:'pointer',
                background: icon===i ? 'linear-gradient(135deg, rgba(0,226,138,0.15), rgba(0,184,114,0.05))' : 'rgba(20,37,31,0.6)',
                border: icon===i ? '1px solid var(--accent)' : '1px solid var(--border-subtle)',
              }}>
                <div style={{ fontSize:36, fontWeight:900, color: icon===i? 'var(--accent)' : 'var(--text-muted)' }}>Y</div>
              </div>
            ))}
          </div>
        </Row>
      </Section>
    </div>
  );
}

// === PLAYBACK ============================================================
function TabPlayback() {
  const [resize, setResize] = sUS('FIT');
  const [hw, setHw] = sUS('HW+FALLBACK');
  const [buffer, setBuffer] = sUS('BALANCED');
  const [seek, setSeek] = sUS('10s');
  const [resume, setResume] = sUS(85);
  const [passthrough, setPassthrough] = sUS(false);
  const [matchFps, setMatchFps] = sUS(true);
  const [autoplay, setAutoplay] = sUS(true);
  const [longPress2x, setLongPress2x] = sUS(true);
  return (
    <div>
      <Section title="Video">
        <Row label="Resize" hint="How the stream's frame is mapped to the player's area. Fit preserves aspect ratio with letterboxing; Fill stretches to the screen edges; Zoom crops to fill without letterboxing."
          right={<ChipRow value={resize} onChange={setResize} options={['FIT','FILL','ZOOM','16:9','4:3','ORIGINAL']} />} />
        <Row label="Hardware decoder" hint="Software fallback enabled by default if HW decoder fails."
          right={<ChipRow value={hw} onChange={setHw} options={['HW','HW+FALLBACK','SW']} />} />
        <Row label="Buffer preset" hint="Low-latency = 500ms · Balanced = 2000ms · Stable = 5000ms"
          right={<ChipRow value={buffer} onChange={setBuffer} options={['LOW-LATENCY','BALANCED','STABLE']} />} />
        <Row label="Match display frame rate" hint="Android TV only. Switches display mode to match stream fps (24/30/60)." kicker="TV ONLY"
          right={<Toggle value={matchFps} onChange={setMatchFps} />} />
      </Section>

      <Section title="Controls">
        <Row label="Seek increment" hint="Used by back/fwd buttons and remote rewind/ff."
          right={<ChipRow value={seek} onChange={setSeek} options={['5s','10s','30s']} />} />
        <Row label="Long-press for 2× speed" hint="Hold SELECT to temporarily double playback speed."
          right={<Toggle value={longPress2x} onChange={setLongPress2x} />} />
        <Row label="Auto-play next episode" hint="When a series episode ends, continue to the next in the same season."
          right={<Toggle value={autoplay} onChange={setAutoplay} />} />
        <Row label="Resume threshold" hint="Below this percentage, 'Resume' appears; above, the title is marked watched.">
          <Slider value={resume} min={50} max={99} unit="%" onChange={setResume} />
        </Row>
      </Section>

      <Section title="Audio">
        <Row label="Audio passthrough" hint="Forward AC3/EAC3/DTS to your receiver instead of decoding."
          right={<Toggle value={passthrough} onChange={setPassthrough} />} />
        <Row label="Preferred audio language" hint="Applied when a stream ships multiple audio tracks. Two- or three-letter ISO 639 code."
          right={<Select value="English (en)" />} />
      </Section>
    </div>
  );
}

// === SUBTITLES ===========================================================
function TabSubtitles() {
  const [size, setSize] = sUS(100);
  const [bgOpacity, setBgOpacity] = sUS(40);
  const [position, setPosition] = sUS('BOTTOM');
  const [color, setColor] = sUS('#F0FFF6');
  const [outline, setOutline] = sUS(true);
  return (
    <div>
      {/* Live preview panel */}
      <div className="y-card-l" style={{
        height:220, marginBottom:32, position:'relative',
        background:`
          radial-gradient(120% 80% at 30% 30%, #0F1C17 0%, #0A1410 60%, #050A08 100%),
          linear-gradient(135deg, rgba(0,226,138,0.08), rgba(0,0,0,0.4))
        `,
        border:'1px solid var(--panel-border)', overflow:'hidden',
      }}>
        <div className="kicker-d" style={{ position:'absolute', top:16, left:20 }}>LIVE PREVIEW</div>
        <div className="mono" style={{ position:'absolute', top:16, right:20, fontSize:10, color:'var(--text-muted)', letterSpacing:'0.18em' }}>02:14 / 41:22</div>
        <div style={{
          position:'absolute', left:'50%',
          [position==='TOP'?'top':'bottom']: 30,
          transform:'translateX(-50%)',
          fontSize: 18 * size/100, fontWeight:500, color,
          textShadow: outline ? '0 0 3px #000, 0 0 3px #000, 0 0 6px #000' : 'none',
          padding:'4px 14px',
          background: `rgba(0,0,0,${bgOpacity/100})`,
          whiteSpace:'nowrap',
        }}>This is how your subtitles will appear while watching.</div>
      </div>

      <Section title="Language">
        <Row label="Preferred subtitle language" hint="Auto-selects a matching track when the stream provides subtitles."
          right={<Select value="English (en)" />} />
        <Row label="Secondary fallback" hint="Used if the preferred language is not available."
          right={<Select value="— None —" />} />
      </Section>

      <Section title="Appearance">
        <Row label="Font size" hint="As a percentage of the default size.">
          <Slider value={size} min={50} max={200} step={5} unit="%" onChange={setSize} presets={[75,100,125,150]} />
        </Row>
        <Row label="Text colour">
          <div style={{ display:'flex', gap:10 }}>
            {['#F0FFF6','#FFFF00','#00E28A','#66F0B5','#FF6B6B','#D7B36A','#A7B8AF'].map(c => (
              <div key={c} onClick={()=>setColor(c)} className="y-chip" style={{
                width:34, height:34, cursor:'pointer', background:c,
                boxShadow: color===c ? `0 0 0 2px var(--text-primary), 0 0 0 4px ${c}` : 'inset 0 1px 0 rgba(255,255,255,0.3)',
              }}/>
            ))}
          </div>
        </Row>
        <Row label="Background opacity">
          <Slider value={bgOpacity} min={0} max={100} step={5} unit="%" onChange={setBgOpacity} />
        </Row>
        <Row label="Position"
          right={<ChipRow value={position} onChange={setPosition} options={['TOP','BOTTOM']} />} />
        <Row label="Outline"
          right={<Toggle value={outline} onChange={setOutline} />} />
      </Section>
    </div>
  );
}

// === NETWORK =============================================================
function TabNetwork() {
  const [ua, setUa] = sUS('VLC');
  const [connect, setConnect] = sUS(15);
  const [read, setRead] = sUS(30);
  const [proxy, setProxy] = sUS(false);
  const [testStatus, setTestStatus] = sUS('OK · 142ms');
  return (
    <div>
      <Section title="HTTP">
        <Row label="User-Agent preset" hint="Select a preset or type a custom string."
          right={<Select value={ua} />}>
          <div style={{ display:'flex', gap:6, marginTop:6 }}>
            {['VLC','ExoPlayer','Kodi','Smart TV','Chrome Android','Custom…'].map(p =>
              <HexChip key={p} active={p===ua} onClick={()=>setUa(p)}>{p}</HexChip>
            )}
          </div>
          {ua==='Custom…' && (
            <div style={{ marginTop:10 }}>
              <TextField mono value="Mozilla/5.0 (Linux; Android 13; BRAVIA) AppleWebKit/537.36" />
            </div>
          )}
        </Row>
        <Row label="Connect timeout" hint="How long to wait for TCP connection before giving up.">
          <Slider value={connect} min={1} max={60} unit="s" onChange={setConnect} presets={[5,15,30]} />
        </Row>
        <Row label="Read timeout" hint="Max idle time on an active stream before reconnecting.">
          <Slider value={read} min={1} max={600} unit="s" onChange={setRead} presets={[10,30,60,180]} />
        </Row>
      </Section>

      <Section title="Diagnostics">
        <Row label="Test connection" hint="Makes a HEAD request to the first active source."
          right={<>
            <HexChip tone={testStatus.startsWith('OK') ? 'active' : 'live'} active={testStatus.startsWith('OK')}>{testStatus}</HexChip>
            <HexBtn icon={I.retry} onClick={()=>setTestStatus('OK · 142ms')}>RUN TEST</HexBtn>
          </>}/>
      </Section>

      <Section title="HTTP proxy" sub="Forward all requests through a proxy server. Leave disabled for direct connections.">
        <Row label="Enabled" right={<Toggle value={proxy} onChange={setProxy} />} />
        {proxy && (
          <>
            <Row label="Host"><TextField mono value="proxy.example.net" /></Row>
            <Row label="Port"><TextField mono value="8080" /></Row>
            <Row label="Username (optional)"><TextField placeholder="— none —" /></Row>
            <Row label="Password (optional)"><TextField placeholder="— none —" /></Row>
          </>
        )}
      </Section>
    </div>
  );
}

// === SOURCES =============================================================
function TabSources() {
  const [autoSync, setAutoSync] = sUS(true);
  const [syncEvery, setSyncEvery] = sUS(12);
  const [wifiOnly, setWifiOnly] = sUS(true);
  const [parallel, setParallel] = sUS(2);
  const [epgStrict, setEpgStrict] = sUS('LOOSE');
  const [conflict, setConflict] = sUS('HIGHEST');

  // mini source list — deep-link to full manager lives at sources.html
  const sources = [
    { name:'BrittBox Premium',    kind:'XTREAM-CODES', chans:3812, vod:18206, state:'SYNCED',   age:'9h' },
    { name:'EuroSky Portal',      kind:'M3U + EPG',    chans: 984, vod:    0, state:'SYNCED',   age:'2h' },
    { name:'MENA Reseller',       kind:'XTREAM-CODES', chans:6210, vod:42188, state:'SYNCING',  age:'—'  },
    { name:'Kids Zone (sub-list)',kind:'STALKER',      chans: 214, vod:  882, state:'STALE',    age:'4d' },
    { name:'Local DVB-T (tuner)', kind:'HDHOMERUN',    chans:  48, vod:    0, state:'ERROR',    age:'—'  },
  ];
  const tone = (s) => s==='SYNCED' ? 'active' : s==='SYNCING' ? 'default' : s==='STALE' ? 'premium' : 'live';

  const jump = (hash) => { window.location.href = 'sources.html' + (hash || ''); };

  return (
    <div>
      <Section
        title="Sources"
        sub="Playlists, portals and tuners that feed YancoTV. The full manager lives in a dedicated surface — use the deep links below to add, edit or diagnose an individual source."
        right={<>
          <HexBtn icon={I.link} onClick={()=>jump('')}>OPEN MANAGER</HexBtn>
          <HexBtn primary icon={I.plus} onClick={()=>jump('#add')}>ADD SOURCE</HexBtn>
        </>}
      />

      {/* Summary strip */}
      <div style={{ display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap:10, marginBottom:28 }}>
        {[
          { k:'SOURCES',       v:'5',      sub:'4 active · 1 error',    col:'var(--accent)' },
          { k:'LIVE CHANNELS', v:'11,268', sub:'deduped across lists',  col:'var(--text-primary)' },
          { k:'VOD TITLES',    v:'61,276', sub:'movies + series',       col:'var(--text-primary)' },
          { k:'LAST SYNC',     v:'2h ago', sub:'next in 10h · auto',    col:'var(--accent-soft)' },
        ].map(s => (
          <div key={s.k} className="y-card" style={{
            padding:'18px 20px', background:'rgba(10,20,16,0.5)', border:'1px solid var(--border-subtle)',
          }}>
            <div className="kicker-d">{s.k}</div>
            <div className="mono tab-nums" style={{ fontSize:32, fontWeight:900, color:s.col, marginTop:6, letterSpacing:'-0.02em' }}>{s.v}</div>
            <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:4 }}>{s.sub}</div>
          </div>
        ))}
      </div>

      <Section title="Installed sources" sub="Select any row to jump into the full manager with that source pre-selected.">
        {sources.map((s, i) => (
          <div key={i} className="y-card" onClick={()=>jump('#src-'+i)} style={{
            background:'rgba(10,20,16,0.5)',
            border:'1px solid var(--border-subtle)',
            padding:'16px 20px', marginBottom:8,
            display:'flex', alignItems:'center', gap:18, cursor:'pointer',
          }}>
            <div className="y-hex" style={{
              width:44, height:44, display:'grid', placeItems:'center',
              background: s.state==='ERROR' ? 'rgba(255,107,107,0.14)' : 'rgba(0,226,138,0.14)',
              color: s.state==='ERROR' ? 'var(--error)' : 'var(--accent)',
              flexShrink:0,
            }}>
              <Icon path={s.state==='ERROR' ? I.warn : I.link} size={18}/>
            </div>
            <div style={{ minWidth:260 }}>
              <div style={{ fontSize:15, fontWeight:800, color:'var(--text-primary)' }}>{s.name}</div>
              <div className="mono" style={{ fontSize:10, color:'var(--text-muted)', letterSpacing:'0.12em', marginTop:3 }}>{s.kind}</div>
            </div>
            <div style={{ flex:1, display:'flex', gap:24 }}>
              <div>
                <div className="kicker-d">LIVE</div>
                <div className="mono tab-nums" style={{ fontSize:15, fontWeight:700, color:'var(--text-primary)', marginTop:2 }}>{s.chans.toLocaleString()}</div>
              </div>
              <div>
                <div className="kicker-d">VOD</div>
                <div className="mono tab-nums" style={{ fontSize:15, fontWeight:700, color:'var(--text-primary)', marginTop:2 }}>{s.vod.toLocaleString()}</div>
              </div>
              <div>
                <div className="kicker-d">LAST SYNC</div>
                <div className="mono tab-nums" style={{ fontSize:15, fontWeight:700, color:'var(--text-primary)', marginTop:2 }}>{s.age}</div>
              </div>
            </div>
            <HexChip tone={tone(s.state)} active={s.state==='SYNCED'}>{s.state}</HexChip>
            <Icon path={I.chevRt} size={14}/>
          </div>
        ))}
      </Section>

      <Section title="Sync schedule" sub="Applies to every source unless overridden in its individual settings.">
        <Row label="Auto-sync sources" hint="Refresh playlists and EPG in the background without opening the app."
          right={<Toggle value={autoSync} onChange={setAutoSync} />} />
        {autoSync && (
          <>
            <Row label="Sync every" hint="Hours between full refresh cycles. Individual sources may declare a shorter TTL.">
              <Slider value={syncEvery} min={1} max={48} unit="h" onChange={setSyncEvery} presets={[4,12,24]} />
            </Row>
            <Row label="Wi-Fi only"
              hint="Skip background sync on metered cellular / ethernet-tagged-metered connections."
              right={<Toggle value={wifiOnly} onChange={setWifiOnly} />} />
            <Row label="Parallel downloads" hint="How many sources YancoTV will sync simultaneously. Higher = faster but more RAM + bandwidth.">
              <Slider value={parallel} min={1} max={6} onChange={setParallel} presets={[1,2,4]} />
            </Row>
          </>
        )}
      </Section>

      <Section title="Merge &amp; conflict rules" sub="When the same channel or title appears in multiple sources, YancoTV decides which to keep using these rules.">
        <Row label="EPG match strictness" hint="LOOSE merges on channel name + region. STRICT requires exact tvg-id. STRICT avoids false matches but loses some guide data.">
          <ChipRow options={['LOOSE', 'BALANCED', 'STRICT']} value={epgStrict} onChange={setEpgStrict} />
        </Row>
        <Row label="Duplicate priority" hint="Which copy wins when a channel appears in multiple sources."
          right={<Select value={conflict==='HIGHEST' ? 'Highest-priority source wins' : conflict==='NEWEST' ? 'Newest added wins' : 'Ask me each time'} />}>
          <div style={{ display:'flex', gap:6, marginTop:6 }}>
            {['HIGHEST','NEWEST','ASK'].map(p =>
              <HexChip key={p} active={p===conflict} onClick={()=>setConflict(p)}>{p}</HexChip>
            )}
          </div>
        </Row>
      </Section>
    </div>
  );
}

// === GROUPS ==============================================================
function TabGroups() {
  const [type, setType] = sUS('LIVE');
  const rows = [
    { n:'★ Favorites', c:12, pin:true, hide:false },
    { n:'4K | UHD 3840P', c:48, pin:true, hide:false },
    { n:'Sports', c:312, pin:false, hide:false },
    { n:'News', c:108, pin:false, hide:false },
    { n:'Arabic', c:422, pin:false, hide:false },
    { n:'Kids', c:64, pin:false, hide:true },
    { n:'Music', c:98, pin:false, hide:false },
    { n:'Movies', c:2103, pin:false, hide:false },
    { n:'Adult', c:88, pin:false, hide:true },
  ];
  return (
    <div>
      <Section title="Groups"
        sub="Re-order, pin or hide content groups. Changes apply to the rail order on Home, Browse, Live TV categories and the guide."
        right={<ChipRow value={type} onChange={setType} options={['LIVE','MOVIES','SERIES']} />}>
        {rows.map((r,i)=>(
          <div key={r.n} className="y-card" style={{
            padding:'16px 20px', display:'flex', alignItems:'center', gap:18,
            background:'rgba(10,20,16,0.55)', border:'1px solid var(--border-subtle)',
            marginBottom:8, opacity: r.hide? 0.55 : 1,
          }}>
            <div style={{ color:'var(--text-muted)', cursor:'grab' }}><Icon path={I.drag} size={18}/></div>
            <div className="mono tab-nums" style={{ fontSize:11, color:'var(--text-muted)', width:32, letterSpacing:'0.1em' }}>{String(i+1).padStart(2,'0')}</div>
            <div style={{ flex:1, display:'flex', alignItems:'center', gap:10 }}>
              <span style={{ fontSize:15, fontWeight:700, color:'var(--text-primary)' }}>{r.n}</span>
              {r.pin && <HexChip tone="muted">PINNED</HexChip>}
              {r.hide && <HexChip tone="muted">HIDDEN</HexChip>}
            </div>
            <div className="mono tab-nums" style={{ fontSize:12, color:'var(--text-muted)' }}>{r.c.toLocaleString()} CH</div>
            <HexChip tone={r.pin?'active':'muted'} active={r.pin}>PIN</HexChip>
            <Toggle value={!r.hide} onChange={()=>{}} />
          </div>
        ))}
        <div style={{ display:'flex', justifyContent:'space-between', marginTop:10, color:'var(--text-muted)', fontSize:12 }}>
          <span>2 hidden</span>
          <a href="#" style={{ color:'var(--accent)', fontWeight:700 }}>Show hidden →</a>
        </div>
      </Section>
    </div>
  );
}

// === EPG =================================================================
function TabEPG() {
  const [forward, setForward] = sUS(7);
  const [back, setBack] = sUS(1);
  const [duration, setDuration] = sUS('90');
  const [rowHeight, setRowHeight] = sUS('NORMAL');
  const [nowLine, setNowLine] = sUS(true);
  const [time24, setTime24] = sUS(true);
  const [leadTime, setLeadTime] = sUS(5);
  return (
    <div>
      <Section title="Timeline">
        <Row label="Days forward" hint="How many days of upcoming programmes to fetch and display.">
          <Slider value={forward} min={1} max={14} unit="d" onChange={setForward} presets={[1,3,7,14]} />
        </Row>
        <Row label="Days backward" hint="How many days of historical programmes to keep — used for catch-up.">
          <Slider value={back} min={0} max={14} unit="d" onChange={setBack} presets={[0,1,3,7]} />
        </Row>
        <Row label="Timeline duration" hint="How many minutes of the timeline are visible in the guide."
          right={<ChipRow value={duration} onChange={setDuration} options={['30','60','90','120','180']} />} />
        <Row label="Row height"
          right={<ChipRow value={rowHeight} onChange={setRowHeight} options={['COMPACT','NORMAL','SPACIOUS']} />} />
        <Row label="Now-line" hint="Vertical accent line marking the current time in the guide."
          right={<Toggle value={nowLine} onChange={setNowLine} />} />
      </Section>

      <Section title="Time &amp; calendar">
        <Row label="24-hour time" right={<Toggle value={time24} onChange={setTime24} />} />
        <Row label="First day of week"
          right={<ChipRow value="MON" onChange={()=>{}} options={['MON','SUN']} />} />
      </Section>

      <Section title="Source priority" sub="When multiple sources provide EPG for the same channel, the top source wins. Drag to reorder.">
        {['BrittBox · Xtream','iptv-org · public','sbs-tv.xml · URL'].map((s,i)=>(
          <div key={s} className="y-card" style={{
            padding:'14px 18px', display:'flex', alignItems:'center', gap:16,
            background:'rgba(10,20,16,0.55)', border:'1px solid var(--border-subtle)', marginBottom:8,
          }}>
            <div style={{ color:'var(--text-muted)', cursor:'grab' }}><Icon path={I.drag} size={18}/></div>
            <div className="mono tab-nums" style={{ fontSize:11, color:'var(--accent)', width:26 }}>{String(i+1).padStart(2,'0')}</div>
            <div style={{ flex:1, fontSize:14, fontWeight:700 }}>{s}</div>
            <div className="mono" style={{ fontSize:11, color:'var(--text-muted)', letterSpacing:'0.16em' }}>214,293 PGM</div>
          </div>
        ))}
      </Section>

      <Section title="Reminders">
        <Row label="Lead time before programme start" hint="You'll be nudged this many minutes before a saved programme begins.">
          <Slider value={leadTime} min={0} max={30} unit=" min" onChange={setLeadTime} presets={[0,5,10,15]} />
        </Row>
      </Section>
    </div>
  );
}

// === PARENTAL ============================================================
function TabParental() {
  const [hideAdult, setHideAdult] = sUS(true);
  const [requireSettings, setRequireSettings] = sUS(false);
  return (
    <div>
      <Section title="PIN" sub="A 4-digit PIN is required to open hidden groups, adult content, or Settings when enabled.">
        <Row label="PIN">
          <div style={{ display:'flex', gap:10 }}>
            {[0,0,0,0].map((_,i)=>(
              <div key={i} className="y-chip" style={{
                width:60, height:72,
                display:'grid', placeItems:'center',
                background:'rgba(0,0,0,0.4)', border:'1px solid var(--border-subtle)',
                fontSize:32, fontWeight:900, color:'var(--text-primary)',
              }}>•</div>
            ))}
          </div>
          <div style={{ display:'flex', gap:8, marginTop:12 }}>
            <HexBtn primary icon={I.key}>CHANGE PIN</HexBtn>
            <HexBtn>RESET PIN</HexBtn>
          </div>
        </Row>
      </Section>

      <Section title="Content">
        <Row label="Hide adult content" hint="Channels and groups tagged as adult are removed from all lists."
          right={<Toggle value={hideAdult} onChange={setHideAdult} />} />
        <Row label="Require PIN for Settings" hint="When enabled, opening Settings prompts for the parental PIN."
          right={<Toggle value={requireSettings} onChange={setRequireSettings} />} />
        <Row label="Keyword block" hint="Programmes whose title contains any of these words are hidden from the guide.">
          <div style={{ display:'flex', gap:8, flexWrap:'wrap' }}>
            {['explicit','18+','mature','XXX'].map(k => (
              <div key={k} className="y-chip" style={{ padding:'6px 12px', background:'rgba(255,107,107,0.12)', border:'1px solid rgba(255,107,107,0.3)', color:'var(--error)', fontSize:11, fontWeight:700, fontFamily:'var(--font-mono)', letterSpacing:'0.1em', display:'inline-flex', alignItems:'center', gap:6 }}>
                {k} <Icon path={I.close} size={10}/>
              </div>
            ))}
            <div className="y-chip" style={{ padding:'6px 12px', background:'rgba(255,255,255,0.04)', border:'1px dashed var(--border-subtle)', color:'var(--text-muted)', fontSize:11, fontFamily:'var(--font-mono)', cursor:'pointer' }}>+ ADD WORD</div>
          </div>
        </Row>
      </Section>

      <Section title="Hidden channels">
        <Row label="Bulk unhide"
          hint="88 adult-tagged channels, 12 manually hidden. Enter the parental PIN to unhide selected items.">
          <div style={{ display:'flex', gap:8 }}>
            <HexBtn icon={I.shield}>OPEN HIDDEN LIST</HexBtn>
            <HexChip tone="muted">100 HIDDEN</HexChip>
          </div>
        </Row>
      </Section>
    </div>
  );
}

// === RECORDINGS ==========================================================
function TabRecordings() {
  const [quota, setQuota] = sUS(64);
  const [pre, setPre] = sUS(60);
  const [post, setPost] = sUS(120);
  const [auto, setAuto] = sUS(30);
  return (
    <div>
      <Section title="Storage">
        <Row label="Recording location" hint="External USB or internal storage. Must have at least 2GB free."
          right={<>
            <Select value="/storage/usb/yanco-recordings" />
            <HexBtn icon={I.folder}>BROWSE</HexBtn>
          </>} />
        <Row label="Storage quota cap" hint="Stop recording when this much disk has been used.">
          <Slider value={quota} min={4} max={512} unit=" GB" onChange={setQuota} presets={[16,64,128,256]} />
        </Row>
      </Section>
      <Section title="Padding">
        <Row label="Pre-record padding" hint="Begin recording this many seconds before programme start.">
          <Slider value={pre} min={0} max={600} unit="s" onChange={setPre} presets={[0,30,60,120]} />
        </Row>
        <Row label="Post-record padding" hint="Keep recording this many seconds after programme end.">
          <Slider value={post} min={0} max={600} unit="s" onChange={setPost} presets={[0,60,120,300]} />
        </Row>
      </Section>
      <Section title="Retention">
        <Row label="Auto-delete after" hint="Recorded programmes are deleted once they exceed this age.">
          <Slider value={auto} min={0} max={365} unit=" days" onChange={setAuto} presets={[7,14,30,90]} />
        </Row>
      </Section>
      <Section title="Active recordings">
        <div className="y-card" style={{ padding:18, background:'rgba(10,20,16,0.55)' }}>
          <div style={{ display:'flex', alignItems:'center', gap:14 }}>
            <div className="live-dot"/>
            <div style={{ flex:1 }}>
              <div style={{ fontSize:14, fontWeight:700 }}>PSG vs Lyon · beIN SPORTS 1</div>
              <div className="mono" style={{ fontSize:11, color:'var(--text-muted)', marginTop:3 }}>21:00 → 23:15 · 412 MB · 412 MB/h</div>
            </div>
            <HexChip tone="live">RECORDING · 01:23:47</HexChip>
            <HexBtn>STOP</HexBtn>
          </div>
        </div>
      </Section>
    </div>
  );
}

// === NOTIFICATIONS =======================================================
function TabNotifications() {
  const [reminders, setReminders] = sUS(true);
  const [recEvents, setRecEvents] = sUS(true);
  const [syncErr, setSyncErr] = sUS(true);
  const [newContent, setNewContent] = sUS(false);
  return (
    <div>
      <div className="y-card" style={{
        padding:'18px 22px', marginBottom:32, display:'flex', alignItems:'center', gap:18,
        background:'linear-gradient(135deg, rgba(0,226,138,0.1), rgba(0,226,138,0.02))',
        border:'1px solid rgba(0,226,138,0.35)',
      }}>
        <div style={{ width:44, height:44, background:'rgba(0,226,138,0.15)', borderRadius:'50%', display:'grid', placeItems:'center', color:'var(--accent)' }}>
          <Icon path={I.bell} size={20}/>
        </div>
        <div style={{ flex:1 }}>
          <div style={{ fontSize:14, fontWeight:700, color:'var(--text-primary)' }}>Notification permission · GRANTED</div>
          <div style={{ fontSize:12, color:'var(--text-muted)', marginTop:3 }}>YancoTV can post reminders and recording events through the Android notification system.</div>
        </div>
        <HexBtn>OPEN SYSTEM SETTINGS</HexBtn>
      </div>

      <Section title="Categories">
        <Row label="Programme reminders" hint="Fire a notification N minutes before a saved programme begins." kicker="EPG"
          right={<Toggle value={reminders} onChange={setReminders} />} />
        <Row label="Recording events" hint="Recording started / stopped / failed." kicker="DVR"
          right={<Toggle value={recEvents} onChange={setRecEvents} />} />
        <Row label="Sync errors" hint="When a source fails to refresh (bad credentials, timeout, etc.)." kicker="SOURCES"
          right={<Toggle value={syncErr} onChange={setSyncErr} />} />
        <Row label="New content" hint="New movies or series episodes detected in your sources." kicker="DISCOVERY"
          right={<Toggle value={newContent} onChange={setNewContent} />} />
      </Section>

      <Section title="Quiet hours" sub="Mute non-critical notifications during this window.">
        <Row label="Time range" right={<>
          <Select value="22:00" /> <span style={{color:'var(--text-muted)'}}>→</span> <Select value="08:00" />
        </>}/>
      </Section>
    </div>
  );
}

// === STORAGE =============================================================
function TabStorage() {
  const buckets = [
    { n:'Images · posters &amp; thumbs', size:412, col:'var(--accent)' },
    { n:'EPG cache', size:118, col:'var(--accent-soft)' },
    { n:'Media buffer', size:204, col:'var(--premium)' },
    { n:'Logs &amp; diagnostics', size:12, col:'var(--text-muted)' },
  ];
  const total = buckets.reduce((s,b)=>s+b.size,0);
  const free = 1234;
  return (
    <div>
      <Section title="Cache usage">
        <div className="y-card" style={{ padding:24, background:'rgba(10,20,16,0.55)' }}>
          <div style={{ display:'flex', alignItems:'baseline', gap:14, marginBottom:18 }}>
            <div className="mono tab-nums" style={{ fontSize:32, fontWeight:900, color:'var(--text-primary)' }}>{total}<span style={{fontSize:16, color:'var(--text-muted)', marginLeft:6}}>MB used</span></div>
            <div style={{ flex:1 }}/>
            <div className="mono tab-nums" style={{ fontSize:13, color:'var(--text-muted)' }}>{free} MB free on disk</div>
          </div>
          {/* stacked bar */}
          <div style={{ height:14, display:'flex', gap:2, marginBottom:14, borderRadius:4, overflow:'hidden' }}>
            {buckets.map(b=>(
              <div key={b.n} style={{ flex:b.size, background:b.col, boxShadow:`inset 0 1px 0 rgba(255,255,255,0.3)` }}/>
            ))}
          </div>
          <div style={{ display:'grid', gridTemplateColumns:'repeat(4,1fr)', gap:14 }}>
            {buckets.map(b=>(
              <div key={b.n}>
                <div style={{ display:'flex', alignItems:'center', gap:6, fontSize:11, color:'var(--text-muted)', fontFamily:'var(--font-mono)', letterSpacing:'0.1em', marginBottom:4 }}>
                  <span style={{ width:10, height:10, background:b.col }}/>
                  <span>{b.n.toUpperCase()}</span>
                </div>
                <div className="mono tab-nums" style={{ fontSize:18, fontWeight:700 }}>{b.size} <span style={{ fontSize:11, color:'var(--text-muted)' }}>MB</span></div>
              </div>
            ))}
          </div>
        </div>
      </Section>
      <Section title="Clear">
        <Row label="Clear image cache" hint="Posters, thumbnails, channel logos." right={<HexBtn icon={I.trash}>CLEAR · 412 MB</HexBtn>} />
        <Row label="Clear EPG cache" hint="All fetched programme data will be re-downloaded on next sync." right={<HexBtn icon={I.trash}>CLEAR · 118 MB</HexBtn>} />
        <Row label="Clear media buffer" right={<HexBtn icon={I.trash}>CLEAR · 204 MB</HexBtn>} />
        <Row label="Clear logs" right={<HexBtn icon={I.trash}>CLEAR · 12 MB</HexBtn>} />
      </Section>
    </div>
  );
}

// === SHORTCUTS ===========================================================
function TabShortcuts() {
  const entries = [
    { s:'↑ / ↓', a:'Channel up / down', cat:'LIVE PLAYER' },
    { s:'← / →', a:'Seek -/+ 10s', cat:'VOD PLAYER' },
    { s:'CENTER', a:'Play · pause · show controls', cat:'PLAYER' },
    { s:'MENU', a:'Open options sheet', cat:'PLAYER' },
    { s:'INFO', a:'Show quick-info card', cat:'LIVE PLAYER' },
    { s:'0–9', a:'Type channel number', cat:'LIVE PLAYER' },
    { s:'BACK', a:'Dismiss overlay · exit to browse', cat:'GLOBAL' },
    { s:'HOME', a:'Return to Home surface', cat:'GLOBAL' },
    { s:'HOLD SELECT', a:'Favorite toggle on focused card', cat:'BROWSE' },
    { s:'HOLD SELECT', a:'2× speed while held', cat:'VOD PLAYER' },
    { s:'CH +/- (phone)', a:'Volume up / down', cat:'PHONE' },
    { s:'LONG PRESS MENU', a:'Open Settings', cat:'GLOBAL' },
  ];
  return (
    <div>
      <Section title="Remote &amp; keyboard" sub="Editable in a future release (MK.18.4). Today: read-only reference.">
        <div style={{ display:'grid', gridTemplateColumns:'repeat(2,1fr)', gap:10 }}>
          {entries.map((e,i)=>(
            <div key={i} className="y-card" style={{
              padding:'14px 18px', display:'flex', alignItems:'center', gap:14,
              background:'rgba(10,20,16,0.55)', border:'1px solid var(--border-subtle)',
            }}>
              <div className="y-chip mono tab-nums" style={{
                minWidth:90, padding:'6px 12px',
                background:'rgba(0,226,138,0.12)', border:'1px solid rgba(0,226,138,0.35)',
                color:'var(--accent)', fontSize:11, fontWeight:700, letterSpacing:'0.1em',
                textAlign:'center',
              }}>{e.s}</div>
              <div style={{ flex:1, fontSize:13, fontWeight:600 }}>{e.a}</div>
              <div className="kicker-d">{e.cat}</div>
            </div>
          ))}
        </div>
      </Section>
    </div>
  );
}

// === ABOUT ===============================================================
function TabAbout() {
  return (
    <div>
      <Section title="Version">
        <div className="y-card-l" style={{ padding:28, background:'rgba(10,20,16,0.55)', border:'1px solid var(--panel-border)' }}>
          <div style={{ display:'flex', alignItems:'center', gap:24 }}>
            <div className="y-card" style={{ width:80, height:80, background:'linear-gradient(135deg, var(--accent) 0%, var(--accent-deep) 100%)', display:'grid', placeItems:'center', color:'#04130C', fontSize:44, fontWeight:900 }}>Y</div>
            <div>
              <div style={{ fontSize:32, fontWeight:900, letterSpacing:'-0.02em' }}>YancoTV</div>
              <div style={{ fontSize:13, color:'var(--text-secondary)', marginTop:4 }}>Version <span className="mono tab-nums" style={{color:'var(--text-primary)'}}>1.12.0</span> · Build <span className="mono tab-nums" style={{color:'var(--text-primary)'}}>2026042403</span></div>
              <div style={{ fontSize:12, color:'var(--text-muted)', marginTop:8 }}>Android TV · Jetpack Compose · Media3 · Frosted Glass Emerald</div>
            </div>
            <div style={{ flex:1 }}/>
            <HexBtn primary icon={I.sync}>CHECK FOR UPDATES</HexBtn>
          </div>
        </div>
      </Section>

      <Section title="Diagnostics">
        <Row label="Export diagnostics" hint="Bundles crash.log, prefs and a source list (minus credentials) into a share-sheet intent."
          right={<HexBtn icon={I.external}>EXPORT · SHARE</HexBtn>} />
        <Row label="Privacy policy" right={<HexBtn icon={I.external}>OPEN</HexBtn>} />
        <Row label="Open-source licences" hint="Third-party libraries: Media3 · Compose · OkHttp · Coil · AndroidX · Kotlin."
          right={<HexBtn icon={I.chevR}>VIEW LIST</HexBtn>} />
      </Section>

      <Section title="App data" sub="Destructive. Double-confirm before proceeding.">
        <Row label="Import settings" hint="Restore a previously exported .yanco-backup file."
          right={<HexBtn icon={I.cloud}>IMPORT JSON</HexBtn>} />
        <Row label="Export settings" hint="Backs up preferences, sources (credentials scrubbed), favorites, groups to a JSON file."
          right={<HexBtn icon={I.external}>EXPORT JSON</HexBtn>} />
        <Row label="Reset app data" kicker="DESTRUCTIVE" hint="Clears every preference and source. Requires typing DELETE."
          right={<HexBtn icon={I.trash} style={{ background:'rgba(255,107,107,0.12)', color:'var(--error)', border:'1px solid rgba(255,107,107,0.4)' }}>RESET…</HexBtn>} />
      </Section>
    </div>
  );
}

Object.assign(window, {
  TabGeneral, TabAppearance, TabPlayback, TabSubtitles, TabNetwork,
  TabGroups, TabEPG, TabParental, TabRecordings, TabNotifications,
  TabStorage, TabShortcuts, TabAbout,
});
