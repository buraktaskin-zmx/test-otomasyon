package orchestrator.explore;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the agent actually did, written down so the walk can be replayed
 * without it.
 *
 * <p>The cost of an agent walk is paid in tokens and minutes, and it is paid
 * AGAIN every time the same scenario is exercised, because nothing survives
 * the run but a paragraph of prose. Every tool call already passes through one
 * method ({@link BudgetedTool#guarded}), which until now printed the arguments
 * to stdout and threw them away: <i>"Arguments only, never results."</i> That
 * was right for a log line and wrong for an artifact — a log cannot be
 * rendered into a scenario.
 *
 * <p><b>This is phase 0 and deliberately records, not interprets.</b> The
 * entries here are the RAW calls: tool name, the model's own argument JSON,
 * and what came back. Turning {@code {"target":"e42"}} into
 * {@code click "Detay"} needs the snapshot that was on screen when the model
 * chose {@code e42}, which is why results are kept rather than dropped — a
 * snapshot reference is dead the moment the page changes, so the mapping can
 * only be made from the text the model was looking at. Doing that mapping here
 * would mean re-running the walk when the mapping turns out to be wrong, and
 * re-running the walk is the one thing this class exists to avoid.
 *
 * <p><b>Rewritten after every entry</b>, the same way CatalogDiscovery
 * rewrites its draft after every scan: a walk that dies on screen three still
 * leaves screens one and two on disk, and the reason it died is usually the
 * thing being investigated. The files are small next to a Playwright trace.
 *
 * <p><b>Nothing here may break a walk.</b> Every write is best effort: a
 * directory that cannot be created or a disk that is full costs the tape, not
 * the run. Same discipline as Hooks closing the browser even when tracing
 * throws.
 *
 * <p><b>Secrets, and the lesson they cost.</b> The agent really does type the
 * password and the SMS code, so those two are masked — but only where they are
 * a WHOLE argument value, see {@link #maskValues}. Masking them wherever they
 * occurred was the first version and the first real tape killed it: the test
 * password is {@code 123456}, the dealer code under test is {@code 81234567},
 * and the grid came back {@code cell "8***7"}. The one value the scenario
 * asserts was destroyed by the thing meant to protect it.
 *
 * <p>The cif and the user id are not masked at all. They are test-environment
 * values, they already live in {@code TEST_RUN.intent}, and {@code runs/} is
 * gitignored. When a tape is persisted or exported — H2, "Dosyaya aktar" —
 * that calculus changes and the rule in docs/EYLEM-BANDI.md §6 applies
 * instead: the tape starts AFTER login and the login line is grafted back at
 * render time, exactly as LoginStep already does for generated scenarios.
 */
public final class ActionTape {

    /** The file name inside the tape's own directory. */
    public static final String FILE = "tape.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MASK = "***";

    /**
     * Shorter than this and masking does more harm than good.
     *
     * A three-character secret appears inside ordinary page text constantly,
     * so replacing it would quietly rewrite the very arguments this file
     * exists to preserve.
     */
    private static final int SHORTEST_SECRET = 4;

    private final String id;
    private final Instant startedAt = Instant.now();
    private final Map<String, Object> header = new LinkedHashMap<>();
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final List<String> secrets;

    /** Null when the directory could not be created; the tape then records but never writes. */
    private final Path file;

    /** Printed once, so a failing disk does not produce one line per tool call. */
    private boolean complained;

    /** What happened to one call. */
    public enum Outcome {
        /** The tool ran and its result went to the model. */
        OK,
        /** A guard answered instead of the tool — the browser never saw it. */
        REFUSED,
        /** The tool threw, or came back saying the browser was gone. */
        ERROR
    }

    private ActionTape(String id, Path file, List<String> secrets) {
        this.id = id;
        this.file = file;
        this.secrets = secrets;
    }

    /**
     * Opens a tape under {@code <dir>/<tapeId>/}.
     *
     * A directory of its own because the MCP server writes its own artifacts
     * into the same output directory, and because phase 1 will put the
     * resolved actions next to the raw ones.
     *
     * @param dir         where tapes live, from {@code uptest.explore.tape-dir}
     * @param kind        {@code explore} or {@code catalog} — which endpoint
     * @param instruction the composed instruction, masked before it is stored
     * @param secrets     values to mask wherever they appear; nulls and blanks
     *                    are ignored
     */
    public static ActionTape open(Path dir, String kind, String instruction,
                                  String url, String mainMenu, List<String> secrets) {
        List<String> usable = secrets == null ? List.of() : secrets.stream()
                .filter(secret -> secret != null && secret.trim().length() >= SHORTEST_SECRET)
                .map(String::trim)
                .toList();

        // The id names the directory, so a tapeId handed to the UI is enough
        // to find the file. Minting a second UUID for the directory made them
        // disagree, and the id in the answer then located nothing.
        String id = UUID.randomUUID().toString();

        ActionTape tape = new ActionTape(id, resolve(dir, id), usable);
        tape.header.put("tapeId", tape.id);
        tape.header.put("kind", kind);
        tape.header.put("recordedAt", tape.startedAt.toString());
        tape.header.put("url", url == null ? "" : url);
        tape.header.put("mainMenu", mainMenu == null ? "" : mainMenu);
        tape.header.put("instruction", tape.maskText(instruction));
        tape.flush();
        if (tape.file != null) {
            System.out.println("[uptest] tape -> " + tape.file);
        }
        return tape;
    }

    /**
     * The directory, or null when it cannot be made.
     *
     * Deliberately not an exception: a tape is an extra, and an exploration
     * that cannot write one should still explore.
     */
    private static Path resolve(Path dir, String id) {
        try {
            // Absolute, because the configured default is RELATIVE (./runs/mcp)
            // and a relative path resolves against whatever working directory
            // the orchestrator was started in — the module folder under IDEA,
            // the repo root from the command line. The first tape written went
            // missing for exactly this reason: the log line said ".\runs\mcp\…"
            // and nobody could say which ".". CatalogService.root() absolutises
            // for the same reason.
            Path target = dir.toAbsolutePath().normalize().resolve(id);
            Files.createDirectories(target);
            return target.resolve(FILE);
        } catch (RuntimeException | IOException unavailable) {
            System.out.println("[uptest] tape: dizin olusturulamadi (" + unavailable.getMessage()
                    + ") — kosu devam ediyor, bant yazilmiyor");
            return null;
        }
    }

    /** The id the caller hands back to the UI. */
    public String id() {
        return id;
    }

    /** Where the tape is being written, or null when it is not. */
    public Path file() {
        return file;
    }

    /**
     * One tool call, as it happened.
     *
     * @param tool    the tool's own name, e.g. {@code browser_click}
     * @param input   the model's raw argument JSON, stored whole: a truncated
     *                argument is the one the next diagnosis needs
     * @param outcome see {@link Outcome}
     * @param result  what the model was given back — already capped by
     *                {@link BudgetedTool}'s own result limit, so the tape holds
     *                exactly what the model saw and no more
     */
    public synchronized void record(String tool, String input, Outcome outcome, String result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("seq", entries.size() + 1);
        entry.put("atMs", Duration.between(startedAt, Instant.now()).toMillis());
        entry.put("tool", tool == null ? "" : tool);
        entry.put("args", maskValues(input));
        entry.put("outcome", outcome.name());
        entry.put("result", maskValues(result));
        entries.add(entry);
        flush();
    }

    /** Stamps the end of the walk. Called whether it finished or died. */
    public synchronized void finish() {
        header.put("finishedAt", Instant.now().toString());
        header.put("entryCount", entries.size());
        flush();
    }

    /**
     * Whole file, every time.
     *
     * Rewriting beats appending because the header carries a count that is
     * only right once the entry is in, and because a half-written JSON array
     * is not readable by anything. At tens of entries and a capped result per
     * entry this is a fraction of a second against a walk that takes minutes.
     */
    private void flush() {
        if (file == null) {
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>(header);
        document.put("entries", entries);
        try {
            Files.writeString(file,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unwritable) {
            if (!complained) {
                complained = true;
                System.out.println("[uptest] tape yazilamadi (" + unwritable.getMessage()
                        + ") — kosu devam ediyor");
            }
        }
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
    private String maskValues(String text) {
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
    private String maskText(String text) {
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
