/* Lifted from app.js unchanged: the analyst reads these every day and a port
   is not the moment to renegotiate how a duration looks. */

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "\u2014";
  if (ms < 1000) return ms + " ms";
  if (ms < 60000) return (ms / 1000).toFixed(1) + " sn";
  return Math.floor(ms / 60000) + " dk " + Math.round((ms % 60000) / 1000) + " sn";
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "\u2014";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "\u2014";
  return d.toLocaleString("tr-TR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) return "";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + " KB";
  return (bytes / 1048576).toFixed(1) + " MB";
}

/** Run ids are uuids; the grid shows the first 8 characters, as it always has. */
export const shortRunId = (runId: string | null | undefined): string =>
  String(runId ?? "").slice(0, 8);
