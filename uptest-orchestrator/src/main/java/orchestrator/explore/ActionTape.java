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
 * <p><b>Secrets.</b> The agent really does type the password and the SMS code,
 * so those two values are masked wherever they appear. The cif and the user id
 * are NOT masked: a user id is four digits here and masking a four-digit
 * string corrupts every amount and invoice number that happens to match, which
 * is a worse artifact than an unmasked test credential in a gitignored
 * directory ({@code runs/}). When a tape is persisted or exported — H2, "Dosyaya
 * aktar" — that calculus changes and the rule in docs/EYLEM-BANDI.md §6
 * applies instead: the tape starts AFTER login and the login line is grafted
 * back at render time, exactly as LoginStep already does for generated
 * scenarios.
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
        tape.header.put("instruction", tape.mask(instruction));
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
            Path target = dir.resolve(id);
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
        entry.put("args", mask(input));
        entry.put("outcome", outcome.name());
        entry.put("result", mask(result));
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

    /** Every secret replaced, everywhere, including inside a result. */
    private String mask(String text) {
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
