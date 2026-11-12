import { useState } from "react";
import { getAiContent } from "../api/endpoints";
import type { AiBreakdown, AiSessionKind, AiSessionStatus, AiToolOutcome } from "../api/types";

/*
  Token Takibi's small pieces. What every number means: docs/token-takibi/README.md.

  COLOURS FOLLOW THE CATEGORY, NEVER ITS RANK. The five prompt sources keep the
  same hue on every chart, in the order the dataviz validator passed
  (adjacent-pair CVD ΔE ≥ 8.4, normal-vision ≥ 19.3, both modes); the
  remainder ("şablon") is grey because it is not a source, it is what is left.
  Three of the light-mode hues sit under 3:1 on white, so every chart that
  uses them prints each category's number in words beside it -- the colour is
  never the only way to read a part.
*/

export const KIND_WORD: Record<AiSessionKind, string> = {
  "ai-run": "AI ile çalıştır",
  explore: "Ekran Keşfi",
  catalog: "Katalog üret",
  gherkin: "Gherkin oluştur",
  "flow-read": "Ekran okuma",
  "flow-plan": "Planlama",
  "flow-suggest": "Senaryo üretimi",
  health: "Sağlık kontrolü",
  other: "Diğer",
};

/** The filter chips: what an analyst asks for, mapped to the kinds that answer it. */
export const KIND_FILTERS: ReadonlyArray<{ id: string; word: string }> = [
  { id: "", word: "Tümü" },
  { id: "ai-run", word: "AI ile çalıştır" },
  { id: "gherkin", word: "Gherkin" },
  { id: "explore,catalog", word: "Ekran Keşfi" },
  { id: "flow-read,flow-plan,flow-suggest", word: "Senaryo Üret" },
  { id: "other", word: "Diğer" },
  { id: "health", word: "Sağlık" },
];

export type Category = "toolResults" | "system" | "toolDefs" | "user" | "assistant" | "overhead";

/** Stack order, bottom to top -- also the validated hue order. */
export const CATEGORIES: ReadonlyArray<{ key: Category; word: string; hint: string }> = [
  { key: "toolResults", word: "Tool sonuçları", hint: "Araçların modele geri verdiği metin: sayfa snapshot'ları, tıklama sonuçları." },
  { key: "system", word: "System prompt", hint: "Kurallar: her turda aynen yeniden gönderilir." },
  { key: "toolDefs", word: "Tool tanımları", hint: "Araçların adı, açıklaması ve argüman şeması; her turda gider." },
  { key: "user", word: "Kullanıcı mesajı", hint: "Talimat: giriş bilgileri, senaryonun kendisi." },
  { key: "assistant", word: "Model geçmişi", hint: "Modelin önceki turlardaki cevapları ve tool çağrıları." },
  { key: "overhead", word: "Şablon / ek", hint: "Sunucunun bildirdiği girdi eksi parçalar: sohbet şablonunun kendi işaretleri." },
];

export function partOf(split: AiBreakdown | null, key: Category): number {
  if (!split) return 0;
  const value = split[key];
  return value == null ? 0 : Math.max(0, value);
}

/** 12.345 — tr-TR thousands, the way the analysts read numbers. */
export function tokens(n: number | null | undefined): string {
  return n == null ? "—" : n.toLocaleString("tr-TR");
}

/** 12,3 bin · 1,2 mn — for axes and tight cells. */
export function compact(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return (n / 1000).toLocaleString("tr-TR", { maximumFractionDigits: n < 10_000 ? 1 : 0 }) + " bin";
  return (n / 1_000_000).toLocaleString("tr-TR", { maximumFractionDigits: 1 }) + " mn";
}

export function percent(part: number, whole: number): string {
  if (!whole) return "—";
  const p = (100 * part) / whole;
  return (p < 1 && p > 0 ? "<1" : Math.round(p).toString()) + "%";
}

/** A round top for a y-axis, so it reads 40 bin rather than 37.812. */
export function niceMax(peak: number): number {
  if (peak <= 0) return 1;
  const magnitude = Math.pow(10, Math.floor(Math.log10(peak)));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * magnitude >= peak) return step * magnitude;
  }
  return 10 * magnitude;
}

export function SessionStatus({ status }: { status: AiSessionStatus }) {
  const map: Record<AiSessionStatus, { word: string; pill: string }> = {
    RUNNING: { word: "çalışıyor", pill: "RUNNING" },
    DONE: { word: "bitti", pill: "DONE" },
    FAILED: { word: "başarısız", pill: "FAILED" },
    UNKNOWN: { word: "bilinmiyor", pill: "UNKNOWN" },
  };
  const entry = map[status] ?? map.UNKNOWN;
  return <span className={"pill pill-" + entry.pill}>{entry.word}</span>;
}

