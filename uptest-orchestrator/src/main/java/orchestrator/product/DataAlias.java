package orchestrator.product;

import java.util.Locale;

/**
 * One entry of a product's {@code test-data.yaml}: a name a scenario can ask
 * for instead of a real value.
 *
 * <p>Aliases exist because real customer numbers, IBANs and employee names do
 * not go in this repository — {@code DO_NOT_COMMIT/} is that rule made
 * concrete. A scenario says {@code @needs:GECERLI_TCKN}; where the value
 * actually comes from is {@link #how}'s business.
 *
 * <p><b>A need is not the same thing as the rule being tested</b>, and the two
 * are easy to confuse because they name the same field. "Geçersiz TCKN
 * reddedilir" TESTS the TCKN check and needs nothing — an invalid number is
 * free to type. "Geçerli TCKN kabul edilir" needs a real one. Testing broken
 * data does not require sound data, which is why a blocker on
 * {@code GECERLI_TCKN} stops far fewer scenarios than it first appears to.
 *
 * @param label     a short human phrase for the precondition column, e.g.
 *                  "Geçerli TCKN". The id is for machines; a reader scanning
 *                  fifty rows wants the need, not the key
 * @param how       how the value is obtained: {@code sabit} (written in the
 *                  file), {@code uretim} (computed at run time), {@code seed}
 *                  (created by the test itself), {@code gizli} (read from
 *                  DO_NOT_COMMIT), {@code eksik} (nobody has it yet)
 * @param blockedBy an open question's id, e.g. {@code S-18}
 */
public record DataAlias(String id, String group, String label, String description,
                        String how, String value, String blockedBy, String note) {

    /**
     * Four states, because four different people have to do four different
     * things about them.
     *
     * <p>Collapsing {@link #SEED} into {@link #MISSING} would hide the half of
     * a suite that is perfectly runnable and merely has a precondition.
     * Collapsing {@link #BLOCKED} into {@code MISSING} loses the sharper
     * distinction: "eksik" is work nobody has done (get a test user), while
     * "engelli" is a QUESTION nobody has answered — and until it is answered
     * the scenario cannot even be written correctly, because what it should
     * assert is unknown. The first is a request to an ops team; the second is
     * a request to an analyst.
     */
    public enum Readiness {
        /** The value exists or can be produced. */
        READY,
        /** The test must create it first — a precondition, not a blocker. */
        SEED,
        /** Nobody has it yet. Somebody has to supply it. */
        MISSING,
        /** An open question forbids it. Somebody has to answer that first. */
        BLOCKED
    }

    public Readiness readiness() {
        // An open question outranks everything else. GECERLI_TCKN is
        // `uretim` -- the checksum algorithm is public, so the value looks
        // producible -- and is still unusable while S-18 ("does the
        // application accept a synthetic TCKN?") is unanswered. Reading `how`
        // alone would call it ready and send a scenario to its death.
        if (blockedBy != null && !blockedBy.isBlank()) {
            return Readiness.BLOCKED;
        }
        return switch (how == null ? "" : how.trim().toLowerCase(Locale.ROOT)) {
            case "seed" -> Readiness.SEED;
            case "eksik", "" -> Readiness.MISSING;
            default -> Readiness.READY;
        };
    }

    /** What the precondition column prints: the phrase, falling back to the key. */
    public String shown() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return description != null && !description.isBlank() ? description : id;
    }
}
