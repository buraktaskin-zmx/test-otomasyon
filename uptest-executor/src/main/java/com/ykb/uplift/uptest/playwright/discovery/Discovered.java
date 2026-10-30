package com.ykb.uplift.uptest.playwright.discovery;

import java.util.List;

/**
 * What a scan records about a field, and what filling one did to the others.
 *
 * Split out of {@link CatalogDiscovery} unchanged.
 */
final class Discovered {

    private Discovered() {
    }

    enum Type {
        TEXT("text"),
        AMOUNT("amount"),
        DATE("date"),
        DROPDOWN("dropdown"),
        ACCOUNT_PICKER("account_picker"),
        CHECKBOX("checkbox"),
        UNSUPPORTED_V3("UNSUPPORTED_V3"),
        SKIP("");

        final String schemaName;

        Type(String schemaName) {
            this.schemaName = schemaName;
        }
    }

    /**
     * What the box itself says it will accept, read straight off its attributes.
     *
     * This exists for the generator rather than for the run: VariationPlanner
     * has to invent a DIFFERENT value for each of N scenarios, and the only
     * honest way to pick a boundary value is to know where the boundary is. A
     * 16-character invoice number typed into a 3-character box is silently
     * truncated by the browser, so the form then validates a value nobody chose.
     *
     * A null component means the DOM did not say — never "unbounded". The
     * difference is the whole point: the planner falls back to a conservative
     * ladder AND reports that it had to, instead of guessing confidently.
     *
     * Known blind spot: a jQuery-masked field enforces its shape in JavaScript,
     * not in attributes, so a mask that permits only digits is invisible here
     * unless the markup also says so. Read off attributes only, deliberately —
     * guessing "numeric" from a label's wording is how "Fatura No" becomes a
     * number field on a screen that accepts "FTR-2026-0001".
     */
    record Limits(Integer maxLength, Integer min, Integer max, boolean numeric) {

        static final Limits NONE = new Limits(null, null, null, false);

        boolean known() {
            return maxLength != null || min != null || max != null || numeric;
        }
    }

    static final class Field {
        final String label;
        final Type type;
        /**
         * Not final: a dependent dropdown is empty when the scan reads it and
         * only fills in once its parent is chosen, so the list is replaced the
         * moment the screen is seen to load one.
         */
        List<String> choices;
        final Limits limits;
        final int firstSeenScreen;
        boolean verifiable;
        /** The application owns this value: a scenario verifies it, never types it. */
        boolean readonly;

        Field(String label, Type type, List<String> choices, Limits limits, int firstSeenScreen) {
            this.label = label;
            this.type = type;
            this.choices = choices;
            this.limits = limits;
            this.firstSeenScreen = firstSeenScreen;
        }
    }

    /**
     * What one field looked like at one moment. Compared before and after a
     * fill to see what that fill set off elsewhere on the screen.
     */
    record FieldState(boolean enabled, String value, int optionCount) {
    }

    /** What filling one field did to another. */
    record Effect(String label, String what) {
    }

    /** One log line per cause, for the run output. */
    static String describe(String cause, List<Effect> caused) {
        return "\"" + cause + "\" secilince: " + caused.stream()
                .map(e -> "\"" + e.label() + "\" " + e.what())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