export function OutcomeWord({ outcome }: { outcome: AiToolOutcome }) {
  const map: Record<AiToolOutcome, { word: string; pill: string; title: string }> = {
    OK: { word: "tamam", pill: "DONE", title: "Araç çalıştı, sonucu modele gitti" },
    REFUSED: { word: "reddedildi", pill: "CANCELED", title: "Bir koruma araç yerine cevap verdi; tarayıcı bu çağrıyı görmedi" },
    ERROR: { word: "hata", pill: "FAILED", title: "Araç hata verdi ya da tarayıcı kapanmıştı" },
  };
  const entry = map[outcome] ?? map.ERROR;
  return <span className={"pill pill-" + entry.pill} title={entry.title}>{entry.word}</span>;
}

/** "≈": a token count estimated from characters, not counted by the model's tokenizer. */
export function Estimate({ on }: { on: boolean }) {
  if (!on) return null;
  return (
    <span className="db-approx" title="Tahmin: modelin tokenizer'ına ulaşılamadı; bu oturumun kendi karakter/token oranından hesaplandı">
      ≈
    </span>
  );
}

/** One horizontal bar of the prompt's sources, with each part in words beneath it. */
export function BreakdownBar({ split, prompt }: { split: AiBreakdown; prompt: number | null }) {
  const parts = CATEGORIES.map((c) => [c, partOf(split, c.key)] as const).filter(([, n]) => n > 0);
  const whole = prompt ?? parts.reduce((sum, [, n]) => sum + n, 0);
  return (
    <>
      <div className="db-bar tk-bar" role="img"
           aria-label={parts.map(([c, n]) => c.word + " " + tokens(n)).join(", ")}>
        {parts.map(([c, n]) => (
          <span key={c.key} className={"db-seg tk-" + c.key} style={{ flexGrow: n }}
                title={c.word + ": " + tokens(n) + " token (" + percent(n, whole) + ")"} />
        ))}
      </div>
      <div className="db-bar-legend tk-legend-row">
        {parts.map(([c, n]) => (
          <span key={c.key} className={"tk-key tk-" + c.key}>
            {c.word} <b>{tokens(n)}</b> <span className="tk-dim">{percent(n, whole)}</span>
          </span>
        ))}
        <Estimate on={split.exact === false} />
      </div>
    </>
  );
}

/**
 * A stored text: its preview at once, the whole of it on request.
 *
 * Fetched lazily because a page snapshot is tens of kilobytes and a walk has
 * dozens of them; the panel would otherwise download the whole session to
 * show a timeline.
 */
export function LazyText({ sessionId, sha, preview, chars, label }: {
  sessionId: string;
  sha: string | null;
  preview?: string | null;
  chars: number;
  label?: string;
}) {
  const [full, setFull] = useState<string | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "failed">("idle");
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  const shownPreview = preview ?? "";
  const truncated = chars > shownPreview.length;

  const load = async () => {
    if (!sha) return;
    setState("loading");
    const result = await getAiContent(sessionId, sha);
    if (result.ok) {
      setFull(result.body);
      setState("idle");
    } else {
      setError(result.error);
      setState("failed");
    }
  };

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(full ?? shownPreview);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      /* A browser that refuses the clipboard costs the convenience, nothing else. */
    }
  };

  const text = full ?? shownPreview;
  return (
    <div className="tk-text">
      {label ? <div className="tk-text-label">{label} <span className="tk-dim">{tokens(chars)} karakter</span></div> : null}
      {text ? <pre className="tk-pre">{text}{full == null && truncated ? "…" : ""}</pre> : null}
      <div className="tk-text-actions">
        {full == null && sha && (truncated || !text) ? (
          <button type="button" className="btn btn-ghost btn-sm" disabled={state === "loading"} onClick={() => void load()}>
            {state === "loading" ? "Okunuyor…" : text ? "Tamamını göster" : "Göster"}
          </button>
        ) : null}
        {full != null && full.length > 4000 ? (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setFull(null)}>Kısalt</button>
        ) : null}
        {text ? (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void copy()}>
            {copied ? "Kopyalandı" : "Kopyala"}
          </button>
        ) : null}
        {state === "failed" ? <span className="tk-error">Okunamadı: {error}</span> : null}
      </div>
    </div>
  );
}

/** +12,4 sn — a moment inside a session, from its start. */
export function offset(fromIso: string, atIso: string): string {
  const ms = new Date(atIso).getTime() - new Date(fromIso).getTime();
  if (isNaN(ms) || ms < 0) return "";
  if (ms < 60000) return "+" + (ms / 1000).toLocaleString("tr-TR", { maximumFractionDigits: 1 }) + " sn";
  return "+" + Math.floor(ms / 60000) + " dk " + Math.round((ms % 60000) / 1000) + " sn";
}
