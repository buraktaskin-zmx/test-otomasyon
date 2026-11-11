package orchestrator.aitrace;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The full texts of a session, on disk.
 *
 * <pre>
 * runs/llm/&lt;sessionId&gt;/
 *   session.json        the header, rewritten when the session ends
 *   turns/&lt;seq&gt;.json    which messages each turn sent, by sha, and what came back
 *   msg/&lt;sha&gt;.txt       every distinct text, ONCE
 *   msg/&lt;sha&gt;.tok       its token count once known: "&lt;n&gt; exact|estimate"
 * </pre>
 *
 * <p><b>Content-addressed, because an agent conversation only ever grows.</b>
 * Turn 30 resends the system prompt, the instruction and all twenty-nine
 * earlier exchanges; writing each turn's prompt whole would store the same
 * snapshot thirty times and make the directory grow with the square of the
 * walk. Here each text is written the first time it is seen and a turn is a
 * list of hashes — the same reason the session can say which messages were
 * NEW on a turn.
 *
 * <p><b>Never throws into a model call.</b> Every write is best effort, in
 * {@code ActionTape}'s discipline: a full disk costs the trace, not the run.
 * Every read validates its arguments before they become a path, since both
 * the session id and the sha arrive from a URL.
 */
final class TraceFiles {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final ObjectMapper mapper;

    TraceFiles(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    Path root() {
        return root;
    }

    static String sha(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean isSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    /**
     * Stores a text unless it is already there; returns its sha, or null for a
     * null text.
     */
    String put(UUID session, String text) throws IOException {
        if (text == null) {
            return null;
        }
        String sha = sha(text);
        Path file = dir(session).resolve("msg").resolve(sha + ".txt");
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        return sha;
    }

    void tokens(UUID session, String sha, int tokens, boolean exact) throws IOException {
        Path file = dir(session).resolve("msg").resolve(sha + ".tok");
        Files.createDirectories(file.getParent());
        Files.writeString(file, tokens + " " + (exact ? "exact" : "estimate"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    void turn(UUID session, int seq, Map<String, Object> document) throws IOException {
        Path file = dir(session).resolve("turns").resolve(seq + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
    }

    void header(UUID session, Map<String, Object> document) throws IOException {
        Path file = dir(session).resolve("session.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
    }

    Optional<String> read(UUID session, String sha) {
        if (!isSha(sha)) {
            return Optional.empty();
        }
        return readFile(dir(session).resolve("msg").resolve(sha + ".txt"));
    }

    /** The stored count of a text: tokens, and whether they were counted or estimated. */
    Optional<Count> count(UUID session, String sha) {
        if (!isSha(sha)) {
            return Optional.empty();
        }
        return readFile(dir(session).resolve("msg").resolve(sha + ".tok")).flatMap(line -> {
            String[] parts = line.trim().split(" ");
            try {
                return Optional.of(new Count(Integer.parseInt(parts[0]),
                        parts.length > 1 && "exact".equals(parts[1])));
            } catch (NumberFormatException unreadable) {
                return Optional.empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> turn(UUID session, int seq) {
        if (seq < 1) {
            return Optional.empty();
        }
        return readFile(dir(session).resolve("turns").resolve(seq + ".json")).map(json -> {
            try {
                return (Map<String, Object>) mapper.readValue(json, LinkedHashMap.class);
            } catch (RuntimeException unreadable) {
                return Map.of();
            }
        });
    }

    record Count(int tokens, boolean exact) {
    }

    private Path dir(UUID session) {
        // A UUID's toString is hex and dashes: it cannot climb out of root.
        return root.resolve(session.toString());
    }

    private static Optional<String> readFile(Path file) {
        try {
            return Files.exists(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }
}
