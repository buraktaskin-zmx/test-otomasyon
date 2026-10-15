package orchestrator.explore;

import orchestrator.catalog.CatalogModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Holds `catalogs/SCHEMA.md` and the agent's tools to the same contract.
 *
 * This exists because the same mistake has now happened three times, and each
 * time it was found by a human reading a draft rather than by anything in the
 * build:
 *
 *   1. `entry_tab` was ACCEPTED by submit_catalog and then dropped by toYaml,
 *      so the Banko catalog said nothing about the "Ödenecekler" tab the agent
 *      had demonstrably clicked.
 *   2. `entry_choice` was added to SCHEMA.md when the DBS radio fork was
 *      discovered, and never followed through to a tool argument at all — so
 *      the model read about the key in the schema (SCHEMA.md is pasted into the
 *      system prompt verbatim) and had nowhere to put the answer.
 *   3. `shown_when`, the same way.
 *
 * All three are SILENT. A catalog with no `entry_choice` looks exactly like a
 * screen that has no fork, and `ExploreController.problems()` cannot know the
 * difference — there is no signal in the document to check. The failure only
 * appears minutes later, in a browser, as a form that never opened.
 *
 * So the check is a round trip rather than a text scan: fill EVERY argument the
 * tool accepts, and look for every key the schema documents in what comes out.
 * That catches all three shapes at once — a key with no argument, an argument
 * the renderer drops, and a key that is fully wired (which passes).
 *
 * Deliberately signature-agnostic. The arguments are discovered by reflection
 * and filled by type, so ADDING an argument to submit_catalog does not require
 * touching this test. A contract test that has to be edited whenever the
 * contract changes is one more thing to forget.
 */
class CatalogContractTest {

    /**
     * Every String argument gets this. The assertions only ask whether the KEY
     * reached the document, never what the value was, so one marker does for
     * all of them.
     */
    private static final String MARKER = "X";

    /** Maven runs tests with the module directory as the working directory. */
    private static final Path SCHEMA = Path.of("..", "catalogs", "SCHEMA.md");

    @Test
    @DisplayName("every navigation key in SCHEMA.md survives the trip through submit_catalog")
    void everyDocumentedNavigationKeyReachesTheYaml() {
        Set<String> documented = navigationKeysFromSchema();

        // A schema that parsed to nothing would make this test pass by
        // accident, which is the one outcome worse than failing.
        assertThat(documented)
                .as("SCHEMA.md's navigation block parsed to nothing — the parser below is "
                        + "out of step with the document, and this test is checking nothing")
                .isNotEmpty();

        String yaml = catalogWithEveryArgumentFilled();

        List<String> missing = documented.stream()
                .filter(key -> !yaml.contains(key + ":"))
                .toList();

        assertThat(missing)
                .as("""
                        These keys are documented in catalogs/SCHEMA.md but never reach the \
                        catalog the agent produces, even with every tool argument filled in. \
                        Either submit_catalog has no argument for them, or toYaml accepts one \
                        and discards it. Both are silent: the draft looks like a screen that \
                        simply does not have that feature.
                        Produced catalog:
                        %s""".formatted(yaml))
                .isEmpty();
    }

    /**
     * The other half of making an argument required: "" has to mean something.
     *
     * These navigation keys are required arguments precisely so the model
     * cannot skip them — a model fills what the schema demands and omits what
     * it merely permits. That only works if there is a way to say "this screen
     * had none", and the way is an empty string. If "" leaked into the document
     * the catalog would claim a fork, a tab and a row that do not exist, and
     * the generated scenario would hunt for all three.
     */
    @Test
    @DisplayName("an empty navigation argument writes no key at all")
    void emptyMeansThereWasNone() {
        CatalogSink sink = new CatalogSink();
        sink.submitCatalog("NGC", "Flow", "MENÜ", "Alt Menü",
                "", "", "", "",
                List.of(), List.of(), "", List.of());

        assertThat(sink.yaml())
                .contains("main_menu:")
                .doesNotContain("entry_tab:")
                .doesNotContain("entry_row:")
                .doesNotContain("entry_button:")
                .doesNotContain("entry_choice:");
    }

