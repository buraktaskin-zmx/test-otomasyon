import type { Draft } from "./draft";

/*
  app.js:788-806. Three chip kinds because the vocabulary has three shapes
  and they fail differently: a typed value, a ticked checkbox, and an
  EXCLUDED field that must not be named at all (naming a shown_when field
  that is not on this screen costs a 30 s timeout; a box ticked twice
  UNticks it).
*/
export function VariationChips({
  draft,
}: {
  draft: Pick<Draft, "values" | "ticked" | "excluded">;
}) {
  return (
    <>
      {Object.entries(draft.values).map(([label, value]) => (
        <span className="multi-chip" key={label}>
          <span className="multi-chip-key">{label}</span>
          {value}
        </span>
      ))}
      {draft.ticked.map((label) => (
        <span className="multi-chip is-ticked" key={label}>
          ✓ {label}
        </span>
      ))}
      {draft.excluded.map((label) => (
        <span className="multi-chip is-excluded" key={label}>
          {label}
        </span>
      ))}
    </>
  );
}
