import { StepPattern } from "../../ui/StepPattern";

/** "Bunu mu demek istediniz" chip (app.js:304-310, .sug class). */
export function SuggestionChip({
  line,
  fix,
  onApply,
}: {
  line: number;
  fix: string;
  onApply: (line: number, fix: string) => void;
}) {
  return (
    <button className="sug" type="button" title="Senaryoya ekle" onClick={() => onApply(line, fix)}>
      <StepPattern pattern={fix} />
    </button>
  );
}
