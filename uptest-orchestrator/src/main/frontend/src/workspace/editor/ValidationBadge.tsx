import { Badge } from "../../ui/Badge";
import type { ValidationState } from "../../hooks/useValidation";

/*
  Six text states, matching app.js's renderValidation/validate exactly
  (app.js:229-271) -- including which of the three CSS variants each one
  uses. "yazılıyor…" and "boş" both render as badge-idle, not a dedicated
  "busy" look; that is app.js's own choice, not an omission here.
*/
export function ValidationBadge({ state }: { state: ValidationState }) {
  switch (state.kind) {
    case "idle":
      return <Badge variant="idle">denetlenmedi</Badge>;
    case "typing":
      return <Badge variant="idle">yazılıyor…</Badge>;
    case "empty":
      return <Badge variant="idle">boş</Badge>;
    case "valid":
      return <Badge variant="ok">{state.stepCount} adım geçerli</Badge>;
    case "invalid":
      return <Badge variant="fail">{state.errorCount} hata</Badge>;
    case "no-executor":
      return <Badge variant="fail">executor yok</Badge>;
  }
}
