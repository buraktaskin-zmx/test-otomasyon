import type { VocabularyStep } from "../api/types";

/*
  Presentation grouping only -- app.js:35-42. The vocabulary itself always
  comes from GET /api/v1/vocabulary; nothing here is a second source of
  truth for what steps exist, only for how they are bucketed on screen.
*/
export interface VocabGroup {
  title: string;
  test: (pattern: string) => boolean;
}

export const VOCAB_GROUPS: readonly VocabGroup[] = [
  { title: "Giriş", test: (p) => /^login/.test(p) },
  { title: "Navigasyon", test: (p) => /^(go to|navigate to|click)/.test(p) },
  { title: "Veri Girişi", test: (p) => /^enter/.test(p) },
  { title: "Seçim", test: (p) => /^select/.test(p) },
  { title: "Doğrulama", test: (p) => /^verify/.test(p) },
  { title: "Diğer", test: () => true },
];

/** Buckets `steps` into VOCAB_GROUPS order, each step appearing in exactly
 *  one group (the first whose test matches) -- app.js:200-213. */
export function groupVocabulary(
  steps: readonly VocabularyStep[],
): ReadonlyArray<{ group: VocabGroup; steps: VocabularyStep[] }> {
  const used = new Set<string>();
  const result: Array<{ group: VocabGroup; steps: VocabularyStep[] }> = [];

  for (const group of VOCAB_GROUPS) {
    const inGroup = steps.filter((step) => !used.has(step.pattern) && group.test(step.pattern));
    inGroup.forEach((step) => used.add(step.pattern));
    if (inGroup.length > 0) result.push({ group, steps: inGroup });
  }

  return result;
}

export function filterVocabulary(
  steps: readonly VocabularyStep[],
  needle: string,
): VocabularyStep[] {
  const lower = needle.trim().toLowerCase();
  if (!lower) return [...steps];
  return steps.filter((step) => step.pattern.toLowerCase().includes(lower));
}
