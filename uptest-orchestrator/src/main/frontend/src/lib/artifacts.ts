/* app.js:446-456. Label map for the three well-known artifact names; a
   traces/ prefix gets its own label; anything else falls back to the raw
   name. contentType is on the wire now (Artifact.contentType) but the
   label logic still keys off the NAME, same as app.js -- the content type
   only ever distinguishes .zip (download) from everything else (open). */

const ARTIFACT_LABELS: Record<string, string> = {
  "report.html": "Raporu Aç",
  "run.log": "Günlüğü Aç",
  "scenario.feature": "Senaryoyu Aç",
  "catalog-draft.yaml": "Taslak Kataloğu Aç",
};

export function artifactLabel(name: string): string {
  if (ARTIFACT_LABELS[name]) return ARTIFACT_LABELS[name];
  if (/^traces\//.test(name)) return "Trace İndir";
  return name;
}

/** .zip must stay a plain download link (no target="_blank"), because the
 *  executor sets Content-Disposition: attachment only for .zip -- opening
 *  it in a new tab would just show an empty tab while the browser downloads
 *  in the background. Every other artifact opens in a new tab: report.html
 *  specifically MUST, since it is served with
 *  Content-Security-Policy: sandbox allow-scripts and cannot be fetched or
 *  rendered inline from this origin. */
export function isDownloadOnly(name: string): boolean {
  return /\.zip$/i.test(name);
}
