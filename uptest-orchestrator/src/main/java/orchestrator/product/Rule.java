package orchestrator.product;

import java.util.List;
import java.util.Locale;

/**
 * One business rule: a thing the application MUST do, written by a person.
 *
 * This is the piece a catalog structurally cannot carry. Both catalog
 * producers READ a live screen — the DOM scan and the MCP agent — so whatever
 * they record is a description of what is there, never a judgement about
 * whether it should be. Ask an agent to "test this screen" and it will write
 * down the İptal button it finds on a paid invoice as expected behaviour, and
 * a bug becomes a passing test. The oracle has to come from somewhere nothing
 * on the screen can supply.
 *
 * <p>{@code messages} is the part that reaches code rather than only the
 * prompt. {@code ScenarioValidator.checkMessageIsCatalogued} accepts exactly
 * one asserted sentence today — the catalog's {@code success_message} — and
 * its own javadoc admits the consequence: "a negative scenario asserting an
 * error dialog's text is flagged too". Nearly every rule worth writing for a
 * payment product is negative, so without this list those scenarios cannot be
 * generated at all.
 *
 * @param id          the stable handle, e.g. {@code R-MAAS-010}. Required; a
 *                    rule without one is dropped, because an unnamed rule
 *                    cannot be selected, tagged or reported on
 * @param status      {@link #CONFIRMED} or {@link #ASSUMPTION}. Anything else,
 *                    including absent, counts as an assumption — see
 *                    {@link #confirmed()}
 * @param fields      the catalogued labels this rule touches, for the later
 *                    relevance filter. Unused in v1 and deliberately so: a
 *                    wrong match drops a rule silently
 * @param messages    the EXACT sentences the application prints for this rule.
 *                    Compared character for character after folding, so a
 *                    paraphrase here is as wrong as no entry at all
 */
public record Rule(String id, String title, String status, String description,
                   List<String> fields, List<String> messages, String source) {

    public static final String CONFIRMED = "DOĞRULANDI";
    public static final String ASSUMPTION = "VARSAYIM";

    /**
     * Whether a human has verified this rule against something authoritative.
     *
     * Note the direction of the default: anything unrecognised is NOT
     * confirmed. Defaulting the other way would quietly promote a guess to a
     * fact, and the whole point of carrying a status is that the two are
     * different. The UI leaves unconfirmed rules unticked for the same reason —
     * putting an assumption into a test should take a deliberate click.
     */
    public boolean confirmed() {
        return CONFIRMED.equals(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
    }
}
