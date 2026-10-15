import { useCallback, useEffect, useRef, useState } from "react";
import { validateScenario } from "../api/endpoints";
import type { Origin, ValidationError, ValidationResult } from "../api/types";

/* 700 ms idle, same as app.js's VALIDATE_IDLE_MS (app.js:16). */
const VALIDATE_IDLE_MS = 700;

export type ValidationState =
  | { kind: "idle" }
  | { kind: "typing" }
  | { kind: "empty" }
  | { kind: "valid"; stepCount: number }
  | { kind: "invalid"; errorCount: number }
  | { kind: "no-executor" };

/*
  Mirrors app.js's validate()/scheduleValidate()/renderValidation()
  (app.js:229-314), minus the DOM writes.

  `feature` and `origin` are read through refs rather than taken as plain
  closures, so `validateNow` keeps one stable identity across keystrokes --
  the same shape app.js had with its module-level `el`/`state` globals, just
  without actually using globals. Only `scheduleValidate` (called from the
  editor's onChange) restarts the debounce timer; `validateNow` is also
  exposed directly for the Doğrula button and for the "revalidate right
  after a suggestion/fix" call app.js makes inline.

  /scenarios/validate is always 200 on a normal pass/fail -- 422 does not
  exist on this endpoint, only on POST /runs. A 503 means no executor is
  reachable and must read "executor yok", not a generic failure toast.
*/
export function useValidation(
  feature: string,
  origin: Origin,
  /** Fired with every ValidationResult this hook produces or is handed, on
   *  a successful outcome only -- the hook point Çoklu Üret's
   *  renderValidation write-back uses (app.js:276-282) to keep the active
   *  draft's own badge in sync as the analyst fixes and re-validates it. */
  onResult?: (result: ValidationResult) => void,
) {
  const [state, setState] = useState<ValidationState>({ kind: "idle" });
  const [errors, setErrors] = useState<ValidationError[]>([]);
  const debounceRef = useRef<number | null>(null);
  const featureRef = useRef(feature);
  const originRef = useRef(origin);
  const onResultRef = useRef(onResult);
  featureRef.current = feature;
  originRef.current = origin;
  onResultRef.current = onResult;

  const validateNow = useCallback(async (): Promise<boolean> => {
    const currentFeature = featureRef.current;
    if (!currentFeature.trim()) {
      setErrors([]);
      setState({ kind: "empty" });
      return false;
    }

    const result = await validateScenario(currentFeature, originRef.current);
    if (!result.ok) {
      if (result.status === 503) {
        setErrors([]);
        setState({ kind: "no-executor" });
      }
      // Any other failure (network, 400, 500): leave the previous badge in
      // place and let the caller toast the error -- there is nothing new to
      // render here, unlike a 503, which has its own dedicated badge state.
      return false;
    }

    setErrors(result.body.errors);
    setState(
      result.body.valid
        ? { kind: "valid", stepCount: result.body.stepCount }
        : { kind: "invalid", errorCount: result.body.errors.length },
    );
    onResultRef.current?.(result.body);
    return result.body.valid;
  }, []);

  const scheduleValidate = useCallback(() => {
    if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    setState({ kind: "typing" });
    debounceRef.current = window.setTimeout(() => {
      void validateNow();
    }, VALIDATE_IDLE_MS);
  }, [validateNow]);

  useEffect(
    () => () => {
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    },
    [],
  );

  /** Applies a ValidationResult that arrived from somewhere OTHER than this
   *  hook's own validateNow() -- specifically POST /runs's 422 body, which
   *  is the bare ValidationResult itself (app.js:361-364: a run refused for
   *  undefined steps re-renders the same error panel validate() would have,
   *  without a second round trip). */
  const applyResult = useCallback((result: ValidationResult) => {
    setErrors(result.errors);
    setState(
      result.valid ? { kind: "valid", stepCount: result.stepCount } : { kind: "invalid", errorCount: result.errors.length },
    );
    onResultRef.current?.(result);
  }, []);

  return { state, errors, setErrors, validateNow, scheduleValidate, applyResult };
}
