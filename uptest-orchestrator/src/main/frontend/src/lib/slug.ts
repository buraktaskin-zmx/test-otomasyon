/*
  Turkish-aware ASCII fold, app.js:721-729, byte-for-byte. This becomes a
  filename, and CatalogService.SAFE_NAME only accepts [A-Za-z0-9._-]{1,64}
  -- "Fatura Oluştur" would otherwise be refused for a reason nobody could
  see on screen.
*/
const FROM = "çğıöşüÇĞİÖŞÜ";
const TO = "cgiosucgiosu";

export function slug(text: string): string {
  let out = text.trim().toLowerCase();
  for (let i = 0; i < FROM.length; i++) {
    out = out.split(FROM[i] as string).join(TO[i] as string);
  }
  return out
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 64);
}

/** Pulls `^app:` / `^flow:` out of a generated catalog YAML to prefill the
 *  save boxes -- app.js:707-716. Only ever used to fill EMPTY boxes; the
 *  caller decides that, this just extracts. */
export function catalogNameFromYaml(yaml: string): { app?: string; flow?: string } {
  const appMatch = /^app:\s*(\S+)/m.exec(yaml);
  const flowMatch = /^flow:\s*(.+)$/m.exec(yaml);
  return {
    app: appMatch?.[1]?.toLowerCase(),
    flow: flowMatch ? slug(flowMatch[1] ?? "") : undefined,
  };
}
