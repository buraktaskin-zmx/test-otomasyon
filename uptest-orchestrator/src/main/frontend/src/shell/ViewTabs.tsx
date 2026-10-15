export type View = "workspace" | "history";

const TABS: ReadonlyArray<{ id: View; label: string }> = [
  { id: "workspace", label: "Çalışma Alanı" },
  { id: "history", label: "Geçmiş" },
];

/*
  aria-selected is new -- app.js shipped role="tablist"/"tab" without it, so
  assistive technology was told these were tabs but never which one was active.
  It costs one attribute, so it is fixed in passing; full keyboard tab
  semantics (roving tabindex, arrow keys) are a separate job and are not done.
*/
export function ViewTabs({ view, onChange }: { view: View; onChange: (v: View) => void }) {
  return (
    <nav className="tabs" role="tablist">
      {TABS.map((tab) => (
        <button
          key={tab.id}
          className={"tab" + (view === tab.id ? " is-active" : "")}
          data-view={tab.id}
          role="tab"
          aria-selected={view === tab.id}
          type="button"
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
        </button>
      ))}
    </nav>
  );
}
