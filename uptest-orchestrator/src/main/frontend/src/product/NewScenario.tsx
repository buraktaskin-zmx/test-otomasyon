import { useState } from "react";
import { DocEditor } from "./ScenarioDetail";
import { HeadingCombobox } from "./HeadingCombobox";
import { writeDoc } from "../lib/scenarioProse";
import type { ScenarioDoc } from "../lib/scenarioProse";
import type { ScenarioHeading } from "../api/types";

/*
  "+" beside a screen: a new scenario, written either as the five
  natural-language fields (the same form as Düzenle) or as Gherkin steps.

  Either way the result is one ordinary .feature file -- the same layout the
  detail view reads -- so a scenario started as prose can get its Gherkin
  later, and the other way round.
*/

const EMPTY: ScenarioDoc = { title: "", rules: [], preconditions: [], steps: [], expected: [] };

/** `Feature:` named after the screen, one `Scenario:` -- what both modes start from. */
function skeleton(screen: string, title: string): string {
  return "Feature: " + screen + "\n\n  Scenario: " + title.trim() + "\n";
}

export function NewScenario({
  screen,
  headings,
  onSave,
  onCancel,
}: {
  /** The screen's display name, used for the Feature line. */
  screen: string;
  /** The screen's own headings, offered in "Başlık". */
  headings: ScenarioHeading[];
  /** Resolves to true when the file was created. `heading` is a name, blank
   *  for başlıksız; a new name is made into a heading by the server. */
  onSave: (feature: string, heading: string) => Promise<boolean>;
  onCancel: () => void;
}) {
  const [mode, setMode] = useState<"prose" | "gherkin">("prose");
  const [doc, setDoc] = useState<ScenarioDoc>(EMPTY);
  const [name, setName] = useState("");
  const [steps, setSteps] = useState("");
  const [heading, setHeading] = useState("");
  const [saving, setSaving] = useState(false);

  const title = mode === "prose" ? doc.title : name;

  const build = (): string => {
    if (mode === "prose") return writeDoc(skeleton(screen, doc.title), doc);
    const body = steps
      .split("\n")
      .map((line) => (line.trim() ? "    " + line.trim() : ""))
      .join("\n")
      .trim();
    return skeleton(screen, name) + (body ? "\n    " + body + "\n" : "");
  };

  const save = async () => {
    setSaving(true);
    await onSave(build(), heading.trim());
    setSaving(false);
  };

  return (
    <div className="sd new-scenario">
      <div className="new-scenario-head">
        <span className="sd-doc-label">Yeni senaryo</span>
        <div className="run-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={mode === "prose"}
                  className={"btn " + (mode === "prose" ? "btn-primary" : "btn-ghost")}
                  onClick={() => setMode("prose")}>
            Doğal dil
          </button>
          <button type="button" role="tab" aria-selected={mode === "gherkin"}
                  className={"btn " + (mode === "gherkin" ? "btn-primary" : "btn-ghost")}
                  onClick={() => setMode("gherkin")}>
            Gherkin
          </button>
        </div>
      </div>

      {mode === "prose" ? (
        <DocEditor
          doc={doc}
          onChange={setDoc}
          afterTitle={<HeadingCombobox value={heading} headings={headings} onChange={setHeading} />}
        />
      ) : (
        <div className="sd-doc">
          <input
            className="search"
            value={name}
            placeholder="Senaryo adı"
            onChange={(event) => setName(event.target.value)}
          />
          <HeadingCombobox value={heading} headings={headings} onChange={setHeading} />
          <textarea
            className="search sd-edit"
            spellCheck={false}
            value={steps}
            placeholder={'Given navigate to "ÖDEMELER" "Alıcı Listesi İşlemleri"\n'
              + 'When click "Yeni Liste Oluştur"\n'
              + 'Then verify "İşlem Bilgileri" text is displayed'}
            onChange={(event) => setSteps(event.target.value)}
          />
        </div>
      )}

      <div className="new-scenario-actions">
        <button type="button" className="btn btn-primary"
                disabled={saving || !title.trim()} onClick={() => void save()}>
          {saving ? "Kaydediliyor…" : "Kaydet"}
        </button>
        <button type="button" className="btn btn-ghost" disabled={saving} onClick={onCancel}>
          Vazgeç
        </button>
      </div>
    </div>
  );
}
