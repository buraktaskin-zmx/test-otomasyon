import { useEffect, useState } from "react";
import { getAiTurnMessages, getAiTurnTranscript } from "../api/endpoints";
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

type Mode = "list" | "one";

/** Message by message, with each part's size — or the whole turn as one text. */
const MODES: Array<[Mode, string]> = [["list", "mesaj mesaj"], ["one", "tek metin"]];

/*
  One turn's whole input and output, on request.

  This is the "what exactly did the model receive, and what came back" view:
  the system prompt, the instruction, the tool definitions, every earlier
  answer and tool result the agent resends each turn, then the model's own
  answer, its reasoning and the calls it asked for. Loaded only when asked
  for -- a late turn of a walk is the whole walk.

  TWO VIEWS, because they answer different questions. "mesaj mesaj" prints
  each part with its own character and token count, which is how an expensive
  part is found. "tek metin" prints the request as one document in the order
  the model read it, which is how it is READ, copied, and diffed against
  another turn; one request instead of one per message.

  Everything a part shows was masked on its way to disk (SecretMask), so no
  password reaches this panel to be hidden here.
*/
export function TurnMessages({ sessionId, seq, count }: { sessionId: string; seq: number; count: number }) {
  const [data, setData] = useState<AiTurnMessages | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");
  const [error, setError] = useState("");
  /*
    Default OFF. The system prompt and the instruction are only ever "new" on
    turn one, so hiding what repeated hid exactly the two texts someone opens
    this view to read. The box stays, for the opposite question (what did THIS
    turn add).
  */
  const [onlyNew, setOnlyNew] = useState(false);
  const [mode, setMode] = useState<Mode>("list");

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
        <span className="db-periods" role="group" aria-label="Görünüm">
          {MODES.map(([id, word]) => (
            <button key={id} type="button"
                    className={"btn btn-ghost db-chip" + (mode === id ? " is-active" : "")}
                    aria-pressed={mode === id} onClick={() => setMode(id)}>
              {word}
            </button>
          ))}
        </span>
        {mode === "list" ? (
          <label className="tk-check">
            <input type="checkbox" checked={onlyNew} onChange={(event) => setOnlyNew(event.target.checked)} />
            yalnız bu turda yeni olanlar
          </label>
        ) : null}
      </div>

      {mode === "one" ? <WholeTurn sessionId={sessionId} seq={seq} /> : (
        <>
          {data.toolDefs ? <MessageItem sessionId={sessionId} message={data.toolDefs} /> : null}
          {shown.length === 0 ? <p className="db-empty">Bu turda yeni mesaj yok — hepsi önceki turlarda gönderilmişti.</p> : null}
          <ol className="tk-message-list">
            {shown.map((m) => <li key={m.index}><MessageItem sessionId={sessionId} message={m} /></li>)}
          </ol>
          {onlyNew && shown.length < data.messages.length ? (
            <p className="db-note">{data.messages.length - shown.length} mesaj önceki turlardan aynen yeniden gönderildi (gizli).</p>
          ) : null}
          <Outgoing sessionId={sessionId} data={data} />
        </>
      )}
    </div>
  );
}

/**
 * The other half of a turn: what the model sent back.
 *
 * The server has always returned these three with the messages; nothing
 * printed them, so the view that exists to show a whole request showed only
 * the half that went out.
 */
function Outgoing({ sessionId, data }: { sessionId: string; data: AiTurnMessages }) {
  if (!data.response && !data.reasoning && data.toolCalls.length === 0) {
    return null;
  }
  return (
    <div className="tk-outgoing">
      <div className="tk-text-label">Bu turdan çıkan</div>
      {data.response ? <MessageItem sessionId={sessionId} message={data.response} /> : null}
      {data.reasoning ? <MessageItem sessionId={sessionId} message={data.reasoning} /> : null}
      {data.toolCalls.length > 0 ? (
        <div className="tk-text">
          <div className="tk-text-label">
            İstediği tool çağrıları <span className="tk-dim">{data.toolCalls.length}</span>
          </div>
          <pre className="tk-pre">
            {data.toolCalls.map((call) => call.name + " " + call.arguments).join("\n")}
          </pre>
        </div>
      ) : null}
    </div>
  );
}

/**
 * The turn as one document: read top to bottom, copy, or save as a file.
 *
 * Fetched whole and uncut on purpose. A late turn is hundreds of kilobytes
 * and that is the thing being asked about, so the <pre> scrolls and İndir is
 * there for anything too long to read in a panel. The file is built from the
 * text already in hand (Blob + createObjectURL) — nothing is contacted.
 */
function WholeTurn({ sessionId, seq }: { sessionId: string; seq: number }) {
  const [text, setText] = useState<string | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "failed">("loading");
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setState("loading");
    void (async () => {
      const result = await getAiTurnTranscript(sessionId, seq);
      if (cancelled) return;
      if (result.ok) {
        setText(result.body);
        setState("idle");
      } else {
        setError(result.error);
        setState("failed");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId, seq]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text ?? "");
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* A browser that refuses the clipboard costs the convenience, nothing else. */
    }
  };

  const save = () => {
    const url = URL.createObjectURL(new Blob([text ?? ""], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "tur-" + seq + ".txt";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  };

  if (state === "failed") {
    return <p className="tk-error">Okunamadı: {error}</p>;
  }
  if (text == null) {
    return <p className="db-empty">Okunuyor…</p>;
  }
  return (
    <div className="tk-text">
      <pre className="tk-pre is-tall">{text}</pre>
      <div className="tk-text-actions">
        <span className="tk-dim">{tokens(text.length)} karakter</span>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => void copy()}>
          {copied ? "Kopyalandı" : "Tamamını kopyala"}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={save}>İndir (.txt)</button>
      </div>
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
      <LazyText sessionId={sessionId} sha={m.sha} preview={m.preview} chars={m.chars} />
    </div>
  );
}
