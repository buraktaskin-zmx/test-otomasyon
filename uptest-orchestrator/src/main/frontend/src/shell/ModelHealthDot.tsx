import { useLlmHealth } from "../hooks/useLlmHealth";

/*
  New -- nothing in app.js surfaces this. Checked once on load (checking
  more often would cost a real model generation every time), click to
  retry. A dead vLLM endpoint today only shows up as a 502 from /suggest
  AFTER an analyst has filled in a catalog, CIF and intent; this catches it
  before any of that.
*/
export function ModelHealthDot() {
  const health = useLlmHealth();

  const dotClass = health.checking
    ? "dot dot-unknown"
    : health.healthy
      ? "dot dot-up"
      : health.healthy === false
        ? "dot dot-down"
        : "dot dot-unknown";

  const label = health.checking
    ? "model kontrol ediliyor…"
    : health.healthy
      ? "model çalışıyor"
      : health.healthy === false
        ? "model kapalı"
        : "model durumu bilinmiyor";

  return (
    <button
      className="node-pill"
      type="button"
      title={health.detail ?? label}
      onClick={() => void health.recheck()}
    >
      <span className={dotClass} />
      <span>{label}</span>
    </button>
  );
}
