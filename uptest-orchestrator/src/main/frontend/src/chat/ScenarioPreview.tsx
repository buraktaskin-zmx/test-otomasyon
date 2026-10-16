import { Badge } from "../ui/Badge";
import type { ValidationResult } from "../api/types";

/*
  The draft as it stands, with the two things to do with it.

  Shown on every turn, not only the last one: the scenario changing under the
  conversation is the feedback that makes the conversation worth having, and
  an analyst who can see the step appear when they answer a question knows
  their answer landed.

  Read-only here on purpose. Editing belongs in the editor, which has the
  gutter, the validation panel, the jump-to-line and everything else; a second
  half-featured editing surface would be a worse copy of one that already
  exists three metres to the left.
*/
export function ScenarioPreview({
  feature,
  validation,
  complete,
  busy,
  onRun,
  onHandOff,
}: {
  feature: string;
  validation: ValidationResult | null;
  complete: boolean;
  busy: boolean;
  onRun: () => void;
  onHandOff: () => void;
}) {
  return (
    <div className="chat-draft">
      <div className="pane-head">
        <h2 className="pane-title">Taslak</h2>
        <div className="pane-actions">
          {validation ? (
            <Badge variant={validation.valid ? "ok" : "fail"}>
              {validation.valid
                ? validation.stepCount + " adım geçerli"
                : validation.errors.length + " hata"}
            </Badge>
          ) : null}
          <button className="btn btn-ghost" type="button" onClick={onHandOff}>
            Editöre aktar
          </button>
          {/* Running an invalid draft is refused server-side anyway; the
              button says so first rather than spending a round trip to be
              told. */}
          <button
            className="btn btn-primary"
            type="button"
            disabled={busy || !complete}
            title={complete ? "" : "Önce sohbetteki soruları yanıtlayın"}
            onClick={onRun}
          >
            Çalıştır
          </button>
        </div>
      </div>
      <pre className="chat-feature">{feature}</pre>
    </div>
  );
}
