import { useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { matchesTr } from "../lib/headings";
import type { RegisteredScenario, ScenarioHeading } from "../api/types";
import type { MoveTarget } from "./ScenarioTable";

/** The menu's height cap -- also what the row measures against to open it upwards. */
export const MOVE_MENU_MAX_PX = 340;

const UNGROUPED = "Başlıksız";

/*
  ⇄ on a scenario row: two halves, "Ekran" (move it to another screen) and
  "Başlık" (group it under one of its own screen's headings), with one search
  box filtering both.

  Mounted inside the row's `td.sc-move`, whose click handler stops
  propagation, so nothing chosen here opens the row underneath.
*/
export function ScenarioMoveMenu({
  scenario,
  targets,
  headings,
  current,
  up,
  onMove,
  onHeading,
  onClose,
}: {
  scenario: RegisteredScenario;
  targets: MoveTarget[];
  /** The scenario's own screen's headings. */
  headings: ScenarioHeading[];
  /** The heading it is under now, null for başlıksız. */
  current: string | null;
  /** Open above the button: there is no room below. */
  up: boolean;
  onMove: (scenario: RegisteredScenario, target: MoveTarget) => void;
  onHeading: (scenario: RegisteredScenario, heading: string | null) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const menu = useRef<HTMLDivElement>(null);

  const screens = targets.filter((target) => matchesTr(target.label, query));
  const showUngrouped = matchesTr(UNGROUPED, query);
  const groups = headings.filter((heading) => matchesTr(heading.name, query));

  /* ↑/↓ walk the search box and every item; Esc closes. Tab works on its own. */
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const stops = Array.from(
      menu.current?.querySelectorAll<HTMLElement>("input, button") ?? []);
    if (stops.length === 0) return;
    event.preventDefault();
    const at = stops.indexOf(document.activeElement as HTMLElement);
    const step = event.key === "ArrowDown" ? 1 : -1;
    stops[(at + step + stops.length) % stops.length]?.focus();
  };

  const mark = (on: boolean) => (
    <span className="sc-move-check" aria-hidden="true">{on ? "✓" : ""}</span>
  );

  return (
    <div
      ref={menu}
      className={"sc-move-menu" + (up ? " is-up" : "")}
      role="menu"
      onKeyDown={onKeyDown}
    >
      <input
        className="search sc-move-search"
        autoFocus
        value={query}
        placeholder="Ekran ya da başlık ara…"
        aria-label="Ekran ya da başlık ara"
        onChange={(event) => setQuery(event.target.value)}
      />
      <div className="sc-move-list">
        <div className="sc-move-head">Ekran</div>
        {screens.length === 0 ? (
          <div className="sc-move-empty">
            {targets.length === 0 ? "Taşınabilecek başka ekran yok" : "Eşleşen ekran yok"}
          </div>
        ) : (
          screens.map((target) => (
            <button
              key={target.product + "/" + target.flow}
              type="button"
              role="menuitem"
              className="sc-move-item unstyled-button"
              onClick={() => {
                onClose();
                onMove(scenario, target);
              }}
            >
              {target.label}
            </button>
          ))
        )}

        <div className="sc-move-head">Başlık</div>
        {showUngrouped ? (
          <button
            type="button"
            role="menuitemradio"
            aria-checked={current === null}
            className="sc-move-item unstyled-button"
            onClick={() => {
              onClose();
              if (current !== null) onHeading(scenario, null);
            }}
          >
            {mark(current === null)}{UNGROUPED}
          </button>
        ) : null}
        {groups.map((heading) => (
          <button
            key={heading.id}
            type="button"
            role="menuitemradio"
            aria-checked={current === heading.id}
            className="sc-move-item unstyled-button"
            onClick={() => {
              onClose();
              if (current !== heading.id) onHeading(scenario, heading.id);
            }}
          >
            {mark(current === heading.id)}{heading.name}
          </button>
        ))}
        {!showUngrouped && groups.length === 0 ? (
          <div className="sc-move-empty">Eşleşen başlık yok</div>
        ) : null}
      </div>
    </div>
  );
}
