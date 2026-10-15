/*
  index.html:62-70 (the .side-tab row). "multi" is added in its own step;
  listed here as a reserved id so SidePane's switch does not need
  restructuring when it lands.
*/
export type SideTab = "vocab" | "timeline" | "suggest" | "multi" | "agent";

export function SideTabs({
  active,
  onChange,
  liveRun,
  agentVisible,
}: {
  active: SideTab;
  onChange: (tab: SideTab) => void;
  liveRun: boolean;
  agentVisible: boolean;
}) {
  const tabs: ReadonlyArray<{ id: SideTab; label: string }> = [
    { id: "vocab", label: "Adım Sözlüğü" },
    { id: "timeline", label: "Zaman Çizelgesi" },
    { id: "suggest", label: "AI Üret" },
    { id: "multi", label: "Çoklu Üret" },
    ...(agentVisible ? ([{ id: "agent", label: "Ajan" }] as const) : []),
  ];

  return (
    <div className="pane-tabs" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          className={"side-tab" + (active === tab.id ? " is-active" : "")}
          role="tab"
          aria-selected={active === tab.id}
          type="button"
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
          {tab.id === "timeline" ? <span className={"tab-dot" + (liveRun ? " is-live" : "")} /> : null}
        </button>
      ))}
    </div>
  );
}
