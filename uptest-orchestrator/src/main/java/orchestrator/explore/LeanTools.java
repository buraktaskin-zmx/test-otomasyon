package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * The same tools, described in fewer words.
 *
 * <p><b>Measured, 11.11.2026.</b> A 22-turn "AI ile çalıştır" spent 124,817
 * input tokens, and after the old pages were cut out of each turn the single
 * largest item left was the TOOL DEFINITIONS: 49,478 tokens, 40% of the run,
 * about 2,250 every turn for eight tools. Nothing can make them cheaper by
 * sending them less often — a tool the model is not told about is a tool it
 * cannot call, so they go with every request. What can be made cheaper is the
 * words.
 *
 * <p>The MCP browser tools arrive with several paragraphs of prose each and a
 * JSON schema that repeats that prose on every property. The agent needs the
 * NAME, what the tool does in a line, and the argument names and types; the
 * rest is written for a human reading a tool catalogue.
 *
 * <p>So the description is cut to its first sentences and the schema keeps only
 * its shape. Nothing is removed that changes what a call must look like:
 * {@code type}, {@code properties}, {@code required}, {@code enum} and
 * {@code items} all stay, and so do the property NAMES — only the prose
 * attached to them goes.
 *
 * <p>"AI ile çalıştır" only. Ekran Keşfi is reading a screen it has never seen
 * and its agent is told to weigh each control, so it keeps every word.
 */
final class LeanTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Prose keys a model does not need in order to make a well-formed call. */
    private static final List<String> PROSE = List.of("description", "examples", "example",
            "title", "default", "$schema", "$comment", "markdownDescription");

    private LeanTools() {
    }

    /**
     * @param maxDescription characters kept of the tool's own description; the
     *                       cut lands on a sentence end when there is one near
     */
    static ToolDefinition lean(ToolDefinition full, int maxDescription) {
        return ToolDefinition.builder()
                .name(full.name())
                .description(shorten(full.description(), maxDescription))
                .inputSchema(shape(full.inputSchema()))
                .build();
    }

    /** The first sentences, up to the cap. */
    static String shorten(String description, int max) {
        if (description == null) {
            return "";
        }
        String flat = description.strip().replaceAll("\\s*\\n\\s*", " ").replaceAll("  +", " ");
        if (flat.length() <= max) {
            return flat;
        }
        String cut = flat.substring(0, max);
        int sentence = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf(".\n"));
        return sentence > max / 3 ? cut.substring(0, sentence + 1) : cut.strip() + "…";
    }

    /**
     * The schema with its prose stripped out, everything that defines a valid
     * call left in.
     *
     * Returns the schema untouched when it cannot be parsed: a tool whose
     * arguments the model gets wrong is worse than a tool that costs tokens.
     */
    static String shape(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return inputSchema;
        }
        try {
            JsonNode root = JSON.readTree(inputSchema);
            strip(root);
            return JSON.writeValueAsString(root);
        } catch (Exception unreadable) {
            return inputSchema;
        }
    }

    /**
     * Walks SCHEMA nodes only.
     *
     * The distinction is load-bearing. Inside {@code properties} the keys are
     * argument NAMES, and an argument may perfectly well be called
     * "description" or "title" — a blind recursion that removed those keys
     * wherever it found them would delete the argument itself and leave a tool
     * the model cannot call correctly. So the values of {@code properties} are
     * recursed into, never its keys.
     */
    private static void strip(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        PROSE.forEach(object::remove);

        JsonNode properties = object.get("properties");
        if (properties instanceof ObjectNode named) {
            named.fieldNames().forEachRemaining(name -> strip(named.get(name)));
        }
        for (String key : List.of("$defs", "definitions")) {
            if (object.get(key) instanceof ObjectNode defs) {
                defs.fieldNames().forEachRemaining(name -> strip(defs.get(name)));
            }
        }
        strip(object.get("items"));
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            if (object.get(key) instanceof ArrayNode branches) {
                branches.forEach(LeanTools::strip);
            }
        }
    }
}
