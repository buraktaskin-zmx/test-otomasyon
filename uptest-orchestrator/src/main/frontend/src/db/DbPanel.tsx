import { useRef, useState } from "react";
import { askDb } from "../api/endpoints";
import type { DbTurn } from "../api/types";
import { useAsyncAction } from "../hooks/useAsyncAction";
import type { useDbTools } from "../hooks/useDbTools";
import { EmptyState } from "../ui/EmptyState";
import { useToast } from "../ui/Toast";
import { PanelHead } from "../workspace/side/PanelHead";

/*
  Veritabanı -- asking the test Oracle a question, in Turkish.

  A conversation rather than a single box, because finding test data is
  rarely one question: "ödenmemiş faturası olan bir firma bul" is followed by
  "peki o firmanın cari hesabı ne", and the second only means anything if the
  first is still in view. The history is sent back with each question
  (capped server-side at the last 8 exchanges) so the model can resolve
  "o firma" without the analyst repeating it.

  It is NOT persisted. A reload clears it, deliberately: these answers carry
  real customer identifiers out of a bank's test database, and the one place
  they are already written down is Token Takibi, where the session can be
  reviewed and is governed by that feature's own rules.
*/

const EXAMPLES = [
  "limit_usage tablosunu anlat: kolonları, PK/FK ve ne işe yarıyor",
  "DBS fatura tabloları hangileri, aralarındaki ilişki nedir",
  "Ödenmemiş bir faturası olan bir firma bul, firma kodunu ve fatura numarasını yaz",
];

export function DbPanel({
  active,
  tools,
}: {
  active: boolean;
  tools: ReturnType<typeof useDbTools>;
}) {
  const [turns, setTurns] = useState<DbTurn[]>([]);
  const [question, setQuestion] = useState("");
  const toast = useToast();
  const endRef = useRef<HTMLDivElement | null>(null);

  const ask = useAsyncAction(async (text: string) => {
    const asked = text.trim();
    if (!asked) {
      toast("Bir soru yazın", true);
      return;
    }
    /* The question goes on screen BEFORE the request: these take ten to
       sixty seconds (a cold Oracle connection plus several tool calls), and
       a box that empties with nothing appearing reads as a lost click. */
    const sent: DbTurn[] = [...turns, { role: "user", text: asked }];
    setTurns(sent);
    setQuestion("");
    // The history sent is what came BEFORE this question; the question
    // itself travels in its own field.
    const result = await askDb(asked, turns);
    if (!result.ok) {
      setTurns([...sent, { role: "assistant", text: "⚠ " + result.error }]);
      toast("Sorgu başarısız: " + result.error, true);
      return;
    }
    setTurns([...sent, { role: "assistant", text: result.body.answer }]);
    window.setTimeout(() => endRef.current?.scrollIntoView({ block: "end" }), 0);
  });

  const busy = ask.busy;

  return (
    <div id="side-db" className={"side-panel db-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Veritabanı">
        <span className="db-tools-count">
          {tools.error ? "bağlı değil" : tools.count + " araç"}
        </span>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={busy}
          onClick={() => {
            void tools.reconnect().then((ok) => {
              toast(ok ? "Veritabanı aracı bağlandı" : "Bağlanamadı", !ok);
            });
          }}
        >
          Yeniden bağlan
        </button>
      </PanelHead>

      {tools.error ? (
        /* The server is configured but did not start. The cause is in the
           message -- a missing run.sh, a closed VPN, ORA-01017 -- and each
           needs a different fix, so it is printed rather than summarised. */
        <p className="db-error">
          Veritabanı aracı başlamadı: {tools.error}
        </p>
      ) : null}

      <div className="db-chat">
        {turns.length === 0 ? (
          <EmptyState title="Test verisi sor">
            <p className="db-hint">
              Senaryoya yazacağın gerçek bir kayıt bul. Yalnız okuma yapılır;
              kişisel veri taşıyan kolonlar maskelenir.
            </p>
          </EmptyState>
        ) : null}

        {turns.map((turn, index) => (
          <div
            key={index}
            className={"db-msg" + (turn.role === "user" ? " is-user" : " is-bot")}
          >
            <span className="db-msg-who">{turn.role === "user" ? "Sen" : "Veritabanı"}</span>
            <div className="db-msg-text">{turn.text}</div>
          </div>
        ))}

        {busy ? <div className="db-msg is-bot db-pending">Sorgulanıyor…</div> : null}
        <div ref={endRef} />
      </div>

      {turns.length === 0 ? (
        <div className="db-examples">
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              className="db-example"
              disabled={busy}
              onClick={() => void ask.run(example)}
            >
              {example}
            </button>
          ))}
        </div>
      ) : null}

      <div className="db-ask">
        <textarea
          className="search db-input"
          rows={3}
          value={question}
          disabled={busy}
          placeholder="Örn: Ödenmemiş faturası olan bir firma bul"
          onChange={(e) => setQuestion(e.target.value)}
          /* Enter sends, Shift+Enter makes a new line -- the convention
             every chat box here is judged against. */
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void ask.run(question);
            }
          }}
        />
        <div className="db-ask-actions">
          <button
            type="button"
            className="btn btn-ghost"
            disabled={busy || turns.length === 0}
            onClick={() => setTurns([])}
          >
            Temizle
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => void ask.run(question)}
          >
            {busy ? "Sorgulanıyor…" : "Sor"}
          </button>
        </div>
      </div>
    </div>
  );
}
