package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool definitions go out with every turn — 49,478 tokens, 40% of one
 * measured walk. What may be cut is the prose; what may not is anything that
 * decides what a valid call looks like.
 */
class LeanToolsTest {

    /** Shaped like a real MCP browser tool: prose everywhere, a nested array of objects. */
    private static final String SCHEMA = """
            {"type":"object","title":"Fill form input",
             "description":"Arguments for filling a form.",
             "properties":{
               "fields":{"type":"array","description":"Fields to fill in. Be careful…",
                 "items":{"type":"object","description":"One field",
                   "properties":{
                     "name":{"type":"string","description":"Human-readable field name"},
                     "type":{"type":"string","enum":["textbox","checkbox"],"description":"Kind"},
                     "value":{"type":"string","description":"Value to fill","examples":["200"]}},
                   "required":["name","type","value"]}},
               "description":{"type":"string","description":"A caption the caller passes"}},
             "required":["fields"]}""";

    private static ToolDefinition full() {
        return ToolDefinition.builder()
                .name("browser_fill_form")
                .description("""
                        Fill multiple form fields at once. This tool is the preferred way to fill a
                        form because it is faster than typing into each box in turn, and it avoids
                        the problem of a page re-rendering between two separate calls. Use it
                        whenever more than one field has to be set on the same screen.""")
                .inputSchema(SCHEMA)
                .build();
    }

    @Test
    @DisplayName("the description is cut to its first sentences")
    void descriptionIsCut() {
        ToolDefinition lean = LeanTools.lean(full(), 120);
        assertThat(lean.description()).hasSizeLessThanOrEqualTo(121)
                .startsWith("Fill multiple form fields at once.");
        assertThat(lean.name()).isEqualTo("browser_fill_form");
    }

    @Test
    @DisplayName("the shape of a valid call survives; only the prose goes")
    void shapeSurvives() {
        String lean = LeanTools.lean(full(), 120).inputSchema();

        assertThat(lean)
                .contains("\"fields\"").contains("\"items\"")
                .contains("\"textbox\"").contains("\"required\":[\"fields\"]")
                .contains("\"required\":[\"name\",\"type\",\"value\"]")
                .doesNotContain("Human-readable").doesNotContain("Be careful")
                .doesNotContain("\"examples\"").doesNotContain("\"title\"");
        assertThat(lean.length()).isLessThan(SCHEMA.length() / 2);
    }

    @Test
    @DisplayName("an ARGUMENT named 'description' is kept — only prose about it is removed")
    void argumentNamedDescriptionSurvives() {
        String lean = LeanTools.lean(full(), 120).inputSchema();
        assertThat(lean).contains("\"description\":{\"type\":\"string\"}");
        assertThat(lean).doesNotContain("A caption the caller passes");
    }

    @Test
    @DisplayName("a schema that cannot be read is passed through rather than guessed at")
    void unreadableSchemaIsLeftAlone() {
        assertThat(LeanTools.shape("not json at all")).isEqualTo("not json at all");
        assertThat(LeanTools.shape(null)).isNull();
    }

    @Test
    void shortDescriptionsAreLeftAsTheyAre() {
        assertThat(LeanTools.shorten("Take a snapshot.", 200)).isEqualTo("Take a snapshot.");
        assertThat(LeanTools.shorten(null, 10)).isEmpty();
    }
}
