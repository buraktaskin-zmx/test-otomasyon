import { useEffect, useState } from "react";
import type { DragEvent, MouseEvent, ReactNode } from "react";
import { readinessLabel } from "./readiness";
import { MOVE_MENU_MAX_PX, ScenarioMoveMenu } from "./ScenarioMoveMenu";
import { openHeadings, rememberOpenHeadings } from "../lib/headings";
import { ConfirmDialog } from "../ui/ConfirmDialog";
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

  Rows can be dragged (one screen selected): into another heading, to
  "Başlıksız", or to another place in their own group. While dragging, the
  row leaves its place and an empty slot opens where it would land, so the
  rows around it move aside. A dropped row is shown in its new place at once
  and the server is told the whole new order of that group; the reload that
  follows either confirms it or puts it back. The ⇄ menu stays as the
  keyboard way to change a heading.
*/

/** A group of rows: a heading's id, or "" for başlıksız. */
type GroupKey = string;
const UNGROUPED: GroupKey = "";

interface Drop {
  group: GroupKey;
  /** Position among the group's rows with the dragged row left out. */
  index: number;
}
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
  canArrange,
  onArrange,
  onDelete,
  selected,
  selectLocked,
  onToggleSelect,
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
  /** One screen is selected: an order belongs to one screen's groups. */
  canArrange: boolean;
  /** The dropped-into group's rows, in order, all under `heading` (null = başlıksız). */
  onArrange: (heading: string | null, paths: string[]) => void;
  /** Deletes for good; resolves once the server has answered, true when it was deleted. */
  onDelete: (scenario: RegisteredScenario) => Promise<boolean>;
  /** Rows ticked for a multi-run, by path. */
  selected: ReadonlySet<string>;
  /** A queue is running: the ticks cannot change under it. */
  selectLocked: boolean;
  onToggleSelect: (scenario: RegisteredScenario) => void;
}) {
  const [deleting, setDeleting] = useState<RegisteredScenario | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [menuUp, setMenuUp] = useState(false);
  const [open, setOpen] = useState<ReadonlySet<string>>(openHeadings);
  const [dragging, setDragging] = useState<{ path: string; height: number } | null>(null);
  const [drop, setDrop] = useState<Drop | null>(null);
  /* The drop as the table shows it until the reload arrives -- otherwise the
     row would jump back to its old place for the length of a round trip. */
  const [pending, setPending] = useState<{ path: string; group: GroupKey; paths: string[] } | null>(null);

  // Fresh rows from the server replace the optimistic order, right or wrong.
  useEffect(() => setPending(null), [scenarios]);

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

  const openHeading = (id: string) =>
    setOpen((was) => {
      if (was.has(id)) return was;
      const next = new Set(was);
      next.add(id);
      rememberOpenHeadings(next);
      return next;
    });

  const known = new Set(headings.map((heading) => heading.id));
  const headingOf = (scenario: RegisteredScenario) =>
    scenario.heading && known.has(scenario.heading) ? scenario.heading : null;
  const rowsOf = (group: GroupKey) => {
    if (pending) {
      if (group === pending.group) {
        const byPath = new Map(scenarios.map((scenario) => [scenario.path, scenario]));
        return pending.paths.flatMap((path) => byPath.get(path) ?? []);
      }
      return scenarios.filter((scenario) =>
        scenario.path !== pending.path && (headingOf(scenario) ?? UNGROUPED) === group);
    }
    return scenarios.filter((scenario) => (headingOf(scenario) ?? UNGROUPED) === group);
  };
  const ungrouped = rowsOf(UNGROUPED);
  const groups = headings.map((heading) => ({ heading, rows: rowsOf(heading.id) }));

  /* ------------------------------ dragging ------------------------------ */

  const visibleOf = (group: GroupKey) =>
    rowsOf(group).filter((scenario) => scenario.path !== dragging?.path);

  const aim = (group: GroupKey, index: number) =>
    setDrop((was) => (was && was.group === group && was.index === index ? was : { group, index }));

  const startDrag = (scenario: RegisteredScenario, event: DragEvent<HTMLTableRowElement>) => {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", scenario.path);
    const height = event.currentTarget.getBoundingClientRect().height;
    // After the browser has taken its picture of the row: hiding it in the
    // same tick would leave the drag image blank.
    window.setTimeout(() => setDragging({ path: scenario.path, height }), 0);
  };

  const endDrag = () => {
    setDragging(null);
    setDrop(null);
  };

  /** Over a row: before it in its upper half, after it in its lower half. */
  const overRow = (group: GroupKey, index: number, event: DragEvent<HTMLTableRowElement>) => {
    if (!dragging) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    const box = event.currentTarget.getBoundingClientRect();
    aim(group, event.clientY < box.top + box.height / 2 ? index : index + 1);
  };

  /** Over a heading row: open it, and land at the end of it. */
  const overHeading = (group: GroupKey, event: DragEvent<HTMLTableRowElement>) => {
    if (!dragging) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    if (group !== UNGROUPED) openHeading(group);
    aim(group, group === UNGROUPED ? 0 : visibleOf(group).length);
  };

  const keepAim = (event: DragEvent<HTMLElement>) => {
    if (!dragging) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  };

  const commitDrop = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    const moved = dragging;
    const at = drop;
    endDrag();
    if (!moved || !at) return;
    const paths = visibleOf(at.group).map((scenario) => scenario.path);
    paths.splice(Math.min(at.index, paths.length), 0, moved.path);
    const before = rowsOf(at.group).map((scenario) => scenario.path);
    if (before.length === paths.length && before.every((path, i) => path === paths[i])) return;
    setPending({ path: moved.path, group: at.group, paths });
    onArrange(at.group === UNGROUPED ? null : at.group, paths);
  };

  const slot = (key: string) => (
    <tr key={key} className="sc-drop-slot" onDragOver={keepAim} onDrop={commitDrop}>
      <td colSpan={5}>
        <div className="sc-drop-slot-box" style={{ height: dragging?.height ?? 48 }} />
      </td>
    </tr>
  );

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

  /* One group's rows, with the dragged row hidden in place (removing it from
     the page would cancel the drag) and the slot where it would land. */
  const groupRows = (group: GroupKey, rows: RegisteredScenario[], grouped: boolean) => {
    const out: ReactNode[] = [];
    const aimed = dragging && drop && drop.group === group ? drop.index : -1;
    let visible = 0;
    for (const scenario of rows) {
      if (dragging && scenario.path === dragging.path) {
        out.push(row(scenario, grouped, { hidden: true }));
        continue;
      }
      if (aimed === visible) out.push(slot("slot:" + group + ":" + visible));
      const index = visible;
      out.push(row(scenario, grouped, {
        onDragOver: (event) => overRow(group, index, event),
      }));
      visible++;
    }
    if (aimed >= visible) out.push(slot("slot:" + group + ":" + visible));
    return out;
  };

  const row = (scenario: RegisteredScenario, grouped: boolean, drag: RowDrag) => (
    <ScenarioRow
      key={scenario.path}
      scenario={scenario}
      grouped={grouped}
      draggable={canArrange}
      hidden={drag.hidden ?? false}
      onDragStart={startDrag}
      onDragEnd={endDrag}
      onDragOver={drag.onDragOver}
      onDrop={commitDrop}
      onAskDelete={setDeleting}
      checked={selected.has(scenario.path)}
      selectLocked={selectLocked}
      onToggleSelect={onToggleSelect}
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
        <table className={"registry" + (selected.size > 0 ? " is-selecting" : "")}>
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
            {/* Only while dragging: a place to drop a row out of every heading,
                there even when no row is başlıksız yet. */}
            {dragging ? (
              <tr
                className={"sc-group sc-ungrouped-drop"
                  + (drop?.group === UNGROUPED ? " is-drop-target" : "")}
                onDragOver={(event) => overHeading(UNGROUPED, event)}
                onDrop={commitDrop}
              >
                <td colSpan={5}>
                  <div className="sc-group-toggle">
                    <span className="sc-group-name" lang="tr">Başlıksız</span>
                  </div>
                </td>
              </tr>
            ) : null}
            {groupRows(UNGROUPED, ungrouped, false)}
            {groups.map(({ heading, rows }) => {
              const isOpen = open.has(heading.id);
              return [
                <tr
                  key={"h:" + heading.id}
                  className={"sc-group"
                    + (dragging && drop?.group === heading.id ? " is-drop-target" : "")}
                  onDragOver={(event) => overHeading(heading.id, event)}
                  onDrop={commitDrop}
                >
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
                ...(isOpen ? groupRows(heading.id, rows, true) : []),
              ];
            })}
          </tbody>
        </table>
      </div>
      {deleting ? (
        <ConfirmDialog
          title="Senaryoyu silmek istediğinize emin misiniz?"
          detail={"“" + deleting.title + "” kalıcı olarak silinecek. Bu işlem geri alınamaz."}
          confirmLabel="Sil"
          busy={deleteBusy}
          onCancel={() => setDeleting(null)}
          onConfirm={async () => {
            setDeleteBusy(true);
            await onDelete(deleting);
            setDeleteBusy(false);
            setDeleting(null);
          }}
        />
      ) : null}
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

/** Has Gherkin steps that parse: what a deterministic run needs. */
export function runnable(scenario: RegisteredScenario): boolean {
  return scenario.stepCount > 0 && !scenario.problem;
}

/** How one row takes part in a drag. */
interface RowDrag {
  /** The row being dragged: kept on the page, out of sight. */
  hidden?: boolean;
  onDragOver?: (event: DragEvent<HTMLTableRowElement>) => void;
}

function ScenarioRow({
  scenario,
  grouped,
  draggable,
  hidden,
  onDragStart,
  onDragEnd,
  onDragOver,
  onDrop,
  onAskDelete,
  checked,
  selectLocked,
  onToggleSelect,
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
  draggable: boolean;
  hidden: boolean;
  onDragStart: (scenario: RegisteredScenario, event: DragEvent<HTMLTableRowElement>) => void;
  onDragEnd: () => void;
  onDragOver?: (event: DragEvent<HTMLTableRowElement>) => void;
  onDrop: (event: DragEvent<HTMLTableRowElement>) => void;
  onAskDelete: (scenario: RegisteredScenario) => void;
  checked: boolean;
  selectLocked: boolean;
  onToggleSelect: (scenario: RegisteredScenario) => void;
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
        + (grouped ? " is-grouped" : "") + (hidden ? " sc-drag-source" : "")}
      onClick={() => onOpen(scenario)}
      draggable={draggable}
      title={draggable ? "Taşımak için basılı tutup sürükleyin" : undefined}
      onDragStart={draggable ? (event) => onDragStart(scenario, event) : undefined}
      onDragEnd={draggable ? onDragEnd : undefined}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
      <td>
        <div className="sc-title-line">
          {/* The multi-run tick. Only a scenario with Gherkin steps can run
              deterministically; a natural-language-only one is shown but
              cannot be ticked, and says why. */}
          <input
            type="checkbox"
            className="sc-select"
            checked={checked}
            disabled={selectLocked || !runnable(scenario)}
            title={runnable(scenario) ? "Çoklu çalıştırma için seç" : "Gherkin yok — çalıştırılamaz"}
            aria-label={"Çalıştırmak için seç: " + scenario.title}
            draggable={false}
            onClick={(event) => event.stopPropagation()}
            onChange={() => onToggleSelect(scenario)}
          />
          {/* Shown on hover (and keyboard focus); its click must not open the row. */}
          <button
            type="button"
            className="sc-delete unstyled-button"
            title="Senaryoyu sil"
            aria-label={"Senaryoyu sil: " + scenario.title}
            draggable={false}
            onClick={(event) => {
              event.stopPropagation();
              onAskDelete(scenario);
            }}
          >
            <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
              <circle cx="8" cy="8" r="6.25" fill="none" stroke="currentColor" strokeWidth="1.5" />
              <line x1="5" y1="8" x2="11" y2="8" stroke="currentColor" strokeWidth="1.5"
                    strokeLinecap="round" />
            </svg>
          </button>
          <button type="button" className="sc-open unstyled-button">
            {scenario.title}
          </button>
        </div>
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

      {/* The analyst's own Açıklama when written; the steps and expectation otherwise. */}
      <td className="sc-desc">
        {scenario.summary && scenario.summary.length > 0
          ? scenario.summary.join(" ")
          : scenario.description}
      </td>

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
