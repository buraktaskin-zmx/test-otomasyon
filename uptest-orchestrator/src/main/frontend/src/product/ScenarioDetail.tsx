import { useEffect, useState } from "react";
import { readinessLabel } from "./readiness";
import { tokenizeGherkinLine } from "../lib/gherkin";
import { plainSteps } from "../lib/plainTurkish";
import { readProse, writeProse } from "../lib/scenarioProse";
import type { ScenarioDetail as Detail } from "../api/types";

/*
  Bir senaryo, iki dilde.

  Solda GHERKIN: çalışan hâli. Sağda DOĞAL DİL: okunan hâli.

  İkisi aynı şeyin iki yazımı değil, iki AYRI çalıştırma yolu. Senaryo doğal
  dille yazılır ve ajan onu tarayıcıyı sürerek uygular; Gherkin aynı senaryonun
  DETERMINISTIK TEKRARI olarak sonradan yazılır.

  Her iki yarının kendi Düzenle / Kaydet düğmesi var ve ikisi de AYNI .feature
  dosyasını yazar: Gherkin tarafı dosyanın tamamını, doğal dil tarafı yalnızca
  Scenario başlığını ve altındaki açıklamayı (bkz. lib/scenarioProse).
*/
export function ScenarioDetail({
  detail,
  busy,
  onBack,
  onRun,
  onAiRun,
  aiBusy,
  aiAnswer,
  aiClosed,
  onSave,
}: {
  detail: Detail | null;
  busy: boolean;
  onBack: () => void;
  onRun: (feature: string) => void;
  /** Hands the prose to the browsing agent -- the same call as Ekran Keşfi's
   *  Serbest Gözlem. */
  onAiRun: () => void;
  aiBusy: boolean;
  aiAnswer: string;
  /** The operator closed the agent's browser mid-run. */
  aiClosed: boolean;
  /** Writes the whole file; resolves to true when it was saved. */
  onSave: (feature: string) => Promise<boolean>;
}) {
  const [editing, setEditing] = useState<"gherkin" | "prose" | null>(null);
  const [draft, setDraft] = useState("");
  const [titleDraft, setTitleDraft] = useState("");
  const [saving, setSaving] = useState(false);

  // Another scenario opened: any half-finished edit belongs to the old one.
  useEffect(() => setEditing(null), [detail?.path]);

  if (!detail) {
    return <p className="registry-empty">{busy ? "Okunuyor…" : "Senaryo okunamadı."}</p>;
  }

  const steps = plainSteps(detail.feature);
  const state = readinessLabel(detail.readiness, detail.needsNone);
  const hasGherkin = detail.stepCount > 0;
  const prose = readProse(detail.feature);

  const editGherkin = () => {
    setDraft(detail.feature);
    setEditing("gherkin");
  };

  const editProse = () => {
    setTitleDraft(prose?.title ?? detail.title);
    setDraft(prose?.body ?? "");
    setEditing("prose");
  };

  const save = async () => {
    const feature = editing === "prose"
      ? writeProse(detail.feature, { title: titleDraft, body: draft })
      : draft;
    setSaving(true);
    const ok = await onSave(feature);
    setSaving(false);
    if (ok) setEditing(null);
  };

  const editButton = (pane: "gherkin" | "prose", start: () => void) =>
    editing === pane ? (
      <>
        <button type="button" className="btn btn-ghost" disabled={saving}
                onClick={() => setEditing(null)}>
          Vazgeç
        </button>
        <button type="button" className="btn btn-primary" disabled={saving}
                onClick={() => void save()}>
          {saving ? "Kaydediliyor…" : "Kaydet"}
        </button>
      </>
    ) : (
      <button type="button" className="btn btn-ghost" disabled={editing !== null}
              onClick={start}>
        Düzenle
      </button>
    );

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
            {editButton("gherkin", editGherkin)}
            {editing === "gherkin" ? null : (
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
            )}
          </header>

          {editing === "gherkin" ? (
            <textarea
              className="search sd-edit"
              spellCheck={false}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
            />
          ) : (
            <Gherkin text={detail.feature} />
          )}
        </section>

        {/* -------------------------- Doğal dil --------------------------- */}
        <section className="sd-pane">
          <header className="sd-pane-head">
            <span className="sd-pane-title">DOĞAL DİL</span>
            <span className="sd-pane-gap" />
            {prose ? editButton("prose", editProse) : null}
            {editing === "prose" ? null : (
              <button
                type="button"
                className="btn btn-ghost"
                disabled={aiBusy}
                title="Ajan tarayıcıyı sürerek bu senaryoyu uygular ve ne gördüğünü anlatır"
                onClick={onAiRun}
              >
                {aiBusy ? "Ajan çalışıyor…" : "AI ile çalıştır"}
              </button>
            )}
          </header>

          {editing === "prose" ? (
            <>
              <input
                className="search"
                value={titleDraft}
                aria-label="Senaryo başlığı"
                onChange={(event) => setTitleDraft(event.target.value)}
              />
              <textarea
                className="search sd-edit"
                value={draft}
                aria-label="Senaryo metni"
                onChange={(event) => setDraft(event.target.value)}
              />
            </>
          ) : (
            <>
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
            </>
          )}

          {aiBusy ? (
            <p className="sd-note">Tarayıcı sürülüyor, bu birkaç dakika sürebilir…</p>
          ) : aiClosed ? (
            <p className="sd-note browser-closed">
              <span aria-hidden="true">✗</span> Beklenmedik şekilde kapandı
            </p>
          ) : aiAnswer ? (
            <>
              <span className="sd-pane-title">AJANIN ANLATTIĞI</span>
              <pre className="sd-code sd-answer">{aiAnswer}</pre>
            </>
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
          {line.length === 0 ? " " : null}
        </div>
      ))}
    </pre>
  );
}
