package orchestrator.product;

import java.util.List;

/**
 * One registered scenario, as the Ürünler screen lists it.
 *
 * <p><b>Derived from the .feature file, never from a registry.</b> Title,
 * description, step count, the rules it covers and the data it needs all come
 * out of the file itself — Gherkin already has somewhere to put each of them,
 * and a second list naming the same scenarios would start by agreeing and end
 * by disagreeing. The same reasoning that keeps the step vocabulary generated
 * from the executor's annotations rather than hand-copied.
 *
 * <p>Two tag namespaces, and they do not overlap:
 *
 * <pre>
 *   &#64;R-AL-011         what this scenario VERIFIES   -> rules.yaml
 *   &#64;needs:FIRMA_LISTELI  what it CONSUMES to run   -> test-data.yaml
 *   &#64;needs:none       nothing: deliberately declared, not forgotten
 * </pre>
 *
 * Tags cost nothing elsewhere: Cucumber ignores the ones it has no hook for,
 * and {@code ScenarioValidator} only ever looks at steps, so a tagged file
 * validates and runs exactly as an untagged one does.
 *
 * @param path      relative to the product's scenarios directory, e.g.
 *                  {@code alici-listesi-islemleri/01-senaryo.feature}. The
 *                  identity: titles get rewritten, paths are renamed on purpose
 * @param flow      the screen — the first path segment, which is why scenarios
 *                  sit in a folder per screen
 * @param needsNone the file carries {@code @needs:none}. Kept separate from
 *                  "the needs list is empty" because those are different
 *                  facts: one says somebody decided this scenario needs
 *                  nothing, the other says nobody wrote anything down. The
 *                  column must not show them the same way
 */
public record ScenarioCard(String path, String flow, String title, String description,
                           int stepCount, List<String> ruleIds, List<String> needs,
                           boolean needsNone, List<String> otherTags, String problem) {

    /**
     * {@code problem} is non-null when the file could not be read as Gherkin.
     * Listed anyway rather than skipped: a scenario that silently vanishes
     * from the registry is worse than one shown as broken, because nobody
     * goes looking for a row that was never there.
     */
    public boolean broken() {
        return problem != null && !problem.isBlank();
    }

    /** Nobody said anything about preconditions — neither an alias nor "none". */
    public boolean needsUndeclared() {
        return !needsNone && needs.isEmpty();
    }
}
