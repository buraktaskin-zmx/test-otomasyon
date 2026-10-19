import { readinessLabel } from "./readiness";
import type { RegisteredScenario, ScenarioNeed } from "../api/types";

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
}: {
  scenarios: RegisteredScenario[];
  busy: boolean;
  onOpen: (scenario: RegisteredScenario) => void;
}) {
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
                  {scenario.problem ? null : (
                    <span className="sc-steps"> · {scenario.stepCount} adım</span>
                  )}
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
                      className={"rule-chip" + (rule.known ? "" : " is-unknown")}
                      title={rule.known
                        ? rule.title + (rule.confirmed ? "" : " (VARSAYIM)")
                        : "rules.yaml'da böyle bir kural yok"}
                    >
                      {rule.id}
                    </span>
                  ))
                )}
              </td>

              <td><Precondition scenario={scenario} /></td>

              <td className="sc-desc">{scenario.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * One word for the row, then the needs behind it in the analyst's words.
 *
 * The row's word is the WORST of its needs, because a scenario is as runnable
 * as its least available piece of data — four of five aliases ready is still
 * a scenario that cannot run.
 */
function Precondition({ scenario }: { scenario: RegisteredScenario }) {
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
          className={"sc-alias" + (need.readiness === "READY" ? "" : " is-weak")}
          title={needTitle(need)}
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
