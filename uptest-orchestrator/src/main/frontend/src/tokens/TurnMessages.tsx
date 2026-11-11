import { useState } from "react";
import { getAiTurnMessages } from "../api/endpoints";
import type { AiMessage, AiTurnMessages } from "../api/types";
import { Estimate, LazyText, tokens } from "./tokenParts";

const ROLE_WORD: Record<string, string> = {
  system: "system",
  user: "kullanıcı",
  assistant: "model",
  tool: "tool sonucu",
  tool_defs: "tool tanımları",
  response: "cevap",
  reasoning: "düşünce",
};

/*
  One turn's whole input, message by message, on request.

  This is the "what exactly did the model receive" view: the system prompt,
  the instruction, every earlier answer and tool result the agent sends back
  each turn, and the tool definitions. Loaded only when asked for -- a late
  turn of a walk is the whole walk.
*/
export function TurnMessages({ sessionId, seq, count }: { sessionId: string; seq: number; count: number }) {
  const [data, setData] = useState<AiTurnMessages | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");
  const [error, setError] = useState("");
  const [onlyNew, setOnlyNew] = useState(true);

  const load = async () => {
    setState("loading");
    const result = await getAiTurnMessages(sessionId, seq);
    if (result.ok) {
      setData(result.body);
      setState("idle");
    } else {
      setError(result.error);
      setState("failed");
    }
  };

  if (!data) {
    return (
      <div className="tk-text-actions">
        <button type="button" className="btn btn-ghost btn-sm" disabled={state === "loading"} onClick={() => void load()}>
          {state === "loading" ? "Okunuyor…" : "Bu turun tam girdisini göster (" + count + " mesaj)"}
        </button>
        {state === "failed" ? <span className="tk-error">Okunamadı: {error}</span> : null}
      </div>
    );
  }

  const shown = onlyNew ? data.messages.filter((m) => m.isNew) : data.messages;
  const counted = data.messages.filter((m) => m.tokens != null);
  const sum = counted.reduce((total, m) => total + (m.tokens ?? 0), 0) + (data.toolDefs?.tokens ?? 0);

  return (
    <div className="tk-messages">
      <div className="tk-messages-head">
        <span className="tk-text-label">Modele giden girdi</span>
        <span className="tk-dim">
          {data.messages.length} mesaj{counted.length ? " · parçalar " + tokens(sum) + " token" : ""}
        </span>
        <label className="tk-check">
          <input type="checkbox" checked={onlyNew} onChange={(event) => setOnlyNew(event.target.checked)} />
          yalnız bu turda yeni olanlar
        </label>
      </div>
      {data.toolDefs ? <MessageItem sessionId={sessionId} message={data.toolDefs} /> : null}
      {shown.length === 0 ? <p className="db-empty">Bu turda yeni mesaj yok — hepsi önceki turlarda gönderilmişti.</p> : null}
      <ol className="tk-message-list">
        {shown.map((m) => <li key={m.index}><MessageItem sessionId={sessionId} message={m} /></li>)}
      </ol>
      {onlyNew && shown.length < data.messages.length ? (
        <p className="db-note">{data.messages.length - shown.length} mesaj önceki turlardan aynen yeniden gönderildi (gizli).</p>
      ) : null}
    </div>
  );
}

function MessageItem({ sessionId, message: m }: { sessionId: string; message: AiMessage }) {
  const role = ROLE_WORD[m.role] ?? m.role;
  return (
    <div className={"tk-message is-" + m.role}>
      <div className="tk-message-head">
        <span className="tk-role">{role}{m.name ? ": " : ""}{m.name ? <span className="h-mono">{m.name}</span> : null}</span>
        {m.isNew ? <span className="tk-new">yeni</span> : null}
        <span className="tk-dim h-mono">
          {tokens(m.chars)} karakter{m.tokens != null ? " · " + tokens(m.tokens) + " token" : ""}
        </span>
        <Estimate on={m.exact === false} />
      </div>
      <LazyText sessionId={sessionId} sha={m.sha} chars={m.chars} />
    </div>
  );
}
