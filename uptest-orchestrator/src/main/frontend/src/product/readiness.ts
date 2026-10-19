import type { Readiness } from "../api/types";

export interface ReadinessLabel {
  /** CSS suffix: .is-ready / .is-seed / .is-missing / .is-blocked / .is-unknown */
  kind: "ready" | "seed" | "missing" | "blocked" | "unknown";
  word: string;
  /** Why the row is in this state, in one line, for the title attribute. */
  hint: string;
}

/**
 * Five states, because five different people have to do five different things.
 *
 * The pairs that are tempting to merge are exactly the ones worth keeping
 * apart:
 *
 * - seed vs eksik — "the test creates its own data" is RUNNABLE. Folding it
 *   into eksik would hide the half of a suite that works fine and merely has
 *   a precondition.
 * - eksik vs engelli — eksik is a request to whoever provisions test users;
 *   engelli is a request to an analyst. Until the question is answered the
 *   scenario cannot even be written correctly, because what it should assert
 *   is unknown. One is procurement, the other is analysis.
 * - belirtilmemiş vs ön koşulsuz — this is the one that costs most if merged.
 *   "Nobody wrote the preconditions down" and "somebody checked and there are
 *   none" look identical on disk; only the second is a finished scenario, and
 *   showing the first as green queues a run that cannot survive.
 */
export function readinessLabel(readiness: Readiness, needsNone: boolean): ReadinessLabel {
  switch (readiness) {
    case "READY":
      return needsNone
        ? { kind: "ready", word: "ön koşulsuz", hint: "@needs:none — hiçbir veri tüketmiyor" }
        : { kind: "ready", word: "hazır", hint: "Gereken verinin tamamı elde" };
    case "SEED":
      return {
        kind: "seed",
        word: "seed",
        hint: "Veri yok ama test onu kendisi yaratır; koşabilir",
      };
    case "MISSING":
      return {
        kind: "missing",
        word: "eksik",
        hint: "Gereken veri henüz temin edilmedi",
      };
    case "BLOCKED":
      return {
        kind: "blocked",
        word: "engelli",
        hint: "Açık bir soru cevaplanmadan bu senaryo yazılamaz",
      };
    default:
      return {
        kind: "unknown",
        word: "belirtilmemiş",
        hint: "Dosyada @needs etiketi yok — ön koşul bilinmiyor",
      };
  }
}
