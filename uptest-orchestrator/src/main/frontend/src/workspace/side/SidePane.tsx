import type { ReactNode } from "react";

/*
  The side pane's chrome.

  Was a horizontal .pane-tabs strip above the panel. It became a vertical
  rail on the left of the workspace for the reason UI-REDESIGN-BRIEF §6.5
  recorded as a problem: five tabs competing for one narrow column, with the
  labels on these screens long enough ("Adım Sözlüğü", "Zaman Çizelgesi")
  that they crowd each other. A vertical list has room for every label at
  full length, and room to grow -- Chat is the sixth item.

  The panels themselves are untouched: the rail only decides which one has
  .is-active, exactly as the tab strip did.
*/

export type SideTab = "product" | "users" | "timeline" | "chat" | "suggest" | "multi" | "agent";

/*
  Inline SVG, 16px, stroke-based.

  Not an icon font and not an image: corporate SSL inspection blocks the
  browser's outbound requests, so every asset in this UI is either inlined
  or shipped from our own jar. These are small enough to inline, which also
  means they inherit `currentColor` and follow the active/hover states for
  free.
*/
const STROKE = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.6,
  strokeLinecap: "round",
  strokeLinejoin: "round",
} as const;

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg className="rail-icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" {...STROKE}>
      {children}
    </svg>
  );
}

const ICONS: Record<SideTab, ReactNode> = {
  // The rule book: a document with a checked clause.
  product: (
    <Icon>
      <path d="M4 2.5h6.2L13 5.3V13a.5.5 0 0 1-.5.5h-8.5A.5.5 0 0 1 3.5 13V3a.5.5 0 0 1 .5-.5z" />
      <path d="M6 9.3 7.2 10.6 10 7.6" />
    </Icon>
  ),
  // A person.
  users: (
    <Icon>
      <circle cx="8" cy="5.5" r="2.6" />
      <path d="M2.8 13.5c0-2.6 2.3-4.2 5.2-4.2s5.2 1.6 5.2 4.2" />
    </Icon>
  ),
  // A run: play, with progress beneath it.
  timeline: (
    <Icon>
      <path d="M6 3.4 11.2 7 6 10.6z" />
      <path d="M2.5 13.4h11" />
    </Icon>
  ),
  // A conversation.
  chat: (
    <Icon>
      <path d="M13.5 9.5a1.5 1.5 0 0 1-1.5 1.5H6l-3 2.5V4a1.5 1.5 0 0 1 1.5-1.5h7.5A1.5 1.5 0 0 1 13.5 4z" />
    </Icon>
  ),
  // Generation: a spark.
  suggest: (
    <Icon>
      <path d="M8 2.2 9.3 6.1 13.2 7.4 9.3 8.7 8 12.6 6.7 8.7 2.8 7.4 6.7 6.1z" />
    </Icon>
  ),
  // A batch: stacked documents.
  multi: (
    <Icon>
      <rect x="2.5" y="2.5" width="8" height="8" rx="1.2" />
      <path d="M5.5 13.5h7a1 1 0 0 0 1-1v-7" />
    </Icon>
  ),
  // The agent: a browser window it drives.
  agent: (
    <Icon>
      <rect x="2" y="3" width="12" height="10" rx="1.4" />
      <path d="M2 6h12M4.4 4.5h.01M6.4 4.5h.01" />
    </Icon>
  ),
};

const PANEL_ICON = (
  <Icon>
    <rect x="2" y="3" width="12" height="10" rx="1.4" />
    <path d="M10 3v10" />
  </Icon>
);

export function SideRail({
  active,
  onChange,
  liveRun,
  agentVisible,
  collapsed,
  onToggleCollapse,
}: {
  active: SideTab;
  onChange: (tab: SideTab) => void;
  liveRun: boolean;
  agentVisible: boolean;
  collapsed: boolean;
  onToggleCollapse: () => void;
}) {
  const tabs: ReadonlyArray<{ id: SideTab; label: string }> = [
    { id: "product", label: "Kayıtlı Senaryolar" },
    { id: "users", label: "Test Kullanıcıları" },
    { id: "timeline", label: "Kayıtlı adımlarla çalıştır" },
    { id: "chat", label: "Chat" },
    { id: "suggest", label: "AI Üret" },
    { id: "multi", label: "Çoklu Üret" },
    ...(agentVisible ? ([{ id: "agent", label: "Ekran Keşfi" }] as const) : []),
  ];

  return (
    <nav className="side-rail" role="tablist" aria-orientation="vertical">
      <button
        type="button"
        className="rail-item rail-toggle"
        onClick={onToggleCollapse}
        title={collapsed ? "Paneli aç" : "Paneli kapat, editöre yer aç"}
      >
        {PANEL_ICON}
        <span className="rail-label">{collapsed ? "PANELİ AÇ" : "PANELİ KAPAT"}</span>
      </button>

      {tabs.map((tab) => {
        // While the panel is closed nothing is on screen to be "selected",
        // so no item is marked active -- otherwise the rail claims to be
        // showing a panel that is not there.
        const selected = !collapsed && active === tab.id;
        return (
          <button
            key={tab.id}
            type="button"
            className={"rail-item" + (selected ? " is-active" : "")}
            role="tab"
            aria-selected={selected}
            // A click while closed means "show me this", not "select
            // something invisible": it reopens the panel as well.
            onClick={() => onChange(tab.id)}
          >
            {ICONS[tab.id]}
            <span className="rail-label">{tab.label}</span>
            {tab.id === "timeline" ? (
              <span className={"tab-dot" + (liveRun ? " is-live" : "")} />
            ) : null}
          </button>
        );
      })}
    </nav>
  );
}
