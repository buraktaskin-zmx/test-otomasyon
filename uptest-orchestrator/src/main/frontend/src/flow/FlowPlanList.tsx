import type { FlowIntent, FlowPlan, FlowTree } from "../api/types";

/*
  Stage 4: the plan, before any scenario is written.

  This list is the whole point of planning separately. Deciding which
  twenty-five scenarios to write is judgement, and judgement is worth
  checking BEFORE twenty-five model calls are spent on it. Deleting a line
  here costs nothing; deleting a generated scenario costs the call that made
  it.
*/
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

  const add = () => {
    const first = flow.nodes[0];
    const id = "s" + (plan.scenarios.length + 1) + "-" + Date.now().toString(36);
    onChange({
      ...plan,
      scenarios: [...plan.scenarios, {
        id, title: "Yeni senaryo", intent: "",
        path: first ? [first.id] : [], ruleIds: [], negative: false,
      }],
    });
  };

  return (
    <section className="fc-editor">
      <div className="db-card-head">
        <h3 className="db-card-title">Senaryo planı</h3>
        <span className="db-card-aside">{plan.scenarios.length} senaryo</span>
      </div>
      <p className="db-note">
        Her satır bir senaryo niyeti; senaryonun kendisi değil. Üretmeden önce
        silin, ekleyin, düzeltin — burada bir satır silmek bedava, üretilmiş bir
        senaryoyu silmek onu üreten çağrı kadar.
      </p>

      {plan.notes.map((note, i) => <p key={i} className="product-warn">{note}</p>)}

      {plan.scenarios.length === 0 ? (
        <p className="db-empty">Plan boş. "Senaryoları Planla" ile başlayın.</p>
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
                {intent.ruleIds.map((rule) => (
                  <span key={rule} className="rule-chip">{rule}</span>
                ))}
                {intent.negative ? <span className="db-risk">reddetme</span> : null}
                <button type="button" className="sc-delete unstyled-button is-plain"
                        disabled={busy} title="Bu senaryoyu plandan çıkar"
                        aria-label={intent.title + " senaryosunu plandan çıkar"}
                        onClick={() => remove(intent.id)}>×</button>
              </div>
              <input
                className="search fc-plan-intent"
                value={intent.intent}
                disabled={busy}
                placeholder="ne yapılıyor ve ne bekleniyor"
                aria-label="Senaryo niyeti"
                onChange={(event) => patch(intent.id, { intent: event.target.value })}
              />
            </li>
          ))}
        </ol>
      )}

      <div className="fc-editor-actions">
        <button type="button" className="btn btn-ghost" disabled={busy} onClick={add}>
          + Satır ekle
        </button>
      </div>
    </section>
  );
}
