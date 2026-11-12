import { useEffect, useState } from "react";
import type { ReactNode, RefCallback } from "react";
import { tokenizeGherkinLine } from "../lib/gherkin";
import { IconButton, useAutoGrow } from "../ui/InlineEdit";
import { stepExpectation } from "../lib/scenarioProse";
import type { ScenarioDoc } from "../lib/scenarioProse";
import type { AiCacheStatus, AiRunCache, AiRunSummary, WalkLine, ScenarioDetail as Detail, ScenarioEdit, SourceChange } from "../api/types";

/** The three parts of a scenario that each open for editing on their own. */
type Section = "summary" | "gherkin" | "doc";

/*
  Bir senaryo, iki dilde.

  Solda GHERKIN: çalışan hâli. Sağda DOĞAL DİL: okunan hâli, beş alan --
  başlık, kural, ön koşul, adımlar, beklenen.

  İkisi aynı şeyin iki yazımı değil, iki AYRI çalıştırma yolu. Senaryo doğal
  dille yazılır ve ajan onu tarayıcıyı sürerek uygular; Gherkin aynı senaryonun
  DETERMINISTIK TEKRARI olarak sonradan yazılır.

  İkisi veritabanında ayrı alanlarda durur. Açıklama, Gherkin ve Doğal dil'in
  HER BİRİNİN kendi kalemi var: fare (ya da klavye odağı) hangi bölümdeyse
  yalnızca onunki görünür, ve kendi Kaydet'i yalnızca kendi alanlarını
  gönderir -- sunucu gönderilmeyen alana dokunmaz (ProductService.merged).
  Senaryonun adı Doğal dil'in başlığında durduğu için onunla düzenlenir.
  `version` her kayıtta gider, o arada başkası kaydettiyse sunucu 409 ile
  reddeder.

  Gherkin kutusuna yapıştırılan tam bir dosyanın doğal dil tarafını
  bozmaması sunucuda duruyor, burada değil: ProductService.merged gelen
  Gherkin'i ayrıştırıp yalnızca Gherkin yarısını alır, etiketleri ve
  açıklamayı atar. Koruma orada olduğu için iki yarı aynı anda
  gönderilebilir.
*/
export function ScenarioDetail({
  detail,
  busy,
  onBack,
  onOpen,
  onAiRun,
  onFastRun,
  fastStatus,
  aiCache,
  aiWalk,
  aiBusy,
  aiAnswer,
  aiSummary,
  aiClosed,
  aiGherkin,
  aiGherkinBusy,
  canGenerateGherkin,
  onGenerateGherkin,
  aiNotes,
  onShowTokens,
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
  /** "Hızlı çalıştır": the cached tool calls of the last passed walk, sent again. */
  onFastRun: () => void;
  /** Whether there is a cache for this scenario as written now; null while asking. */
  fastStatus: AiCacheStatus | null;
  /** What the last run did with the step cache; null before a run. */
  aiCache: AiRunCache | null;
  /** The walk step by step, from the tape; empty before a run. */
  aiWalk: WalkLine[];
  aiBusy: boolean;
  aiAnswer: string;
  /** The walk's structured result; null when the agent answered in prose. */
  aiSummary: AiRunSummary | null;
  /** The operator closed the agent's browser mid-run. */
  aiClosed: boolean;
  /** The walk rendered into steps -- a proposal, no login line, no assertion. */
  aiGherkin: string;
  /** The walk has answered and the model is writing its Gherkin from the
   *  tape -- a second request, so the answer is already on screen. */
  aiGherkinBusy: boolean;
  /** A walk finished and its Gherkin can be asked for. Never automatic: the
   *  analyst decides whether this walk is worth a scenario. */
  canGenerateGherkin: boolean;
  onGenerateGherkin: () => void;
  /** What the render could not express. Empty is normal; silent is not. */
  aiNotes: string[];
  /** Opens Token Takibi on this walk: every turn, token and tool call. */
  onShowTokens?: () => void;
  /** Saves the fields sent; resolves to true when it was saved. */
  onSave: (edit: ScenarioEdit) => Promise<boolean>;
}) {
  /** Which part is open for editing; one at a time, each with its own Kaydet. */
  const [editing, setEditing] = useState<Section | null>(null);
  /** What the Açıklama box opened with, so an untouched fallback is not saved. */
  const [summaryPrefill, setSummaryPrefill] = useState("");
  const [nameDraft, setNameDraft] = useState("");
  const [gherkinDraft, setGherkinDraft] = useState("");
  const [summaryDraft, setSummaryDraft] = useState("");
  const [docDraft, setDocDraft] = useState<ScenarioDoc | null>(null);
  /** The name was cleared; said beside the field, cleared on the next keystroke. */
  const [nameMissing, setNameMissing] = useState(false);
  /** The Açıklama card shows its first lines until asked for all of them. */
  const [summaryOpen, setSummaryOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const { attach, fit } = useAutoGrow();
  /** The proposal has been taken; leaving it on screen would read as pending. */
  const [applied, setApplied] = useState(false);

  // Another scenario opened: any half-finished edit belongs to the old one.
  useEffect(() => {
    setEditing(null);
    setApplied(false);
    setSummaryOpen(false);
    setNameMissing(false);
  }, [detail?.path]);

  // A new walk's proposal is a new proposal, even after the last one was saved.
  useEffect(() => {
    setApplied(false);
  }, [aiGherkin]);

  if (!detail) {
    return <p className="registry-empty">{busy ? "Okunuyor…" : "Senaryo okunamadı."}</p>;
  }

  const doc: ScenarioDoc = { ...detail.doc, summary: detail.doc.summary ?? [] };
  const shown = detail.gherkin;

  const summaryWritten = doc.summary.length > 0;

  const startEdit = (section: Section) => {
    if (section === "summary") {
      /* The box opens with what the card SHOWED. With no Açıklama written the
         card shows the steps and the expectation in its place, and opening
         an empty box made that text look deleted the moment Düzenle was
         pressed. */
      const text = (summaryWritten ? doc.summary : fallbackSummary(doc)).join("\n");
      setSummaryDraft(text);
      setSummaryPrefill(text);
      // A collapsed Açıklama would hide half of what is being edited.
      setSummaryOpen(true);
    } else if (section === "gherkin") {
      setGherkinDraft(shown);
    } else {
      setNameDraft(doc.title || detail.title);
      setDocDraft({ ...doc });
      setNameMissing(false);
    }
    setEditing(section);
  };

  const cancelEdit = () => {
    setEditing(null);
    setDocDraft(null);
    setNameMissing(false);
  };

  const save = async () => {
    let edit: ScenarioEdit;
    if (editing === "summary") {
      /* The fallback opened untouched: saving it would freeze a copy of the
         steps into the Açıklama, which would then stop following them. */
      if (!summaryWritten && summaryDraft === summaryPrefill) {
        cancelEdit();
        return;
      }
      edit = { summary: summaryDraft.split("\n"), version: detail.version };
    } else if (editing === "gherkin") {
      edit = { gherkin: gherkinDraft, version: detail.version };
    } else if (editing === "doc") {
      const name = nameDraft.trim();
      if (!name) {
        // A scenario with no name cannot be found again in the list, and the
        // Scenario: line would be rewritten empty.
        setNameMissing(true);
        return;
      }
      const draft = docDraft ?? doc;
      edit = {
        title: name,
        rules: draft.rules,
        preconditions: draft.preconditions,
        steps: draft.steps,
        expected: draft.expected,
        version: detail.version,
      };
    } else {
      return;
    }
    // Saving with the "kural değişti" banner on screen IS reviewing it: the
    // analyst read what moved and then wrote. Without the banner the stamp is
    // left alone, so an unrelated fix cannot clear a badge nobody looked at.
    if (detail.changes.length > 0) edit.reviewed = true;
    setSaving(true);
    const ok = await onSave(edit);
    setSaving(false);
    if (ok) {
      setEditing(null);
      setDocDraft(null);
    }
  };

  /** The pencil, or Kaydet/Vazgeç while this part is open. */
  const controls = (section: Section, what: string) => (
    <EditControls
      section={section}
      editing={editing}
      saving={saving}
      what={what}
      onEdit={() => startEdit(section)}
      onSave={() => void save()}
      onCancel={cancelEdit}
    />
  );

  /* The proposal, under this scenario's own header. The steps replace what was
     in the Gherkin half; the natural language is not touched, because the two
     are separate fields for exactly this reason. */
  const proposal = aiGherkin ? composeFeature(shown, doc.title, aiGherkin) : "";

  const applyProposal = async () => {
    setSaving(true);
    const edit: ScenarioEdit = { gherkin: proposal, version: detail.version };
    if (detail.changes.length > 0) edit.reviewed = true;
    const ok = await onSave(edit);
    setSaving(false);
    if (ok) setApplied(true);
  };


  return (
    <div className="sd">
      <div className="sd-head">
        <button type="button" className="btn btn-ghost" onClick={onBack}>
          ← Senaryo listesi
        </button>
      </div>

      {/* The name. It is edited with Doğal dil, whose heading it is. */}
      <div className="sd-name-row">
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

      {/* --------------------------- Açıklama ---------------------------- */}
      <section className={"sd-pane sd-summary" + (editing === "summary" ? " is-editing" : "")}>
        <header className="sd-pane-head">
          <span className="sd-pane-title">AÇIKLAMA</span>
          {controls("summary", "Açıklamayı")}
        </header>
        {editing === "summary" ? (
          <textarea
            ref={attach}
            className="sd-box"
            aria-label="Açıklama"
            value={summaryDraft}
            placeholder="Bu senaryo neyi doğrular?"
            autoFocus
            onChange={(event) => {
              setSummaryDraft(event.target.value);
              fit(event.currentTarget);
            }}
          />
        ) : (
          <SummaryText doc={doc} open={summaryOpen} onToggle={() => setSummaryOpen(!summaryOpen)} />
        )}
      </section>

      <div className="sd-split">
        {/* ---------------------------- Gherkin ---------------------------- */}
        <section className={"sd-pane" + (editing === "gherkin" ? " is-editing" : "")}>
          <header className="sd-pane-head">
            <span className="sd-pane-title">GHERKİN</span>
            {controls("gherkin", "Gherkin'i")}
            <code className="sd-path">{detail.path}</code>
            <button
              type="button"
              className="btn btn-primary sd-go"
              disabled={editing !== null}
              title="Çalıştır ekranında aç: orada düzenlenir, doğrulanır ve çalıştırılır"
              onClick={() => onOpen(detail.feature)}
            >
              Git
            </button>
          </header>

          {editing === "gherkin" ? (
            <textarea
              ref={attach}
              autoFocus
              className="sd-box sd-box-code"
              aria-label="Gherkin"
              spellCheck={false}
              value={gherkinDraft}
              onChange={(event) => {
                setGherkinDraft(event.target.value);
                fit(event.currentTarget);
              }}
            />
          ) : (
            <Gherkin text={shown} />
          )}
        </section>

        {/* -------------------------- Doğal dil --------------------------- */}
        <section className={"sd-pane" + (editing === "doc" ? " is-editing" : "")}>
          <header className="sd-pane-head">
            <span className="sd-pane-title">DOĞAL DİL</span>
            {controls("doc", "Doğal dili")}
            <span className="sd-pane-gap" />
            <button
              type="button"
              className="btn btn-ghost"
              disabled={aiBusy || editing !== null}
              title="Ajan tarayıcıyı sürerek bu senaryoyu uygular ve ne gördüğünü anlatır"
              onClick={onAiRun}
            >
              {aiBusy ? "Ajan çalışıyor…" : "AI ile çalıştır"}
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={!fastStatus?.available || aiBusy || editing !== null}
              title={fastStatus?.available
                ? `Kayıtlı akış: ${fastStatus.stepCount} çağrı, sayfa okunmadan gönderilir; sonucu tek AI turu değerlendirir`
                : fastStatus?.reason ?? "Kayıtlı akış soruluyor…"}
              onClick={onFastRun}
            >
              Hızlı çalıştır
            </button>
            {onShowTokens && (aiBusy || aiAnswer || aiClosed) ? (
              <button
                type="button"
                className="btn btn-ghost"
                title="Bu ajan koşusunun her turunu, token'ını ve tool çağrısını Token Takibi'nde açar"
                onClick={onShowTokens}
              >
                {aiBusy ? "Canlı izle →" : "Token ayrıntısı →"}
              </button>
            ) : null}
          </header>

          {editing === "doc" && docDraft ? (
            <>
              <div className="sd-doc-block">
                <label className="sd-doc-label" htmlFor="sdName">İsim</label>
                <input
                  id="sdName"
                  className="search"
                  aria-invalid={nameMissing}
                  autoFocus
                  value={nameDraft}
                  onChange={(event) => {
                    setNameDraft(event.target.value);
                    if (nameMissing) setNameMissing(false);
                  }}
                />
                {nameMissing ? <span className="sd-name-error">Boş bırakılamaz</span> : null}
              </div>
              <DocEditor doc={docDraft} onChange={setDocDraft} inPlace grow={attach} onGrow={fit} />
            </>
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
          ) : aiSummary || aiAnswer ? (
            <>
              {aiCache ? <AiCacheLine cache={aiCache} /> : null}
              <AiSummaryView summary={aiSummary} answer={aiAnswer} walk={aiWalk} />
            </>
          ) : null}

          {aiGherkinBusy ? (
            <p className="sd-note">Gherkin üretiliyor…</p>
          ) : canGenerateGherkin ? (
            <header className="sd-pane-head">
              <span className="sd-pane-gap" />
              <button
                type="button"
                className="btn btn-primary"
                disabled={editing !== null}
                title="Ajanın bu koşusunu Gherkin senaryosuna çevirir (bir AI çağrısı)"
                onClick={onGenerateGherkin}
              >
                {proposal ? "Gherkin'i yeniden oluştur" : "Gherkin oluştur"}
              </button>
            </header>
          ) : null}

          {/* Notes with no proposal: the generation produced nothing to save,
              and saying why beats an empty space under the answer. */}
          {!aiGherkinBusy && !proposal && aiNotes.length > 0 ? (
            aiNotes.map((note, index) => (
              <p className="sd-note" key={index}>{note}</p>
            ))
          ) : null}

          {/* The walk, written back out as steps. Shown BESIDE the stored
              Gherkin rather than over it: a reviewed scenario being replaced
              by a second agent run, silently, is the thing SourceSnapshot
              already refuses to do for rules. The analyst presses save. */}
          {proposal && !applied && !aiGherkinBusy ? (
            <>
              <header className="sd-pane-head">
                <span className="sd-pane-title">ÖNERİLEN GHERKİN</span>
                <span className="sd-pane-gap" />
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={saving || editing !== null}
                  title="Ajanın yaptığı adımları bu senaryonun Gherkin'i olarak kaydeder"
                  onClick={() => void applyProposal()}
                >
                  {saving ? "Kaydediliyor…" : "Bu koşuyu kaydet"}
                </button>
              </header>
              <Gherkin text={proposal} />
              {hasSteps(shown) ? (
                <p className="sd-note">
                  Mevcut Gherkin'in adımları bununla değişir; doğal dil tarafına dokunulmaz.
                </p>
              ) : null}
              {aiNotes.map((note, index) => (
                <p className="sd-note" key={index}>{note}</p>
              ))}
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

/**
 * What the Açıklama card shows when none was written: the steps and the
 * expectation. The edit box opens with the same lines, so pressing Düzenle
 * never makes the text on screen disappear.
 */
function fallbackSummary(doc: ScenarioDoc): string[] {
  return [
    ...(doc.steps.length ? ["Adımlar:", ...doc.steps.map((step) => "- " + step)] : []),
    ...(doc.expected.length ? ["Beklenen:", ...doc.expected.map((item) => "- " + item)] : []),
  ];
}

/**
 * A part's pencil, shown only while the pointer (or the keyboard focus) is in
 * that part -- see .sd-edit-reveal. While a part is open it shows Kaydet and
 * Vazgeç instead, and the other parts show nothing: one edit at a time, so
 * one Kaydet never has to guess which fields it means.
 */
function EditControls({ section, editing, saving, what, onEdit, onSave, onCancel }: {
  section: Section;
  editing: Section | null;
  saving: boolean;
  /** "Açıklamayı" -- the accusative the tooltip needs: "Açıklamayı düzenle". */
  what: string;
  onEdit: () => void;
  onSave: () => void;
  onCancel: () => void;
}) {
  if (editing === section) {
    return (
      <span className="sd-edit-actions">
        <IconButton glyph="check" tone="ok" label="Kaydet"
                    title={saving ? "Kaydediliyor…" : what + " kaydet"}
                    disabled={saving} onClick={onSave} />
        <IconButton glyph="close" tone="mute" label="Vazgeç"
                    title="Değişiklikleri at" disabled={saving} onClick={onCancel} />
      </span>
    );
  }
  if (editing !== null) return null;
  return (
    <span className="sd-edit-reveal">
      <IconButton glyph="pencil" label={what + " düzenle"} title={what + " düzenle"} onClick={onEdit} />
    </span>
  );
}

/**
 * "AI ile çalıştır"'s result: what went right, what did not, what to know,
 * and one verdict. A section with nothing in it is left out. When the agent
 * answered in prose instead of reporting, the prose is shown and the verdict
 * says it was not reported -- never guessed from the wording.
 */
function AiSummaryView({ summary, answer, walk }: {
  summary: AiRunSummary | null;
  answer: string;
  walk: WalkLine[];
}) {
  return (
    <div className="sd-ai">
      <span className="sd-pane-title">AI ÖZET</span>
      <WalkView lines={walk} />
      {summary ? (
        <>
          <AiList title="Doğru ilerleyen adımlar" items={summary.succeeded} tone="ok" />
          <AiList title="Geçemeyen adımlar" items={summary.failed} tone="fail" />
          <AiList title="Uyarılar" items={summary.warnings} tone="warn" />
          {summary.verdict === "PASSED" ? (
            <p className="sd-verdict is-pass"><span aria-hidden="true">✓</span> Geçti</p>
          ) : (
            <p className="sd-verdict is-fail"><span aria-hidden="true">✗</span> Geçmedi</p>
          )}
        </>
      ) : (
        <>
          <pre className="sd-code sd-answer">{answer}</pre>
          <p className="sd-verdict is-none">Sonuç bildirilmedi — ajan yapılandırılmış bir sonuç vermedi.</p>
        </>
      )}
    </div>
  );
}

/**
 * What the last run did with the step cache behind "Hızlı çalıştır": sent the
 * cached calls, stopped on one (and dropped the cache), or walked with the
 * agent and saved (or did not save) its calls. The full log is in the
 * orchestrator console with the "[uptest] ai-cache" prefix; the same lines are
 * under "Adımlar".
 */
function AiCacheLine({ cache }: { cache: AiRunCache }) {
  const [open, setOpen] = useState(false);
  let text: string;
  switch (cache.state) {
    case "hit":
      text = `Hızlı çalıştır: ${cache.stepCount} çağrı kayıttan, sayfa okunmadan gönderildi; sonucu tek AI turu değerlendirdi.`;
      break;
    case "stopped":
      text = `Hızlı çalıştır ${cache.stoppedAt ?? "?"}. adımda durdu`
        + (cache.note ? ` (${cache.note})` : "")
        + ". Kayıtlı akış silindi; Hızlı çalıştır, 'AI ile çalıştır' yeniden geçene kadar kapalı.";
      break;
    case "walked":
      text = "Ajan ekranı gezdi.";
      break;
    default:
      text = "";
  }
  if (!text) return null;
  return (
    <div className="sd-cache">
      <p className="sd-note">
        {text}
        {cache.saved ? ` Akış: ${cache.saved}.` : ""}
      </p>
      {cache.log.length > 0 ? (
        <div className="sd-cache-actions">
          <button type="button" className="btn btn-ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
            {open ? "Adımları gizle" : "Adımlar"}
          </button>
        </div>
      ) : null}
      {open ? <pre className="sd-code sd-answer">{cache.log.join("\n")}</pre> : null}
    </div>
  );
}

const WALK_GLYPH: Record<WalkLine["kind"], string> = {
  ok: "✓",
  fail: "✗",
  refused: "⊘",
  screen: "▸",
  seen: "·",
};

/**
 * "Adım adım": the walk as it happened, read off the tape by the server --
 * logged in, which screen, what was clicked, what the screen then showed.
 * Indented by depth, so each action sits under the screen it was done on.
 */
function WalkView({ lines }: { lines: WalkLine[] }) {
  if (lines.length === 0) return null;
  return (
    <div className="sd-ai-block">
      <span className="sd-doc-label">Adım adım</span>
      <ul className="sd-walk">
        {lines.map((line, index) => (
          <li
            key={index}
            className={"sd-walk-line is-" + line.kind}
            style={{ paddingLeft: `calc(${line.depth} * var(--space-4))` }}
          >
            <span className="sd-walk-glyph" aria-hidden="true">{WALK_GLYPH[line.kind]}</span>
            {line.text}
          </li>
        ))}
      </ul>
    </div>
  );
}

function AiList({ title, items, tone }: { title: string; items: string[]; tone: "ok" | "fail" | "warn" }) {
  if (items.length === 0) return null;
  return (
    <div className={"sd-ai-block is-" + tone}>
      <span className="sd-doc-label">{title}</span>
      <ul className="sd-ai-list">
        {items.map((item, index) => <li key={index}>{item}</li>)}
      </ul>
    </div>
  );
}

/** How many lines the Açıklama card shows before "Tümünü göster". */
const SUMMARY_LINES = 8;

/**
 * The Açıklama card's text: the analyst's own Açıklama when one was written,
 * otherwise the steps and the expectation in the layout the table's
 * Açıklama column always used -- said so, so nobody mistakes the fallback
 * for a written description.
 */
function SummaryText({
  doc,
  open,
  onToggle,
}: {
  doc: ScenarioDoc;
  open: boolean;
  onToggle: () => void;
}) {
  const written = doc.summary.length > 0;
  const lines = written ? doc.summary : fallbackSummary(doc);
  if (lines.length === 0) {
    return <span className="sd-doc-empty">yok</span>;
  }
  const shown = open ? lines : lines.slice(0, SUMMARY_LINES);
  return (
    <>
      {written ? null : (
        <p className="sd-note sd-summary-note">
          Açıklama yazılmamış; adımlar ve beklenen gösteriliyor.
        </p>
      )}
      <div className="sd-summary-text">
        {shown.map((line, index) => <p key={index} className="sd-prose">{line}</p>)}
      </div>
      {lines.length > SUMMARY_LINES ? (
        <button type="button" className="unstyled-button sd-more" aria-expanded={open}
                onClick={onToggle}>
          {open ? "Daha az göster" : "Tümünü göster (" + lines.length + " satır)"}
        </button>
      ) : null}
    </>
  );
}

function DocList({
  label,
  items,
  numbered,
  chips,
  prose,
}: {
  label: string;
  items: string[];
  numbered?: boolean;
  chips?: boolean;
  /** Plain lines of text, not a list. */
  prose?: boolean;
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
      ) : prose ? (
        <div className="sd-plain">{items.map((item, i) => <p key={i} className="sd-prose">{item}</p>)}</div>
      ) : numbered ? (
        <ol className="sd-plain">
          {withExpectations(items).map((step, i) => (
            <li key={i}>
              {step.text}
              {step.expected.map((line, k) => (
                <p key={k} className="sd-step-expect">Beklenen: {line}</p>
              ))}
            </li>
          ))}
        </ol>
      ) : (
        <ul className="sd-plain">{items.map((item, i) => <li key={i}>{item}</li>)}</ul>
      )}
    </div>
  );
}

/** Steps with each "Beklenen: …" line folded under the step above it, as docAsText sends them. */
function withExpectations(items: string[]): { text: string; expected: string[] }[] {
  const out: { text: string; expected: string[] }[] = [];
  for (const item of items) {
    const expected = stepExpectation(item);
    const last = out[out.length - 1];
    if (expected !== null && last) {
      last.expected.push(expected);
    } else {
      out.push({ text: item, expected: [] });
    }
  }
  return out;
}

/** The natural-language fields, editable. Shared by Düzenle and "+ yeni senaryo". */
export function DocEditor({
  doc,
  onChange,
  afterTitle,
  inPlace,
  grow,
  onGrow,
}: {
  doc: ScenarioDoc;
  onChange: (doc: ScenarioDoc) => void;
  /** Shown right under the name -- the new-scenario form's "Başlık". */
  afterTitle?: ReactNode;
  /** Inside the detail view, where the name and the Açıklama have their own
   *  places above: this editor then shows only the fields they do not. */
  inPlace?: boolean;
  /** Makes each textarea grow to its text; see ui/InlineEdit useAutoGrow. */
  grow?: RefCallback<HTMLTextAreaElement>;
  onGrow?: (box: HTMLTextAreaElement) => void;
}) {
  const set = <K extends keyof ScenarioDoc>(key: K, value: ScenarioDoc[K]) =>
    onChange({ ...doc, [key]: value });
  return (
    <div className="sd-doc">
      {inPlace ? null : (
        <>
          <label className="sd-doc-label" htmlFor="sdTitle">İsim</label>
          <input
            id="sdTitle"
            className="search"
            value={doc.title}
            placeholder="Yeni liste bilgileri onay özetinde görünür"
            onChange={(event) => set("title", event.target.value)}
          />
          {afterTitle}
          <TextEditor label="Açıklama" items={doc.summary} placeholder="Bu senaryo neyi doğrular?"
                      onChange={(items) => set("summary", items)} />
        </>
      )}
      <ListEditor label="Kural" items={doc.rules} placeholder="R-YL-006"
                  onChange={(items) => set("rules", items)} />
      <ListEditor label="Ön koşul" items={doc.preconditions}
                  placeholder="İlgili firma verisi bulunabilmeli"
                  onChange={(items) => set("preconditions", items)} />
      <TextEditor label="Adımlar" items={doc.steps} grow={grow} onGrow={onGrow}
                  placeholder={"\"Fatura İptal Et\" bağlantısına tıkla\nBeklenen: \"Fatura İptal\" ekranı açılır\n\"Geri\" düğmesine tıkla"}
                  hint={"Bir adımın sonucunu hemen altına \"Beklenen:\" ile başlayan satırla yazın;"
                    + " o adımdan hemen sonra kontrol edilir."}
                  onChange={(items) => set("steps", items)} />
      <TextEditor label="Beklenen" items={doc.expected} grow={grow} onGrow={onGrow}
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
  grow,
  onGrow,
  hint,
}: {
  label: string;
  items: string[];
  placeholder: string;
  onChange: (items: string[]) => void;
  /** Given inside the detail view: the box is then sized to its own text
   *  rather than given a fixed height to scroll inside. */
  grow?: RefCallback<HTMLTextAreaElement>;
  onGrow?: (box: HTMLTextAreaElement) => void;
  /** One line under the label: how to write this field. */
  hint?: string;
}) {
  return (
    <div className="sd-doc-block">
      <span className="sd-doc-label">{label}</span>
      {hint ? <p className="sd-note">{hint}</p> : null}
      <textarea
        ref={grow}
        className={grow ? "sd-box" : "search sd-edit sd-edit-medium"}
        value={items.join("\n")}
        placeholder={placeholder}
        onChange={(event) => {
          onChange(event.target.value.split("\n"));
          onGrow?.(event.currentTarget);
        }}
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
/**
 * The walk's steps under this scenario's own Feature/Scenario header.
 *
 * The header is kept rather than rebuilt: it is what the analyst named the
 * scenario, and the tape knows nothing about it. One is made from the title
 * only when there is none -- a scenario whose Gherkin half was never written,
 * which is the usual case the first time a walk is recorded.
 *
 * No login line, deliberately. RunService grafts it from the CIF chosen at run
 * time, because the login is the one part of a scenario that belongs to
 * whoever runs it. So the first step takes the Given and the rest are Ands,
 * which is what LoginStep inserts in front of.
 */
function composeFeature(existing: string, title: string, steps: string): string {
  const lines = existing.split(/\r?\n/);
  const scenarioAt = lines.findIndex((line) => /^\s*Scenario:/i.test(line));
  const header =
    scenarioAt >= 0
      ? lines.slice(0, scenarioAt + 1)
      : [`Feature: ${title}`, "", `  Scenario: ${title}`];
  const body = steps
    .split(/\r?\n/)
    .map((step) => step.trim())
    .filter((step) => step.length > 0)
    .map((step, index) => `    ${index === 0 ? "Given" : "And"} ${step}`);
  return [...header, ...body, ""].join("\n");
}

/** Whether the stored Gherkin already carries steps, not just a header. */
function hasSteps(text: string): boolean {
  return text
    .split(/\r?\n/)
    .some((line) => /^\s*(Given|When|Then|And|But|\*)\s+\S/.test(line));
}

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
