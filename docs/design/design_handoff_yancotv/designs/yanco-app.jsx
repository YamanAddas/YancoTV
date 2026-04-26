// YancoTV+ — main app. Hex + emerald + 3D.

const { useState, useEffect, useMemo } = React;

function App() {
  const [tweaks, setTweak] = useTweaks(/*EDITMODE-BEGIN*/{
    "accentName": "Emerald",
    "hexIntensity": "Balanced",
    "bgTone": "Green-tint",
    "showRemoteBar": true
  }/*EDITMODE-END*/);

  const ACCENT_MAP = {
    'Emerald': '#00E28A',
    'Lime':    '#7CFF5A',
    'Teal':    '#00C8A0',
    'Mint':    '#4ADE80',
    'Cyan':    '#00C8FF',
  };
  const accent = ACCENT_MAP[tweaks.accentName] || '#00E28A';

  const [activeNav, setActiveNav] = useState('home');
  const [activeCat] = useState('favorites');
  const [focusedItem, setFocusedItem] = useState(RAILS[0].items[0]);

  const currentHero = useMemo(()=> ({
    ...HERO,
    art: focusedItem ? focusedItem.art : HERO.art,
  }), [focusedItem]);

  // Hex intensity — re-inject clip-path
  useEffect(()=>{
    const id='hex-style';
    let el=document.getElementById(id);
    if (!el){ el=document.createElement('style'); el.id=id; document.head.appendChild(el); }
    let cut = 22;
    if (tweaks.hexIntensity === 'Subtle') cut = 14;
    if (tweaks.hexIntensity === 'Bold')   cut = 32;
    el.textContent = `
      .hex-2cut { clip-path: polygon(0 0, calc(100% - ${cut}px) 0, 100% ${cut}px, 100% 100%, ${cut}px 100%, 0 calc(100% - ${cut}px)) !important; }
    `;
  }, [tweaks.hexIntensity]);

  const bgGradient = tweaks.bgTone === 'Blue-black'
    ? `radial-gradient(120% 80% at 50% 0%, #0B1826 0%, #050A0E 70%, #02060A 100%)`
    : tweaks.bgTone === 'Pure black'
    ? `radial-gradient(100% 80% at 50% 0%, rgba(0,226,138,0.08) 0%, #000 70%)`
    : `radial-gradient(120% 80% at 50% 0%, #0F1C17 0%, #0A1410 60%, #050A08 100%)`;

  return (
    <div style={{ position:'absolute', inset:0, background:bgGradient, overflow:'hidden' }}>
      {/* subtle hex grid backdrop */}
      <div style={{
        position:'absolute', inset:0, opacity:0.04,
        backgroundImage:`
          repeating-linear-gradient(60deg, ${accent} 0 1px, transparent 1px 60px),
          repeating-linear-gradient(-60deg, ${accent} 0 1px, transparent 1px 60px)
        `,
        pointerEvents:'none',
      }} />

      <Sidebar active={activeNav} accent={accent} onNav={setActiveNav} />

      {activeNav === 'live' ? (
        <LiveTV accent={accent} />
      ) : (
        <>
          <Hero hero={currentHero} accent={accent} />
          <CategoryChips active={activeCat} accent={accent} />

          <div style={{
            position:'absolute', left:260, right:0, top:500,
            bottom: tweaks.showRemoteBar ? 56 : 0,
            overflow:'hidden',
          }}>
            {RAILS.map(rail => (
              <Rail key={rail.id} rail={rail}
                focusedId={focusedItem ? focusedItem.id : null}
                onFocus={setFocusedItem}
                accent={accent}
              />
            ))}
            <div style={{
              position:'absolute', left:0, right:0, bottom:0, height:60,
              background:'linear-gradient(180deg, transparent 0%, rgba(5,10,8,0.95) 100%)',
              pointerEvents:'none',
            }} />
          </div>
        </>
      )}

      <TopHUD accent={accent} />
      {tweaks.showRemoteBar && <RemoteBar accent={accent} />}

      <div style={{
        position:'absolute', inset:0, pointerEvents:'none',
        background:'radial-gradient(ellipse at center, transparent 50%, rgba(0,0,0,0.6) 100%)',
      }} />

      <TweaksPanel>
        <TweakSection label="Accent" />
        <TweakRadio label="Color" value={tweaks.accentName}
          options={['Emerald','Lime','Teal','Mint','Cyan']}
          onChange={(v)=>setTweak('accentName', v)} />

        <TweakSection label="Shape" />
        <TweakRadio label="Hex intensity" value={tweaks.hexIntensity}
          options={['Subtle','Balanced','Bold']}
          onChange={(v)=>setTweak('hexIntensity', v)} />

        <TweakSection label="Background" />
        <TweakRadio label="Tone" value={tweaks.bgTone}
          options={['Green-tint','Blue-black','Pure black']}
          onChange={(v)=>setTweak('bgTone', v)} />

        <TweakSection label="Remote" />
        <TweakToggle label="Key hint bar" value={tweaks.showRemoteBar}
          onChange={(v)=>setTweak('showRemoteBar', v)} />
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('stage')).render(<App />);
