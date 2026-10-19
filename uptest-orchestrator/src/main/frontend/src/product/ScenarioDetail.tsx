import { readinessLabel } from "./readiness";
import { tokenizeGherkinLine } from "../lib/gherkin";
import { plainSteps } from "../lib/plainTurkish";
import type { ScenarioDetail as Detail } from "../api/types";

/*
  Bir senaryo, iki dilde.

  Solda GHERKIN: çalışan hâli. Sağda DOĞAL DİL: okunan hâli.

  İkisi aynı şeyin iki yazımı değil, iki AYRI çalıştırma yolu. Senaryo doğal
  dille yazılır ve ajan onu tarayıcıyı sürerek uygular; Gherkin aynı senaryonun
  DETERMINISTIK TEKRARI olarak sonradan yazılır. Bu yüzden bir dosyanın "0
  adım" olması eksiklik değil bilgi: doğal dil hazır, kayıtlı adımlar henüz
  yazılmamış.

  Salt okunur. Senaryolar git'te yaşıyor, orada gözden geçiriliyor.
*/
export function ScenarioDetail({
  detail,
  busy,
  onBack,
  onRun,
}: {
  detail: Detail | null;
  busy: boolean;
  onBack: () => void;
  onRun: (feature: string) => void;
}) {
  if (!detail) {
    return <p className="registry-empty">{busy ? "Okunuyor…" : "Senaryo okunamadı."}</p>;
  }

  const steps = plainSteps(detail.feature);
  const state = readinessLabel(detail.readiness, detail.needsNone);
  const hasGherkin = detail.stepCount > 0;

  return (
    <div className="sd">
      <div className="sd-head">
        <button type="button" className="btn btn-ghost" onClick={onBack}>
          ← Senaryo listesi
        </button>
        <h3 className="sd-title">{detail.title}</h3>
      </div>

      <div className="sd-split">
        {/* ---------------------------- Gherkin ---------------------------- */}
        <section className="sd-pane">
          <header className="sd-pane-head">
            <span className="sd-pane-title">GHERKİN</span>
            <code className="sd-path">{detail.path}</code>
            <button
              type="button"
              className="btn btn-primary"
              disabled={!hasGherkin}
              title={hasGherkin
                ? "Kayıtlı adımlarla çalıştır"
                : "Bu senaryonun kayıtlı adımları henüz yazılmadı"}
              onClick={() => onRun(detail.feature)}
            >
              Çalıştır
            </button>
          </header>

          {hasGherkin ? null : (
            <p className="sd-note sd-pane-note">
              Kayıtlı adım yok — bu senaryonun yalnızca doğal dil hâli yazılmış.
              Deterministik tekrarı için adımlar sözlükten yazılmalı.
            </p>
          )}

          <Gherkin text={detail.feature} />
        </section>

        {/* -------------------------- Doğal dil --------------------------- */}
        <section className="sd-pane">
          <header className="sd-pane-head">
            <span className="sd-pane-title">DOĞAL DİL</span>
            <span className="sd-pane-gap" />
            <button
              type="button"
              className="btn btn-ghost"
              disabled
              title="Ajanın giriş bilgilerine ihtiyacı var — henüz bağlanmadı"
            >
              AI ile çalıştır
            </button>
          </header>

          <h4 className="sd-plain-title">{detail.title}</h4>

          <ol className="sd-plain">
            <li>
              Ön koşul:{" "}
              {detail.needs.length === 0
                ? state.word
                : detail.needs.map((need) => need.label || need.id).join(", ")}{" "}
              <span className={"sd-badge is-" + state.kind}>{state.word}</span>
            </li>
            {detail.description ? <li>{detail.description}</li> : null}
            {/* Translated from the REAL steps, not from the prose: when a
                scenario has a deterministic replay, what it actually does is
                what should be read. */}
            {steps.map((step, index) => (
              <li key={index} className={step.translated ? undefined : "sd-untranslated"}>
                {step.text}
              </li>
            ))}
          </ol>

          {detail.rules.length > 0 ? (
            <p className="sd-note">
              Doğruladığı kurallar:{" "}
              {detail.rules.map((rule) => rule.id).join(", ")}
            </p>
          ) : null}
        </section>
      </div>
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
