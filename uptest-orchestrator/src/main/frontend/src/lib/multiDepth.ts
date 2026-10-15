export type Depth = "confirm" | "submit";

/*
  app.js:653-675, verbatim Turkish. These sentences are the whole reason
  the free-text intent box stopped being mandatory for a batch: every other
  decision a scenario needs is already made elsewhere (the catalog names
  the fields and navigation, the planner picks every value, LoginStep
  writes the login line) -- the one thing that WAS only reaching the model
  as prose, or not at all, was whether to press the final confirm button on
  a screen where doing so creates a real, irreversible record. Now it is a
  radio button and the wording is identical on every run; the optional note
  ADDS to this, it never replaces it.
*/
export const DEPTH_INSTRUCTION: Record<Depth, string> = {
  confirm:
    "Formu doldur ve Devam'a bas. Onay düğmesine BASMA — bu senaryo kayıt oluşturmamalı. " +
    "Devam'dan sonra gelen özet ekranında girdiğin değerleri doğrula.",
  submit:
    "Formu doldur, Devam'a bas, özet ekranındaki değerleri doğrula, sonra Onay'a basarak işlemi tamamla.",
};

export function buildMultiIntent(depth: Depth, note: string): string {
  const trimmed = note.trim();
  return DEPTH_INSTRUCTION[depth] + (trimmed ? "\n" + trimmed : "");
}

/** First non-blank lines of a scenario -- app.js:816-820, what tells two
 *  draft cards apart faster than any summary of them would. */
export function previewOf(feature: string): string {
  return feature
    .split("\n")
    .filter((line) => line.trim() !== "")
    .slice(0, 4)
    .join("\n");
}
