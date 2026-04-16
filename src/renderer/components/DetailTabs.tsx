import { useEffect, useRef, useState } from 'react';
import { motion } from 'motion/react';

type TabId = 'episodes' | 'info' | 'related';

const TAB_LABELS: Record<TabId, string> = {
  episodes: 'Episodes',
  info: 'Info',
  related: 'Related',
};

interface DetailTabsProps {
  tabs: TabId[];
  activeTab: TabId;
  onTabChange: (tab: TabId) => void;
  contentType: string;
}

export function DetailTabs({ tabs, activeTab, onTabChange }: DetailTabsProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isSticky, setIsSticky] = useState(false);

  // Detect sticky state via IntersectionObserver
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const sentinel = container.previousElementSibling;
    if (!sentinel) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        setIsSticky(!entry.isIntersecting);
      },
      { threshold: 0 },
    );

    observer.observe(sentinel);
    return () => observer.disconnect();
  }, []);

  return (
    <>
      {/* Sentinel element for sticky detection */}
      <div className="h-0" aria-hidden />
      <div
        ref={containerRef}
        className={`sticky top-0 z-20 mb-4 border-b border-surface-800/50 px-6 transition-colors duration-200 ${
          isSticky ? 'bg-surface-950/80 backdrop-blur-md' : 'bg-transparent'
        }`}
      >
        <nav className="flex gap-1" role="tablist">
          {tabs.map((tab) => (
            <button
              key={tab}
              role="tab"
              aria-selected={activeTab === tab}
              onClick={() => onTabChange(tab)}
              className={`relative px-4 py-3 text-sm font-medium transition-colors ${
                activeTab === tab
                  ? 'text-accent'
                  : 'text-surface-400 hover:text-surface-200'
              }`}
            >
              <span className="flex items-center gap-1.5">
                <span className="text-[10px] opacity-60">&#x2B21;</span>
                {TAB_LABELS[tab]}
              </span>
              {activeTab === tab && (
                <motion.div
                  className="absolute bottom-0 left-2 right-2 h-0.5 rounded-full bg-accent"
                  layoutId="detail-tab-underline"
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                />
              )}
            </button>
          ))}
        </nav>
      </div>
    </>
  );
}
