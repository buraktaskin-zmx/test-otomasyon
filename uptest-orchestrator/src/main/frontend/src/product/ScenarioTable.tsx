import { useEffect, useState } from "react";
import { readinessLabel } from "./readiness";
import type { RegisteredScenario, ScenarioNeed } from "../api/types";

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
*/
export function ScenarioTable({
  scenarios,
  busy,
  onOpen,
  targetsFor,
  onMove,
}: {
  scenarios: RegisteredScenario[];
  busy: boolean;
  onOpen: (scenario: RegisteredScenario) => void;
  /** Every screen this row could go to -- its own screen excluded. */
  targetsFor: (scenario: RegisteredScenario) => MoveTarget[];
  onMove: (scenario: RegisteredScenario, target: MoveTarget) => void;
}) {
  const [menuFor, setMenuFor] = useState<string | null>(null);

  // A click anywhere else closes the open menu.
  useEffect(() => {
    if (!menuFor) return;
    const close = () => setMenuFor(null);
    window.addEventListener("click", close);
    return () => window.removeEventListener("click", close);
  }, [menuFor]);

  if (scenarios.length === 0) {
    return (
      <p className="registry-empty">
        {busy
          ? "Okunuyor…"
          : "Bu ekranda kayıtlı senaryo yok. Dosyalar "
            + "products/<app>/<ürün>/scenarios/<ekran>/ altına .feature olarak konur."}
      </p>
    );
  }

  return (
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
          {scenarios.map((scenario) => (
            <tr
              key={scenario.path}
              className={"is-clickable" + (scenario.problem ? " is-broken" : "")}
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
                  title="Başka bir ekrana taşı"
                  aria-haspopup="menu"
                  aria-expanded={menuFor === scenario.path}
                  onClick={() => setMenuFor(menuFor === scenario.path ? null : scenario.path)}
                >
                  ⇄
                </button>
                {menuFor === scenario.path ? (
                  <div className="sc-move-menu" role="menu">
                    <div className="sc-move-head">TAŞI →</div>
                    {targetsFor(scenario).length === 0 ? (
                      <div className="sc-move-empty">Taşınabilecek başka ekran yok</div>
                    ) : (
                      targetsFor(scenario).map((target) => (
                        <button
                          key={target.product + "/" + target.flow}
                          type="button"
                          role="menuitem"
                          className="sc-move-item unstyled-button"
                          onClick={() => {
                            setMenuFor(null);
                            onMove(scenario, target);
                          }}
                        >
                          {target.label}
                        </button>
                      ))
                    )}
                  </div>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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
