package orchestrator.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A recorded walk, read back from disk by its id.
 *
 * <p>The Gherkin is generated in a SECOND request, after the walk has
 * answered: the analyst reads what the agent saw while the model writes the
 * scenario. By then the {@link ActionTape} object is gone with the first
 * request, and the file it wrote is the only copy — which is also the point
 * of writing it.
 *
 * <p>The id arrives from a browser, so it is accepted only in the one shape
 * {@link ActionTape} mints: a UUID. Anything else — a "..", a slash, an
 * absolute path — is refused before it is ever joined to a directory.
 */
public final class TapeFile {

    private static final Pattern ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param raw the file as written, which is what the provenance check
     *            searches: every label the screen printed and every value the
     *            agent typed is somewhere in it
     */
    public record Loaded(String id, String mainMenu, List<TapeRenderer.Call> calls, String raw) {
    }

    private TapeFile() {
    }

    /** Whether the id has the only shape a tape id ever has. */
    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The tape, or empty when the id is malformed or nothing was written under it. */
    public static Optional<Loaded> read(Path tapeDir, String id) {
        if (!isId(id)) {
            return Optional.empty();
        }
        Path file = tapeDir.toAbsolutePath().normalize().resolve(id).resolve("tape.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(parse(id, raw));
        } catch (IOException | RuntimeException unreadable) {
            System.out.println("[uptest] tape okunamadi (" + file + "): " + unreadable.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the file's text; separate so it can be exercised without a disk. */
    static Loaded parse(String id, String raw) throws IOException {
        JsonNode document = JSON.readTree(raw);
        List<TapeRenderer.Call> calls = new ArrayList<>();
        JsonNode entries = document.path("entries");
        for (JsonNode entry : entries) {
            calls.add(new TapeRenderer.Call(
                    entry.path("seq").asInt(),
                    entry.path("tool").asText(""),
                    entry.hasNonNull("args") ? entry.get("args").asText() : "",
                    entry.path("outcome").asText(""),
                    entry.hasNonNull("result") ? entry.get("result").asText() : null));
        }
        return new Loaded(id, document.path("mainMenu").asText(""), calls, raw);
    }
}
