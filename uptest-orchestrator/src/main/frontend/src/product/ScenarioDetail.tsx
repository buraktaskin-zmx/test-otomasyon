import { useEffect, useState } from "react";
import { tokenizeGherkinLine } from "../lib/gherkin";
import { joinTags, readDoc, splitTags, writeDoc } from "../lib/scenarioProse";
import type { ScenarioDoc } from "../lib/scenarioProse";
import type { ScenarioDetail as Detail } from "../api/types";

/*
  Bir senaryo, iki dilde.

  Solda GHERKIN: çalışan hâli. Sağda DOĞAL DİL: okunan hâli, beş alan --
  başlık, kural, ön koşul, adımlar, beklenen (bkz. lib/scenarioProse).

  İkisi aynı şeyin iki yazımı değil, iki AYRI çalıştırma yolu. Senaryo doğal
  dille yazılır ve ajan onu tarayıcıyı sürerek uygular; Gherkin aynı senaryonun
  DETERMINISTIK TEKRARI olarak sonradan yazılır.

  Her iki yarının kendi Düzenle / Kaydet düğmesi var ve ikisi de AYNI .feature
  dosyasını yazar.
*/
export function ScenarioDetail({
  detail,
  busy,
  onBack,
  onOpen,
  onAiRun,
  aiBusy,
  aiAnswer,
  aiClosed,
  onSave,
}: {
  detail: Detail | null;
  busy: boolean;
  onBack: () => void;
  /** "Git": open this scenario in the Çalıştır editor. */
  onOpen: (feature: string) => void;
  /** Hands the scenario to the browsing agent -- the same call as Ekran
   *  Keşfi's Serbest Gözlem. */
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
  const [docDraft, setDocDraft] = useState<ScenarioDoc | null>(null);
  const [saving, setSaving] = useState(false);

  // Another scenario opened: any half-finished edit belongs to the old one.
  useEffect(() => setEditing(null), [detail?.path]);

  if (!detail) {
    return <p className="registry-empty">{busy ? "Okunuyor…" : "Senaryo okunamadı."}</p>;
  }

  const doc = readDoc(detail.feature);
  // Tag lines are hidden in the Gherkin view and editor, and put back on save.
  const { tags, body: shown } = splitTags(detail.feature);

  const editGherkin = () => {
    setDraft(shown);
    setEditing("gherkin");
  };

  const editProse = () => {
    if (!doc) return;
    setDocDraft({ ...doc });
    setEditing("prose");
  };

  const save = async () => {
    const feature = editing === "prose" && docDraft
      ? writeDoc(detail.feature, docDraft)
      : joinTags(tags, draft);
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

  const setField = <K extends keyof ScenarioDoc>(key: K, value: ScenarioDoc[K]) =>
    setDocDraft((was) => (was ? { ...was, [key]: value } : was));

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
                title="Çalıştır ekranında aç: orada düzenlenir, doğrulanır ve çalıştırılır"
                onClick={() => onOpen(detail.feature)}
              >
                Git
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
            <Gherkin text={shown} />
          )}
        </section>

        {/* -------------------------- Doğal dil --------------------------- */}
        <section className="sd-pane">
          <header className="sd-pane-head">
            <span className="sd-pane-title">DOĞAL DİL</span>
            <span className="sd-pane-gap" />
            {doc ? editButton("prose", editProse) : null}
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

          {editing === "prose" && docDraft ? (
            <div className="sd-doc">
              <label className="sd-doc-label" htmlFor="sdTitle">Başlık</label>
              <input
                id="sdTitle"
                className="search"
                value={docDraft.title}
                onChange={(event) => setField("title", event.target.value)}
              />
              <ListEditor label="Kural" items={docDraft.rules} placeholder="R-YL-006"
                          onChange={(items) => setField("rules", items)} />
              <ListEditor label="Ön koşul" items={docDraft.preconditions}
                          placeholder="İlgili firma verisi bulunabilmeli"
                          onChange={(items) => setField("preconditions", items)} />
              <ListEditor label="Adımlar" items={docDraft.steps}
                          placeholder="Alıcı Listesi İşlemleri ekranını aç"
                          onChange={(items) => setField("steps", items)} />
              <ListEditor label="Beklenen" items={docDraft.expected}
                          placeholder="İşlem Bilgileri ekranı görünür"
                          onChange={(items) => setField("expected", items)} />
            </div>
          ) : doc ? (
            <div className="sd-doc">
              <h4 className="sd-plain-title">{doc.title}</h4>
              <DocList label="Kural" items={doc.rules} chips />
              <DocList label="Ön koşul" items={doc.preconditions} />
              <DocList label="Adımlar" items={doc.steps} numbered />
              <DocList label="Beklenen" items={doc.expected} />
            </div>
          ) : (
            <p className="sd-note">Bu dosyada bir Scenario bulunamadı.</p>
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

function DocList({
  label,
  items,
  numbered,
  chips,
}: {
  label: string;
  items: string[];
  numbered?: boolean;
  chips?: boolean;
}) {
  return (
    <div className="sd-doc-block">
      <span className="sd-doc-label">{label}</span>
      {items.length === 0 ? (
        <span className="sd-doc-empty">yok</span>
      ) : chips ? (
        <div className="sd-doc-chips">
          {items.map((item) => <span key={item} className="rule-id">{item}</span>)}
        </div>
      ) : numbered ? (
        <ol className="sd-plain">{items.map((item, i) => <li key={i}>{item}</li>)}</ol>
      ) : (
        <ul className="sd-plain">{items.map((item, i) => <li key={i}>{item}</li>)}</ul>
      )}
    </div>
  );
}

/** One input per item, with remove and "+ ekle". */
function ListEditor({
  label,
  items,
  placeholder,
  onChange,
}: {
  label: string;
  items: string[];
  placeholder: string;
  onChange: (items: string[]) => void;
}) {
  return (
    <div className="sd-doc-block">
      <span className="sd-doc-label">{label}</span>
      {items.map((item, index) => (
        <div className="sd-doc-item" key={index}>
          <input
            className="search"
            value={item}
            placeholder={placeholder}
            onChange={(event) =>
              onChange(items.map((was, i) => (i === index ? event.target.value : was)))}
          />
          <button
            type="button"
            className="btn btn-ghost sd-doc-remove"
            title="Sil"
            onClick={() => onChange(items.filter((_, i) => i !== index))}
          >
            ×
          </button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost sd-doc-add"
              onClick={() => onChange([...items, ""])}>
        + ekle
      </button>
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
