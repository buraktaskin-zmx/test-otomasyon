import { useCallback, useEffect, useState } from "react";
import { llmHello } from "../api/endpoints";

interface HealthState {
  checking: boolean;
  /** null = not checked yet. */
  healthy: boolean | null;
  detail: string | null;
}

/*
  GET /api/v1/llm/hello, checked ONCE on load -- not on an interval like the
  executor pill's 15 s poll, because this costs a real model generation
  every time. A dead vLLM endpoint otherwise only surfaces as a 502 from
  /suggest AFTER an analyst has filled in a catalog, a CIF and an intent;
  this is the one-click "is the model up?" probe that catches it earlier.
*/
export function useLlmHealth() {
  const [state, setState] = useState<HealthState>({ checking: false, healthy: null, detail: null });

  const check = useCallback(async () => {
    setState((prev) => ({ ...prev, checking: true }));
    const result = await llmHello();
    if (!result.ok) {
      setState({ checking: false, healthy: false, detail: result.error });
      return;
    }
    // Always 200; success vs failure is told apart by which keys are
    // present, not by status -- `answer` only exists on success.
    if (result.body.answer !== undefined) {
      setState({ checking: false, healthy: true, detail: result.body.answer });
    } else {
      setState({ checking: false, healthy: false, detail: result.body.error ?? "bilinmeyen hata" });
    }
  }, []);

  useEffect(() => {
    void check();
  }, [check]);

  return { ...state, recheck: check };
}
