// YancoTV+ — icon set v2. 24x24, 1.8px stroke, currentColor.
// Matches existing app sidebar glyphs.
const ICON_COMMON = {
  width: 24, height: 24, viewBox: '0 0 24 24',
  fill: 'none', stroke: 'currentColor', strokeWidth: 1.8,
  strokeLinecap: 'round', strokeLinejoin: 'round',
};

const IconHome = ({ active }) => (
  <svg {...ICON_COMMON}>
    <path d="M3.5 10.5 L12 3.5 L20.5 10.5 L20.5 20 L14.5 20 L14.5 14 L9.5 14 L9.5 20 L3.5 20 Z" />
    <circle cx="12" cy="6.2" r="0.9" fill={active ? '#00E28A' : 'currentColor'} stroke="none" />
    {active && <circle cx="12" cy="6.2" r="2.2" fill="#00E28A" stroke="none" opacity="0.25" />}
  </svg>
);
const IconLiveTV = ({ active }) => (
  <svg {...ICON_COMMON}>
    <rect x="2.75" y="5" width="18.5" height="12" rx="2" />
    <path d="M8 20.5 L16 20.5" /><path d="M12 17.2 L12 20.5" />
    <circle cx="18.2" cy="7.8" r="1.35" fill="#FF3B3B" stroke="none" />
  </svg>
);
const IconGuide = () => (
  <svg {...ICON_COMMON}>
    <rect x="3" y="5" width="18" height="15" rx="1.6" />
    <path d="M3 10 L21 10" /><path d="M8 5 L8 20" /><path d="M14 10 L14 20" />
  </svg>
);
const IconMovies = () => (
  <svg {...ICON_COMMON} strokeWidth={2}>
    <path d="M3 8 L3 6.2 A1.2 1.2 0 0 1 4.2 5 L19.8 5 A1.2 1.2 0 0 1 21 6.2 L21 8 Z" />
    <path d="M7.5 5 L5.2 8" /><path d="M12 5 L9.7 8" /><path d="M16.5 5 L14.2 8" /><path d="M21 5 L18.7 8" />
    <rect x="3" y="8" width="18" height="11" rx="1.2" />
  </svg>
);
const IconSeries = () => (
  <svg {...ICON_COMMON}>
    <rect x="6" y="4" width="12.5" height="8" rx="1.4" opacity="0.55" />
    <rect x="4.5" y="7" width="14" height="9" rx="1.5" opacity="0.8" />
    <rect x="3" y="10" width="15.5" height="10" rx="1.6" />
  </svg>
);
const IconFavorites = ({ active }) => (
  <svg {...ICON_COMMON}>
    <path d="M12 20.2 C8.5 17.8 3.5 14.8 3.5 10.2 A4.2 4.2 0 0 1 7.7 6 C9.6 6 11.1 7 12 8.5 C12.9 7 14.4 6 16.3 6 A4.2 4.2 0 0 1 20.5 10.2 C20.5 14.8 15.5 17.8 12 20.2 Z" />
    <path d="M17.2 17.2 L17.8 18.45 L19.2 18.65 L18.2 19.6 L18.45 21 L17.2 20.35 L15.95 21 L16.2 19.6 L15.2 18.65 L16.6 18.45 Z"
      fill={active ? '#00E28A' : 'currentColor'} stroke="none" />
  </svg>
);
const IconSearch = () => (
  <svg {...ICON_COMMON}>
    <circle cx="10.5" cy="10.5" r="6" />
    <path d="M15 15 L20 20" strokeWidth="2.6" />
  </svg>
);
const IconSettings = () => {
  const cx=12, cy=12, rI=6.8, rO=9.6, tH=0.45;
  const teeth=[];
  for (let i=0;i<6;i++){
    const a=(i/6)*Math.PI*2-Math.PI/2;
    const a1=a-tH, a2=a+tH;
    const p=(ang,r)=>[cx+Math.cos(ang)*r, cy+Math.sin(ang)*r];
    const [x1,y1]=p(a1,rI),[x2,y2]=p(a1,rO),[x3,y3]=p(a2,rO),[x4,y4]=p(a2,rI);
    teeth.push(`M ${x1.toFixed(2)} ${y1.toFixed(2)} L ${x2.toFixed(2)} ${y2.toFixed(2)} L ${x3.toFixed(2)} ${y3.toFixed(2)} L ${x4.toFixed(2)} ${y4.toFixed(2)}`);
  }
  return (
    <svg {...ICON_COMMON}>
      <circle cx="12" cy="12" r="6.8" />
      <circle cx="12" cy="12" r="2.3" />
      {teeth.map((d,i)=><path key={i} d={d} />)}
    </svg>
  );
};

// Matches the existing YancoTV+ app sidebar order
const NAV_ITEMS = [
  { id: 'home', label: 'Home', Icon: IconHome },
  { id: 'live', label: 'Live TV', Icon: IconLiveTV },
  { id: 'guide', label: 'Guide', Icon: IconGuide },
  { id: 'movies', label: 'Movies', Icon: IconMovies },
  { id: 'series', label: 'Series', Icon: IconSeries },
  { id: 'favorites', label: 'Favorites', Icon: IconFavorites },
  { id: 'search', label: 'Search', Icon: IconSearch },
  { id: 'settings', label: 'Settings', Icon: IconSettings },
];

Object.assign(window, {
  IconHome, IconLiveTV, IconGuide, IconMovies, IconSeries, IconFavorites, IconSearch, IconSettings,
  NAV_ITEMS,
});
