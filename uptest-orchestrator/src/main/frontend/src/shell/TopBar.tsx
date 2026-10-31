import { ExecutorPill } from "./ExecutorPill";
import { ModelHealthDot } from "./ModelHealthDot";
import { ViewTabs } from "./ViewTabs";
import type { View } from "./ViewTabs";

export function TopBar({
  view,
  onChangeView,
  onRefresh,
  onToggleTheme,
}: {
  view: View;
  onChangeView: (view: View) => void;
  onRefresh: () => void;
  onToggleTheme: () => void;
}) {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-name">Senaryum</span>
      </div>
      <ViewTabs view={view} onChange={onChangeView} />
      <div className="topbar-right">
        <ModelHealthDot />
        <ExecutorPill onRefresh={onRefresh} />
        <button
          id="themeToggle"
          className="icon-btn"
          type="button"
          title="Tema değiştir"
          aria-label="Tema değiştir"
          onClick={onToggleTheme}
        >
          {"\u25D0"}
        </button>
      </div>
    </header>
  );
}