    /**
     * Proves the two ends actually speak to each other.
     *
     * `shown_when` is written here and read by {@code CatalogModel}, which is
     * what {@code VariationPlanner} asks when deciding which variations may
     * fill a conditional field. Asserting on the YAML text alone would pass
     * just as happily with the wrong indentation or a flat `shown_when_field:`
     * key — and the gap would surface as a field quietly filled in every
     * scenario, including the ones where the screen does not have it.
     */
    @Test
    @DisplayName("a conditional field survives the round trip into CatalogModel")
    void conditionalFieldRoundTrips() {
        CatalogSink sink = new CatalogSink();
        sink.noteField("Fatura Tipi", "dropdown", List.of("Normal", "E-Fatura"),
                null, null, null, "", "");
        sink.noteField("E-Fatura Hash Kodu", "text", List.of(),
                null, null, null, "Fatura Tipi", "E-Fatura");
        sink.submitCatalog("NGC", "Flow", "MENÜ", "Alt Menü", "", "", "", "",
                List.of(), List.of(), "", List.of());

        CatalogModel catalog = CatalogModel.parse(sink.yaml()).orElseThrow();

        CatalogModel.CatalogField hash = catalog.field("E-Fatura Hash Kodu").orElseThrow();
        assertThat(hash.conditional()).isTrue();
        assertThat(hash.shownWhenField()).isEqualTo("Fatura Tipi");
        assertThat(hash.shownWhenValue()).isEqualTo("E-Fatura");

        // The unconditional one must NOT pick up a condition from its neighbour.
        assertThat(catalog.field("Fatura Tipi").orElseThrow().conditional()).isFalse();
    }

    /**
     * The navigation keys the schema promises, read from its own example block.
     *
     * The example is the contract in its most literal form — it is what an
     * analyst copies and what the model is shown. Parsing it rather than
     * keeping a list here is the whole point: a hand-maintained list in a test
     * is exactly the thing that was forgotten three times.
     */
    private static Set<String> navigationKeysFromSchema() {
        String schema = readSchema();
        Set<String> keys = new LinkedHashSet<>();

        Matcher block = Pattern.compile("(?m)^navigation:\\s*$").matcher(schema);
        if (!block.find()) {
            fail("No 'navigation:' block found in " + SCHEMA.toAbsolutePath()
                    + " — the schema moved and this test cannot read it any more.");
        }

        // Keys are the indented lines directly under it; the block ends at the
        // first line that is not one.
        Pattern key = Pattern.compile("^\\s+([a-z_]+):.*$");
        for (String line : schema.substring(block.end()).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            Matcher found = key.matcher(line);
            if (!found.matches()) {
                break;
            }
            keys.add(found.group(1));
        }
        return keys;
    }

    private static String readSchema() {
        try {
            return Files.readString(SCHEMA, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // Never skipped. A contract test that quietly does nothing when it
            // cannot find the contract is not a contract test.
            return fail("Could not read " + SCHEMA.toAbsolutePath()
                    + ". Tests are expected to run with the module directory as the working "
                    + "directory, which is what Maven does.", unreadable);
        }
    }

    /**
     * Calls submit_catalog with every argument it accepts filled in, and hands
     * back the catalog it rendered.
     *
     * Filling EVERYTHING is what makes the result meaningful: a key missing
     * from this document cannot be blamed on an argument the caller left out.
     */
    private static String catalogWithEveryArgumentFilled() {
        CatalogSink sink = new CatalogSink();
        Method submit = Arrays.stream(CatalogSink.class.getMethods())
                .filter(method -> method.getName().equals("submitCatalog"))
                .findFirst()
                .orElseGet(() -> fail("CatalogSink has no submitCatalog method any more."));

        List<Object> arguments = new ArrayList<>();
        for (Class<?> type : submit.getParameterTypes()) {
            if (type == String.class) {
                arguments.add(MARKER);
            } else if (List.class.isAssignableFrom(type)) {
                arguments.add(List.of());
            } else {
                // Not a failure of the contract, but of this test's ability to
                // check it — and that must never pass quietly.
                fail("submitCatalog grew a " + type.getSimpleName() + " argument, which this "
                        + "test does not know how to fill. Teach it, or the key behind that "
                        + "argument stops being checked.");
            }
        }

        try {
            submit.invoke(sink, arguments.toArray());
        } catch (ReflectiveOperationException failed) {
            return fail("submit_catalog threw with every argument filled in.", failed);
        }
        return sink.yaml();
    }
}
