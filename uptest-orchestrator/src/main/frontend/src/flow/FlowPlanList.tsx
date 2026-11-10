import type { FlowIntent, FlowPlan, FlowTree } from "../api/types";

/*
  Stage 4: the scenarios, in Turkish, before any Gherkin is written.

  This list is the whole point of planning separately. Deciding which
  twenty-five scenarios to write is judgement, and judgement is worth
  checking BEFORE twenty-five model calls are spent on it. Deleting a line
  here costs nothing; deleting a generated scenario costs the call that made
  it.

  It used to show a title and a one-sentence intent per row -- a note about a
  scenario rather than the scenario. An analyst reading it could not see what
  the test actually does, what has to be true before it runs, or which rule it
  is there to prove, so every row had to be opened somewhere else to be judged.
  Now each row carries the three parts the library itself stores (ön koşul,
  adımlar, beklenen) plus the rules it exercises, which is everything needed to
  accept or reject it on sight.

  Each prose field is a textarea holding ONE ITEM PER LINE. That is not a
  shortcut around a list editor: these come back from the model already split,
  an analyst reorders and rewords them in bulk far more often than they add a
  single one, and a line is the unit they already think in. Blank lines are
  dropped on the way back, so trailing newlines while typing cost nothing.
*/

/** The three prose fields, in the order a scenario is read. */
const PROSE: ReadonlyArray<{
  key: "preconditions" | "steps" | "expected";
  label: string;
  hint: string;
}> = [
  {
    key: "preconditions",
    label: "Ön koşul",
    /* Said on the form, not only in the prompt: this is the distinction the
       whole feature turns on, and the expensive direction is writing an
       expectation here -- a missing fixture then fails red as though the
       application were broken. */
    hint: "koşmadan önce ortamda doğru olması gerekenler — doğrulanmaz",
  },
  { key: "steps", label: "Adımlar", hint: "sırayla yapılanlar" },
  {
    key: "expected",
    label: "Beklenen",
    hint: "uygulamanın yapmak zorunda olduğu — tutmazsa hata",
  },
];

export function FlowPlanList({
  plan,
  flow,
  busy,
  onChange,
}: {
  plan: FlowPlan;
  flow: FlowTree;
  busy: boolean;
  onChange: (plan: FlowPlan) => void;
}) {
  const titles = new Map(flow.nodes.map((node) => [node.id, node.title]));
  const pathOf = (intent: FlowIntent) =>
    intent.path.map((id) => titles.get(id) ?? id).join(" → ");

  const remove = (id: string) =>
    onChange({ ...plan, scenarios: plan.scenarios.filter((s) => s.id !== id) });

  const patch = (id: string, change: Partial<FlowIntent>) =>
    onChange({
      ...plan,
      scenarios: plan.scenarios.map((s) => (s.id === id ? { ...s, ...change } : s)),
    });

  /* Blank lines dropped, so a trailing newline mid-typing is not an empty
     step; the text itself is kept verbatim otherwise. */
  const toLines = (text: string) =>
    text.split("\n").map((line) => line.trim()).filter((line) => line.length > 0);

  const add = () => {
    const first = flow.nodes[0];
    const id = "s" + (plan.scenarios.length + 1) + "-" + Date.now().toString(36);
    onChange({
      ...plan,
      scenarios: [...plan.scenarios, {
        id, title: "Yeni senaryo", intent: "",
        path: first ? [first.id] : [], ruleIds: [], negative: false,
        preconditions: [], steps: [], expected: [],
      }],
    });
  };

  return (
    <section className="fc-editor">
      <div className="db-card-head">
        <h3 className="db-card-title">Senaryolar</h3>
        <span className="db-card-aside">{plan.scenarios.length} senaryo</span>
      </div>
      <p className="db-note">
        Doğal Türkçe senaryolar; Gherkin bir sonraki adımda yazılır. Üretmeden
        önce silin, ekleyin, düzeltin — burada bir satır silmek bedava, üretilmiş
        bir senaryoyu silmek onu üreten çağrı kadar.
      </p>

      {plan.notes.map((note, i) => <p key={i} className="product-warn">{note}</p>)}

      {plan.scenarios.length === 0 ? (
        <p className="db-empty">Henüz senaryo yok. "Senaryoları Planla" ile başlayın.</p>
      ) : (
        <ol className="fc-plan">
          {plan.scenarios.map((intent) => (
            <li key={intent.id} className={intent.negative ? "is-negative" : ""}>
              <div className="fc-plan-row">
                <input
                  className="search fc-plan-title"
                  value={intent.title}
                  disabled={busy}
                  aria-label="Senaryo başlığı"
                  onChange={(event) => patch(intent.id, { title: event.target.value })}
                />
                <span className="fc-plan-path" title={pathOf(intent)}>{pathOf(intent)}</span>
                {intent.negative ? <span className="db-risk">reddetme</span> : null}
                <button type="button" className="sc-delete unstyled-button is-plain"
                        disabled={busy} title="Bu senaryoyu listeden çıkar"
                        aria-label={intent.title + " senaryosunu listeden çıkar"}
                        onClick={() => remove(intent.id)}>×</button>
              </div>

              {/* The rules this scenario is there to prove. Its own row with a
                  word in front of it: a bare chip said which rule only to
                  someone who already knew the chips were rules. Absence is
                  said out loud too, because a scenario bound to nothing is a
                  scenario nobody can trace back to a requirement. */}
              <div className="fc-plan-rules">
                <span className="fc-plan-label">Test ettiği kurallar</span>
                {intent.ruleIds.length === 0 ? (
                  <span className="fc-plan-hint">yok — hiçbir kurala bağlı değil</span>
                ) : (
                  intent.ruleIds.map((rule) => (
                    <span key={rule} className="rule-chip">{rule}</span>
                  ))
                )}
              </div>

              <input
                className="search fc-plan-intent"
                value={intent.intent}
                disabled={busy}
                placeholder="tek cümlelik özet"
                aria-label="Senaryo özeti"
                onChange={(event) => patch(intent.id, { intent: event.target.value })}
              />

              {PROSE.map((field) => (
                <div key={field.key} className="fc-plan-prose">
                  <label className="fc-plan-label" htmlFor={intent.id + "-" + field.key}>
                    {field.label}
                    <span className="fc-plan-hint">{field.hint}</span>
                  </label>
                  <textarea
                    id={intent.id + "-" + field.key}
                    className="search fc-plan-text"
                    rows={Math.max(2, intent[field.key].length)}
                    value={intent[field.key].join("\n")}
                    disabled={busy}
                    placeholder="her satıra bir madde"
                    onChange={(event) =>
                      patch(intent.id, { [field.key]: toLines(event.target.value) })}
                  />
                </div>
              ))}
            </li>
          ))}
        </ol>
      )}

      <div className="fc-editor-actions">
        <button type="button" className="btn btn-ghost" disabled={busy} onClick={add}>
          + Senaryo ekle
        </button>
      </div>
    </section>
  );
}
