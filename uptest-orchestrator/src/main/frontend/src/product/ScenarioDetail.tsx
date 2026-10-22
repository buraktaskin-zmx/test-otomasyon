import { useEffect, useState } from "react";
import { tokenizeGherkinLine } from "../lib/gherkin";
import type { ScenarioDoc } from "../lib/scenarioProse";
import type { ScenarioDetail as Detail, ScenarioEdit, SourceChange } from "../api/types";

/*
  Bir senaryo, iki dilde.

  Solda GHERKIN: çalışan hâli. Sağda DOĞAL DİL: okunan hâli, beş alan --
  başlık, kural, ön koşul, adımlar, beklenen.

  İkisi aynı şeyin iki yazımı değil, iki AYRI çalıştırma yolu. Senaryo doğal
  dille yazılır ve ajan onu tarayıcıyı sürerek uygular; Gherkin aynı senaryonun
  DETERMINISTIK TEKRARI olarak sonradan yazılır.

  İkisi veritabanında ayrı alanlarda durur ve her yarının Kaydet'i yalnızca
  kendi alanlarını gönderir: Gherkin kutusuna yapıştırılan tam bir dosya
  doğal dil tarafına dokunamaz. `version` da gider; o arada başkası
  kaydettiyse sunucu 409 ile reddeder.
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
  /** Saves the fields sent; resolves to true when it was saved. */
  onSave: (edit: ScenarioEdit) => Promise<boolean>;
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

  const doc: ScenarioDoc = detail.doc;
  const shown = detail.gherkin;

  const editGherkin = () => {
    setDraft(shown);
    setEditing("gherkin");
  };

  const editProse = () => {
    setDocDraft({ ...doc });
    setEditing("prose");
  };

  const save = async () => {
    const edit: ScenarioEdit = editing === "prose" && docDraft
      ? { ...docDraft, version: detail.version }
      : { gherkin: draft, version: detail.version };
    // Saving with the "kural değişti" banner on screen IS reviewing it: the
    // analyst read what moved and then wrote. Without the banner the stamp is
    // left alone, so an unrelated fix cannot clear a badge nobody looked at.
    if (detail.changes.length > 0) edit.reviewed = true;
    setSaving(true);
    const ok = await onSave(edit);
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

      {detail.changes.length > 0 ? (
        <Changed
          changes={detail.changes}
          busy={saving}
          onReviewed={async () => {
            setSaving(true);
            await onSave({ reviewed: true, version: detail.version });
            setSaving(false);
          }}
        />
      ) : null}

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
            {editButton("prose", editProse)}
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
            <DocEditor doc={docDraft} onChange={setDocDraft} />
          ) : (
            <div className="sd-doc">
              <h4 className="sd-plain-title">{doc.title}</h4>
              <DocList label="Kural" items={doc.rules} chips />
              <DocList label="Ön koşul" items={doc.preconditions} />
              <DocList label="Adımlar" items={doc.steps} numbered />
              <DocList label="Beklenen" items={doc.expected} />
            </div>
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

/** Turkish names for the field keys the server sends. */
const FIELD_NAMES: Record<string, string> = {
  title: "Başlık",
  status: "Durum",
  description: "Açıklama",
  messages: "Mesajlar",
  label: "Kısa ad",
  how: "Çözüm",
  value: "Değer",
  blockedBy: "Engel",
};

function fieldName(field: string) {
  if (field.startsWith("deger:")) return "Değer listesi: " + field.slice("deger:".length);
  return FIELD_NAMES[field] ?? field;
}

/**
 * Ne değişti, ve senaryonun hangi satırına bakılmalı.
 *
 * Deliberately shows and does not act. The same difference means two
 * opposite things -- the application changed and the rule caught up, so the
 * scenario should follow; or the rule was simply written down wrong and has
 * been corrected, in which case the scenario should change AND the run
 * should stay red over a defect that is still there. Rewriting the scenario
 * from the rule would hide that second case behind a green test, which is
 * the oracle problem arriving through the back door. So: the difference, the
 * lines worth looking at, and two ways out that a person chooses between.
 */
function Changed({
  changes,
  busy,
  onReviewed,
}: {
  changes: SourceChange[];
  busy: boolean;
  onReviewed: () => void;
}) {
  return (
    <section className="sd-changed">
      <header className="sd-changed-head">
        <span className="sd-changed-title">
          ⚠ Bu senaryo yazıldıktan sonra dayandığı tanım değişti
        </span>
        <button type="button" className="btn btn-ghost" disabled={busy}
                title="Senaryo olduğu gibi kalsın; yeni hâli okudum. Kural baştan yanlış yazılmışsa doğru çıkış budur: test kırmızı kalmalı, çünkü uygulamadaki hata duruyor"
                onClick={onReviewed}>
          {busy ? "Kaydediliyor…" : "Gözden geçirildi"}
        </button>
      </header>

      {changes.map((change) => (
        <div className="sd-change" key={change.kind + change.id}>
          <div className="sd-change-id">
            <span className="rule-id">{change.id}</span>
            {change.title ? <span className="sd-change-name">{change.title}</span> : null}
            {change.state === "GONE" ? (
              <span className="sd-change-gone">
                {change.kind === "RULE"
                  ? "rules.yaml'dan kaldırılmış"
                  : "test-data.yaml'dan kaldırılmış"}
              </span>
            ) : null}
          </div>

          {change.removed.length > 0 || change.added.length > 0 ? (
            <div className="sd-change-values">
              {change.removed.map((value) => (
                <span className="sd-value is-removed" key={"-" + value}>− {value}</span>
              ))}
              {change.added.map((value) => (
                <span className="sd-value is-added" key={"+" + value}>+ {value}</span>
              ))}
            </div>
          ) : null}

          {change.fields.map((field) => (
            <div className="sd-change-field" key={field.field}>
              <span className="sd-doc-label">{fieldName(field.field)}</span>
              <pre className="sd-code sd-diff is-before">{field.before || "(boştu)"}</pre>
              <pre className="sd-code sd-diff is-after">{field.after || "(boş)"}</pre>
            </div>
          ))}

          {change.mentions.length > 0 ? (
            <div className="sd-change-field">
              <span className="sd-doc-label">Senaryoda hâlâ geçiyor</span>
              <ul className="sd-plain">
                {change.mentions.map((mention, index) => (
                  <li key={index}>
                    <span className="sd-value is-removed">{mention.value}</span>
                    {" · " + mention.where + ": "}
                    <code>{mention.line}</code>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      ))}
    </section>
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

/** The five natural-language fields, editable. Shared by Düzenle and "+ yeni senaryo". */
export function DocEditor({
  doc,
  onChange,
}: {
  doc: ScenarioDoc;
  onChange: (doc: ScenarioDoc) => void;
}) {
  const set = <K extends keyof ScenarioDoc>(key: K, value: ScenarioDoc[K]) =>
    onChange({ ...doc, [key]: value });
  return (
    <div className="sd-doc">
      <label className="sd-doc-label" htmlFor="sdTitle">Başlık</label>
      <input
        id="sdTitle"
        className="search"
        value={doc.title}
        placeholder="Yeni liste bilgileri onay özetinde görünür"
        onChange={(event) => set("title", event.target.value)}
      />
      <ListEditor label="Kural" items={doc.rules} placeholder="R-YL-006"
                  onChange={(items) => set("rules", items)} />
      <ListEditor label="Ön koşul" items={doc.preconditions}
                  placeholder="İlgili firma verisi bulunabilmeli"
                  onChange={(items) => set("preconditions", items)} />
      <TextEditor label="Adımlar" items={doc.steps}
                  placeholder={"Alıcı Listesi İşlemleri ekranını aç\nİşlemler panelinden Yeni Liste Oluştur'a tıkla"}
                  onChange={(items) => set("steps", items)} />
      <TextEditor label="Beklenen" items={doc.expected}
                  placeholder={"İşlem Bilgileri ekranı görünür\nDöviz Cinsi: TL"}
                  onChange={(items) => set("expected", items)} />
    </div>
  );
}

/** Free text, one item per line. Blank lines are dropped when the file is written. */
function TextEditor({
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
      <span className="sd-doc-label">{label} <small>(her satır bir madde)</small></span>
      <textarea
        className="search sd-edit sd-edit-medium"
        value={items.join("\n")}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value.split("\n"))}
      />
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
