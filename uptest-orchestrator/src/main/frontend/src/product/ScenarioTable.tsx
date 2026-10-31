import { useEffect, useState } from "react";
import type { MouseEvent } from "react";
import { readinessLabel } from "./readiness";
import { MOVE_MENU_MAX_PX, ScenarioMoveMenu } from "./ScenarioMoveMenu";
import { openHeadings, rememberOpenHeadings } from "../lib/headings";
import type { RegisteredScenario, ScenarioHeading, ScenarioNeed } from "../api/types";

/** A screen a scenario can be moved to. */
export interface MoveTarget {
  product: string;
  flow: string;
  label: string;
}

/*
  Kayıtlı senaryolar: hangi kurala bağlı, koşmak için neye ihtiyaç var.

  Four columns, and two of them are joins the files themselves could not show.
  A .feature on disk states its steps; it does not state that the rule it
  claims to cover was deleted last week, or that the data it needs is still
  nobody's. Those two are why this screen exists — without them a registry is
  a directory listing in a nicer font.

  A row opens the file. The columns are CLAIMS the tags make; the Gherkin
  underneath is whether the steps keep them.

  "Başlık" groups a screen's scenarios. Başlıksız rows come first, as plain
  rows; then each heading, in the order it was made, as one full-width row
  that opens and closes what is under it. A heading starts closed, and which
  ones are open is remembered for the session.
*/
export function ScenarioTable({
  scenarios,
  headings,
  busy,
  canAddHeading,
  onOpen,
  targetsFor,
  onMove,
  onAddHeading,
  onSetHeading,
}: {
  scenarios: RegisteredScenario[];
  /** The headings of the screens the rows come from. */
  headings: ScenarioHeading[];
  busy: boolean;
  /** One screen is selected: a heading needs a screen to belong to. */
  canAddHeading: boolean;
  onOpen: (scenario: RegisteredScenario) => void;
  /** Every screen this row could go to -- its own screen excluded. */
  targetsFor: (scenario: RegisteredScenario) => MoveTarget[];
  onMove: (scenario: RegisteredScenario, target: MoveTarget) => void;
  /** Resolves to true once the heading exists (new, or one of that name already there). */
  onAddHeading: (name: string) => Promise<boolean>;
  onSetHeading: (scenario: RegisteredScenario, heading: string | null) => void;
}) {
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [menuUp, setMenuUp] = useState(false);
  const [open, setOpen] = useState<ReadonlySet<string>>(openHeadings);

  // A click anywhere else, or Esc, closes the open menu.
  useEffect(() => {
    if (!menuFor) return;
    const close = () => setMenuFor(null);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    window.addEventListener("click", close);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("click", close);
      window.removeEventListener("keydown", onKey);
    };
  }, [menuFor]);

  const toggleHeading = (id: string) =>
    setOpen((was) => {
      const next = new Set(was);
      if (!next.delete(id)) next.add(id);
      rememberOpenHeadings(next);
      return next;
    });

  /* The menu opens upwards when the scroll box has no room for it below --
     the box clips it, so on the last rows it would otherwise be cut off. */
  const toggleMenu = (scenario: RegisteredScenario, event: MouseEvent<HTMLButtonElement>) => {
    if (menuFor === scenario.path) {
      setMenuFor(null);
      return;
    }
    const button = event.currentTarget.getBoundingClientRect();
    const box = event.currentTarget.closest(".registry-scroll")?.getBoundingClientRect();
    const bottom = Math.min(box ? box.bottom : window.innerHeight, window.innerHeight);
    const top = Math.max(box ? box.top : 0, 0);
    const below = bottom - button.bottom;
    const above = button.top - top;
    setMenuUp(below < MOVE_MENU_MAX_PX && above > below);
    setMenuFor(scenario.path);
  };

  const known = new Set(headings.map((heading) => heading.id));
  const headingOf = (scenario: RegisteredScenario) =>
    scenario.heading && known.has(scenario.heading) ? scenario.heading : null;
  const ungrouped = scenarios.filter((scenario) => headingOf(scenario) === null);
  const groups = headings.map((heading) => ({
    heading,
    rows: scenarios.filter((scenario) => headingOf(scenario) === heading.id),
  }));

  const toolbar = (
    <HeadingToolbar
      canAdd={canAddHeading}
      headingCount={headings.length}
      ungroupedCount={ungrouped.length}
      onAdd={onAddHeading}
    />
  );

  if (scenarios.length === 0 && headings.length === 0) {
    return (
      <>
        {busy ? null : toolbar}
        <p className="registry-empty">
          {busy
            ? "Okunuyor…"
            : "Bu ekranda kayıtlı senaryo yok. Dosyalar "
              + "products/<app>/<ürün>/scenarios/<ekran>/ altına .feature olarak konur."}
        </p>
      </>
    );
  }

  const row = (scenario: RegisteredScenario, grouped: boolean) => (
    <ScenarioRow
      key={scenario.path}
      scenario={scenario}
      grouped={grouped}
      menuOpen={menuFor === scenario.path}
      menuUp={menuUp}
      headings={headings.filter((heading) => heading.flow === scenario.flow)}
      current={headingOf(scenario)}
      onOpen={onOpen}
      onToggleMenu={toggleMenu}
      onCloseMenu={() => setMenuFor(null)}
      targetsFor={targetsFor}
      onMove={onMove}
      onSetHeading={onSetHeading}
    />
  );

  return (
    <>
      {toolbar}
      <div className="registry-scroll">
        <table className="registry">
          <thead>
            <tr>
              <th>Senaryo</th>
              <th>Kurallar</th>
              <th>Ön koşul</th>
              <th>Açıklama</th>
              <th aria-label="Taşı" />
            </tr>
          </thead>
          <tbody>
            {ungrouped.map((scenario) => row(scenario, false))}
            {groups.map(({ heading, rows }) => {
              const isOpen = open.has(heading.id);
              return [
                <tr key={"h:" + heading.id} className="sc-group">
                  <td colSpan={5}>
                    <button
                      type="button"
                      className="sc-group-toggle unstyled-button"
                      aria-expanded={isOpen}
                      onClick={() => toggleHeading(heading.id)}
                    >
                      <span className="sc-group-caret" aria-hidden="true">{isOpen ? "▼" : "▶"}</span>
                      <span className="sc-group-name" lang="tr">{heading.name}</span>
                      <span className="badge badge-idle sc-group-count"
                            aria-label={rows.length + " senaryo"}>
                        {rows.length}
                      </span>
                    </button>
                  </td>
                </tr>,
                ...(isOpen ? rows.map((scenario) => row(scenario, true)) : []),
              ];
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

/**
 * "+ Başlık" and the summary. The button turns into a text box where it
 * stands: Enter adds, Esc or Vazgeç cancels, a blank name is not accepted.
 */
function HeadingToolbar({
  canAdd,
  headingCount,
  ungroupedCount,
  onAdd,
}: {
  canAdd: boolean;
  headingCount: number;
  ungroupedCount: number;
  onAdd: (name: string) => Promise<boolean>;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);

  const cancel = () => {
    setAdding(false);
    setName("");
  };

  const submit = async () => {
    if (!name.trim() || saving) return;
    setSaving(true);
    const ok = await onAdd(name.trim());
    setSaving(false);
    if (ok) cancel();
  };

  return (
    <div className="sc-toolbar">
      {!canAdd ? null : adding ? (
        <div className="sc-toolbar-add">
          <input
            className="search sc-heading-input"
            autoFocus
            value={name}
            disabled={saving}
            placeholder="Başlık adı… (Enter)"
            aria-label="Başlık adı"
            onChange={(event) => setName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void submit();
              } else if (event.key === "Escape") {
                event.preventDefault();
                cancel();
              }
            }}
          />
          <button type="button" className="btn btn-ghost" disabled={saving} onClick={cancel}>
            Vazgeç
          </button>
        </div>
      ) : (
        <button type="button" className="btn btn-ghost" onClick={() => setAdding(true)}>
          + Başlık
        </button>
      )}
      <span className="sc-toolbar-summary">
        {headingCount} başlık · {ungroupedCount} başlıksız senaryo
      </span>
    </div>
  );
}

function ScenarioRow({
  scenario,
  grouped,
  menuOpen,
  menuUp,
  headings,
  current,
  onOpen,
  onToggleMenu,
  onCloseMenu,
  targetsFor,
  onMove,
  onSetHeading,
}: {
  scenario: RegisteredScenario;
  /** Under a heading: the first cell is indented. */
  grouped: boolean;
  menuOpen: boolean;
  menuUp: boolean;
  /** This row's own screen's headings. */
  headings: ScenarioHeading[];
  current: string | null;
  onOpen: (scenario: RegisteredScenario) => void;
  onToggleMenu: (scenario: RegisteredScenario, event: MouseEvent<HTMLButtonElement>) => void;
  onCloseMenu: () => void;
  targetsFor: (scenario: RegisteredScenario) => MoveTarget[];
  onMove: (scenario: RegisteredScenario, target: MoveTarget) => void;
  onSetHeading: (scenario: RegisteredScenario, heading: string | null) => void;
}) {
  return (
    <tr
      className={"is-clickable" + (scenario.problem ? " is-broken" : "")
        + (grouped ? " is-grouped" : "")}
      onClick={() => onOpen(scenario)}
    >
      <td>
        <button type="button" className="sc-open unstyled-button">
          {scenario.title}
        </button>
        <div className="sc-path">
          {scenario.path}
          {/* Only once there ARE Gherkin steps: "0 adım" on every
              natural-language scenario read as an error. */}
          {scenario.problem || scenario.stepCount === 0 ? null : (
            <span className="sc-steps"> · {scenario.stepCount} adım</span>
          )}
          {scenario.stale ? (
            <span className="sc-stale"
                  title="Bu senaryonun dayandığı kural ya da ön koşul, senaryo gözden geçirildikten sonra değişti">
              {" "}· bayat
            </span>
          ) : null}
        </div>
        {scenario.tags.map((tag) => (
          <span key={tag} className="sc-tag">@{tag}</span>
        ))}
        {scenario.problem ? (
          <div className="sc-problem">{scenario.problem}</div>
        ) : null}
      </td>

      <td>
        {scenario.rules.length === 0 ? (
          // Not left blank: a scenario bound to no rule is one nobody
          // can state the purpose of, and that is a finding.
          <span className="sc-unbound" title="Hiçbir kurala bağlı değil">
            bağsız
          </span>
        ) : (
          scenario.rules.map((rule) => (
            <span
              key={rule.id}
              className={"rule-chip" + (rule.known ? "" : " is-unknown")
                + (rule.stale ? " is-stale" : "")}
              title={!rule.known
                ? "rules.yaml'da böyle bir kural yok"
                : rule.stale
                  ? rule.title + " — bu kural senaryo gözden geçirildikten sonra değişti"
                  : rule.title + (rule.confirmed ? "" : " (VARSAYIM)")}
            >
              {rule.id}
            </span>
          ))
        )}
      </td>

      <td><Precondition scenario={scenario} /></td>

      <td className="sc-desc">{scenario.description}</td>

      {/* Clicks here must not open the row underneath. */}
      <td className="sc-move" onClick={(event) => event.stopPropagation()}>
        <button
          type="button"
          className="btn btn-ghost sc-move-btn"
          title="Taşı"
          aria-label="Taşı"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={(event) => onToggleMenu(scenario, event)}
        >
          ⇄
        </button>
        {menuOpen ? (
          <ScenarioMoveMenu
            scenario={scenario}
            targets={targetsFor(scenario)}
            headings={headings}
            current={current}
            up={menuUp}
            onMove={onMove}
            onHeading={onSetHeading}
            onClose={onCloseMenu}
          />
        ) : null}
      </td>
    </tr>
  );
}

/**
 * The scenario's own "Ön koşul" items, one per line, exactly as written --
 * edited in the detail view, re-read with the table after every save.
 *
 * Only a scenario that wrote none falls back to the @needs readiness: one
 * word for the row, the WORST of its needs, then the needs themselves.
 */
function Precondition({ scenario }: { scenario: RegisteredScenario }) {
  const written = scenario.preconditions ?? [];
  if (written.length > 0) {
    return (
      <ul className="sc-preconditions">
        {written.map((item, index) => <li key={index}>{item}</li>)}
      </ul>
    );
  }
  const state = readinessLabel(scenario.readiness, scenario.needsNone);
  return (
    <>
      <div className={"sc-ready is-" + state.kind} title={state.hint}>
        <span className="sc-dot" aria-hidden="true" />
        {state.word}
      </div>
      {scenario.needs.map((need) => (
        <div
          key={need.id}
          className={"sc-alias" + (need.readiness === "READY" ? "" : " is-weak")
            + (need.stale ? " is-stale" : "")}
          title={need.stale
            ? needTitle(need) + " · tanımı senaryo gözden geçirildikten sonra değişti"
            : needTitle(need)}
        >
          {need.label || need.id}
        </div>
      ))}
    </>
  );
}

function needTitle(need: ScenarioNeed) {
  if (!need.known) return need.id + " — test-data.yaml'da tanımlı değil";
  const parts = [need.id, need.description || "", "çözüm: " + (need.how || "?")];
  // The blocker is NAMED, not merely implied. "S-18 engelliyor" sends the
  // reader to the question; "kullanılamaz" sends them nowhere.
  if (need.blockedBy) parts.push(need.blockedBy + " engelliyor");
  return parts.filter(Boolean).join(" · ");
}
