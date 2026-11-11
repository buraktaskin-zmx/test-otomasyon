package orchestrator.aitrace;

import java.util.List;

/**
 * The two masking rules the action tape established, shared with the token
 * trace.
 *
 * <p>Moved here VERBATIM from {@code ActionTape} (which now delegates) because
 * the trace writes the same texts — the agent's tool arguments and results,
 * and the composed instruction with its login preamble — and a second,
 * slightly different copy of these rules is exactly how a password ends up in
 * one artifact and not the other. The lessons that shaped them are kept with
 * them below.
 */
public final class SecretMask {

    /** What a masked value is replaced with. */
    public static final String MASK = "***";

    /**
     * Shorter than this and masking does more harm than good.
     *
     * A three-character secret appears inside ordinary page text constantly,
     * so replacing it would quietly rewrite the very arguments this file
     * exists to preserve.
     */
    public static final int SHORTEST_SECRET = 4;

    /** Masks nothing. */
    public static final SecretMask NONE = new SecretMask(List.of());

    private final List<String> secrets;

    private SecretMask(List<String> secrets) {
        this.secrets = secrets;
    }

    /**
     * @param secrets values to mask wherever they appear; nulls, blanks and
     *                anything shorter than {@link #SHORTEST_SECRET} are ignored
     */
    public static SecretMask of(List<String> secrets) {
        List<String> usable = secrets == null ? List.of() : secrets.stream()
                .filter(secret -> secret != null && secret.trim().length() >= SHORTEST_SECRET)
                .map(String::trim)
                .toList();
        return new SecretMask(usable);
    }

    /** The secrets kept, for a caller that has to hand the same set on. */
    public List<String> secrets() {
        return secrets;
    }

    /**
     * Masks a secret only where it is a WHOLE JSON string value.
     *
     * <p>The first version replaced the secret wherever it occurred, and the
     * very first real tape proved why that is wrong: the test password is
     * {@code 123456} and the dealer code under test is {@code 81234567}, so
     * the grid came back as {@code cell "8***7"} — the artifact corrupted on
     * exactly the value the scenario asserts. A tape that edits the page's own
     * data is worse than no tape, because it looks right.
     *
     * <p>So the match is anchored on the quotes: a credential is always a
     * complete argument ({@code "value": "123456"}, {@code "text": "123456"}),
     * never a fragment of a longer string, while a page value that merely
     * CONTAINS those digits never has quotes around just that part. Same idea
     * as Redactor, whose mask is itself a quoted argument so the pass is
     * idempotent and bounded.
     *
     * <p>What this gives up: a password echoed back in plain prose by some
     * tool would survive. These tools do not echo it — a fill reports "Typed
     * into …" — and over-masking has now cost more than under-masking would.
     */
    public String maskValues(String text) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String secret : secrets) {
            // Double quotes for the model's own JSON argument, single quotes
            // for the Playwright line the MCP server echoes back in its
            // result: "### Ran Playwright code … page.locator('#password')
            // .fill('123456')". The first version only covered the argument,
            // so the password went into the tape anyway — through the half
            // nobody had looked at, one line below the half that was masked.
            masked = masked.replace('"' + secret + '"', '"' + MASK + '"')
                    .replace('\'' + secret + '\'', '\'' + MASK + '\'');
        }
        return masked;
    }

    /**
     * Masks a secret anywhere, for the composed instruction only.
     *
     * <p>That text is written by {@code ExploreController.compose}, which puts
     * the credential in unquoted ({@code - Şifre = 123456}), so the anchored
     * rule above would miss it. The looser rule is acceptable here and nowhere
     * else: the instruction is prose kept for audit, nothing is ever replayed
     * from it, and over-masking a word in it costs a line of context rather
     * than a value under test.
     */
    public String maskText(String text) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String secret : secrets) {
            masked = masked.replace(secret, MASK);
        }
        return masked;
    }
}
