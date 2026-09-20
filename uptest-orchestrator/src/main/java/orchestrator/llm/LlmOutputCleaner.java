package orchestrator.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips everything a chat model wraps around the file it was asked for.
 *
 * Two things reliably arrive even when the prompt forbids them:
 *
 *  - Qwen3 reasoning blocks, {@code <think> ... </think>}, emitted before the
 *    answer. vLLM passes them straight through in the content field.
 *  - Markdown fences, ```gherkin ... ```, because the model has seen a million
 *    code answers formatted that way.
 *
 * Either one reaches the Gherkin parser as a syntax error on line 1, which
 * would burn a repair round trip on a formatting artefact rather than a real
 * mistake. Cheaper to remove them here.
 */
public final class LlmOutputCleaner {

    private static final Pattern THINK =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** An unterminated block: the model started thinking and never closed it. */
    private static final Pattern UNCLOSED_THINK =
            Pattern.compile("^\\s*<think>.*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern FENCED =
            Pattern.compile("```(?:gherkin|cucumber|feature)?\\s*\\r?\\n(.*?)```",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private LlmOutputCleaner() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = THINK.matcher(raw).replaceAll("");
        text = UNCLOSED_THINK.matcher(text).replaceAll("");

        // Prefer the fenced block when there is one: any preamble the model
        // added despite instructions sits outside it.
        Matcher fenced = FENCED.matcher(text);
        if (fenced.find()) {
            text = fenced.group(1);
        } else {
            // Stray fences with nothing to pair with.
            text = text.replaceAll("```[a-zA-Z]*", "");
        }

        // Drop anything before the first Gherkin keyword — a leftover
        // "Here is the scenario:" line would be a parse error on line 1.
        int start = indexOfFirstKeyword(text);
        if (start > 0) {
            text = text.substring(start);
        }
        return text.strip() + "\n";
    }

    private static int indexOfFirstKeyword(String text) {
        Matcher m = Pattern.compile("(?m)^\\s*(Feature|Özellik|@)").matcher(text);
        return m.find() ? m.start() : 0;
    }
}
