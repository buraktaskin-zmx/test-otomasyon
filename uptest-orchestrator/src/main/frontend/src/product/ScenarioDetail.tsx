import { readinessLabel } from "./readiness";
import { tokenizeGherkinLine } from "../lib/gherkin";
import type { ScenarioDetail as Detail } from "../api/types";

/*
  Bir senaryonun kendisi.

  The table's columns are CLAIMS the tags make — "this covers R-AL-011",
  "this needs a valid TCKN". This view is where they are checked against the
  steps, which is the whole reason a row opens rather than just expanding a
  tooltip. An analyst reading "R-AL-011" and then reading the Gherkin is the
  only mechanism that catches a tag that has drifted from what the file does;
  no parser can check that.

  Read-only. The scenarios are repository files, reviewed in git.
*/
export function ScenarioDetail({
  detail,
  busy,
  onBack,
}: {
  detail: Detail | null;
  busy: boolean;
  onBack: () => void;
}) {
  if (!detail) {
    return (
      <p className="registry-empty">{busy ? "Okunuyor…" : "Senaryo okunamadı."}</p>
    );
  }

  const state = readinessLabel(detail.readiness, detail.needsNone);

  return (
    <div className="sd">
      <button type="button" className="btn btn-ghost sd-back" onClick={onBack}>
        ← Listeye dön
      </button>

      <h3 className="sd-title">{detail.title}</h3>
      <p className="sd-path">
        {detail.path}
        {detail.problem ? null : <span className="sc-steps"> · {detail.stepCount} adım</span>}
      </p>
      {detail.description ? <p className="sd-desc">{detail.description}</p> : null}
      {detail.problem ? <p className="product-warn">{detail.problem}</p> : null}

      <div className="sd-facts">
        <section>
          <h4 className="product-section-title">Doğruladığı kurallar</h4>
          {detail.rules.length === 0 ? (
            <p className="sd-note">
              Hiçbir kurala bağlı değil. Dosyaya <code>@R-...</code> etiketi ekleyin;
              bağsız bir senaryonun amacını kimse ifade edemez.
            </p>
          ) : (
            detail.rules.map((rule) => (
              <div key={rule.id} className="sd-rule">
                <span className={"rule-chip" + (rule.known ? "" : " is-unknown")}>
                  {rule.id}
                </span>
                {rule.known ? (
                  <>
                    <span className="rule-title">{rule.title || "(başlıksız)"}</span>
                    <span className={rule.confirmed ? "rule-ok" : "rule-guess"}>
                      {rule.confirmed ? "DOĞRULANDI" : "VARSAYIM"}
                    </span>
                  </>
                ) : (
                  <span className="sd-note">rules.yaml’da böyle bir kural yok</span>
                )}
              </div>
            ))
          )}
        </section>

        <section>
          <h4 className="product-section-title">Koşmak için gereken</h4>
          <div className={"sc-ready is-" + state.kind}>
            <span className="sc-dot" aria-hidden="true" />
            {state.word}
          </div>
          <p className="sd-note">{state.hint}</p>

          {detail.needs.map((need) => (
            <div key={need.id} className="sd-need">
              <span className="sd-need-label">{need.label || need.id}</span>
              <span className="rule-id">{need.id}</span>
              {need.known ? (
                <span className="sd-note">
                  çözüm: {need.how || "?"}
                  {need.blockedBy ? " · " + need.blockedBy + " engelliyor" : ""}
                </span>
              ) : (
                <span className="sd-note">test-data.yaml’da tanımlı değil</span>
              )}
            </div>
          ))}

          {detail.tags.length > 0 ? (
            <p className="sd-note">
              Diğer etiketler: {detail.tags.map((tag) => "@" + tag).join(" ")}
            </p>
          ) : null}
        </section>
      </div>

      <h4 className="product-section-title sd-code-head">Gherkin</h4>
      <Gherkin text={detail.feature} />
    </div>
  );
}

/**
 * The same tokenizer the editor's highlight layer uses, so a scenario reads
 * identically here and there. Data, not markup: tokenizeGherkinLine returns
 * spans to render, which is what keeps a scenario containing "&lt;" from
 * becoming HTML.
 */
function Gherkin({ text }: { text: string }) {
  return (
    <pre className="sd-code">
      {text.split(/\r?\n/).map((line, index) => (
        <div key={index} className="sd-line">
          {tokenizeGherkinLine(line).map((token, slot) => (
            // cls is null for plain text; className wants undefined, and an
            // explicit null would render class="null".
            <span key={slot} className={token.cls ?? undefined}>{token.text}</span>
          ))}
          {line.length === 0 ? " " : null}
        </div>
      ))}
    </pre>
  );
}
