package orchestrator.aitrace;

import orchestrator.persistence.AiSessionEntity;
import orchestrator.persistence.AiToolCallEntity;
import orchestrator.persistence.AiTurnEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session without Spring: an in-memory sink, a real disk in a temp dir, and
 * a tokenizer that either answers (one token per character, so sums are easy
 * to read) or is down.
 */
class TraceSessionTest {

    @TempDir
    Path dir;

    private FakeSink sink;

    @BeforeEach
    void setUp() {
        sink = new FakeSink(new TraceFiles(dir, new ObjectMapper()));
    }

    private TraceSession open(String... secrets) {
        return new TraceSession(UUID.randomUUID(), "ai-run",
                new AiTraces.Meta().title("Firma Fatura/01.feature").secrets(List.of(secrets)), null, sink);
    }

    private static TurnRecord turn(Integer prompt, Integer completion, List<TurnRecord.Msg> messages,
                                   String toolDefs, TurnRecord.Call... calls) {
        return new TurnRecord(Instant.now(), 120, "qwen3-6", prompt, completion, null, 7L, "stop",
                messages, toolDefs, toolDefs == null ? 0 : 1, "cevap", null, List.of(calls), false, null);
    }

    private static final TurnRecord.Msg SYSTEM = TurnRecord.Msg.of("system", "SSSSSSSSSS");   // 10
    private static final TurnRecord.Msg USER = TurnRecord.Msg.of("user", "UUUUU");            // 5

    @Test
    @DisplayName("a tool call belongs to the turn this thread finished last")
    void toolCallAttachesToLastTurnOfThread() {
        TraceSession session = open();
        session.turn(turn(20, 3, List.of(SYSTEM, USER), null, new TurnRecord.Call("c1", "browser_click", "{}")));
        session.tool("browser_click", "{\"target\":\"e1\"}", "OK", "clicked", "clicked", 40L);
        session.turn(turn(30, 3, List.of(SYSTEM, USER), null));
        session.tool("browser_snapshot", "{}", "OK", "page", "page", 10L);

        assertThat(sink.tools).extracting(AiToolCallEntity::getTurnSeq).containsExactly(1, 2);
        assertThat(sink.tools).extracting(AiToolCallEntity::getSeq).containsExactly(1, 2);
        assertThat(sink.lastSession().getToolCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("in a parallel batch each worker's tool calls go to that worker's own turn")
    void parallelWorkersKeepTheirOwnTurns() throws Exception {
        TraceSession session = open();
        CyclicBarrier bothTurned = new CyclicBarrier(2);
        List<Throwable> failures = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        for (String tool : List.of("a", "b")) {
            new Thread(() -> {
                try {
                    session.turn(turn(10, 1, List.of(TurnRecord.Msg.of("user", tool)), null));
                    bothTurned.await();
                    session.tool(tool, "{}", "OK", tool, tool, 1L);
                } catch (Throwable failed) {
                    failures.add(failed);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();
        assertThat(failures).isEmpty();

        Map<String, Integer> turnOfUser = new HashMap<>();
        for (AiTurnEntity t : sink.turns) {
            String user = sink.files.turn(session.id, t.getSeq()).orElseThrow().get("messages").toString();
            turnOfUser.put(user.contains(TraceFiles.sha("a")) ? "a" : "b", t.getSeq());
        }
        for (AiToolCallEntity call : sink.tools) {
            assertThat(call.getTurnSeq()).isEqualTo(turnOfUser.get(call.getTool()));
        }
    }

    @Test
    @DisplayName("a message the session already sent is not new, and is stored once")
    void repeatedMessagesAreNotNewAndStoredOnce() throws IOException {
        TraceSession session = open();
        session.turn(turn(20, 3, List.of(SYSTEM, USER), null));
        session.turn(turn(30, 3, List.of(SYSTEM, USER,
                TurnRecord.Msg.of("assistant", "→ browser_click {}"),
                new TurnRecord.Msg("tool", "RESULT", "browser_click", "c1")), null));

        assertThat(sink.turns.get(0).getNewMessageCount()).isEqualTo(2);
        assertThat(sink.turns.get(1).getNewMessageCount()).isEqualTo(2);
        assertThat(sink.turns.get(1).getMessageCount()).isEqualTo(4);
        try (Stream<Path> stored = Files.list(dir.resolve(session.id.toString()).resolve("msg"))) {
            long texts = stored.filter(p -> p.toString().endsWith(".txt")).count();
            // system, user, assistant, tool, and the response "cevap" (same both turns)
            assertThat(texts).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("the password never reaches the disk — not in the prompt, not in a tool call")
    void secretsAreMaskedEverywhere() throws IOException {
        TraceSession session = open("123456");
        session.turn(turn(20, 3, List.of(SYSTEM, TurnRecord.Msg.of("user", "Giriş yap:\n- Şifre = 123456")),
                null, new TurnRecord.Call("c1", "browser_type", "{\"text\":\"123456\"}")));
        session.tool("browser_type", "{\"text\":\"123456\"}", "OK",
                "page.locator('#password').fill('123456')", "ok", 5L);

        try (Stream<Path> all = Files.walk(dir)) {
            for (Path file : all.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file, StandardCharsets.UTF_8))
                        .as(file.toString()).doesNotContain("123456");
            }
        }
        assertThat(sink.tools.get(0).getArgsPreview()).doesNotContain("123456");
    }

    @Test
    @DisplayName("an exact split: the parts are counted, the remainder is the template's")
    void exactBreakdownLeavesRemainderAsOverhead() {
        sink.tokenizerUp = true;
        TraceSession session = open();
        session.turn(turn(40, 3, List.of(SYSTEM, USER, new TurnRecord.Msg("tool", "RRRR", "t", "c1")), "DDDDDDDD"));

        AiTraceStore.Breakdown split = sink.splits.get(0);
        assertThat(split.exact()).isTrue();
        assertThat(split.system()).isEqualTo(10);
        assertThat(split.user()).isEqualTo(5);
        assertThat(split.toolResults()).isEqualTo(4);
        assertThat(split.toolDefs()).isEqualTo(8);
        assertThat(split.overhead()).isEqualTo(40 - 27);
    }

    @Test
    @DisplayName("with the tokenizer down the split is estimated from the session's own first turn, and says so")
    void estimatedBreakdownUsesSessionsOwnRatio() {
        sink.tokenizerUp = false;
        TraceSession session = open();
        // 15 characters, server says 5 tokens -> 3 chars per token
        session.turn(turn(5, 1, List.of(SYSTEM, USER), null));

        AiTraceStore.Breakdown split = sink.splits.get(0);
        assertThat(split.exact()).isFalse();
        assertThat(split.system()).isEqualTo(3);   // 10 / 3, rounded
        assertThat(split.user()).isEqualTo(2);     // 5 / 3, rounded
    }

    @Test
    @DisplayName("session totals are the sum of its turns; a missing cache figure stays null, not zero")
    void totalsSumTurns() {
        TraceSession session = open();
        session.turn(turn(20, 3, List.of(USER), null));
        session.turn(turn(30, 4, List.of(USER), null));
        session.finish(null);

        AiSessionEntity row = sink.lastSession();
        assertThat(row.getPromptTokens()).isEqualTo(50);
        assertThat(row.getCompletionTokens()).isEqualTo(7);
        assertThat(row.getTotalTokens()).isEqualTo(57);
        assertThat(row.getCachedTokens()).isEqualTo(14);
        assertThat(row.getTurnCount()).isEqualTo(2);
        assertThat(row.getStatus()).isEqualTo("DONE");
        assertThat(row.getModels()).isEqualTo("qwen3-6");
    }

    @Test
    void failedSessionKeepsItsReason() {
        TraceSession session = open();
        session.finish("Tarayıcı beklenmedik şekilde kapandı");
        session.finish(null);   // a second close changes nothing
        assertThat(sink.lastSession().getStatus()).isEqualTo("FAILED");
        assertThat(sink.lastSession().getErrorMessage()).contains("Tarayıcı");
    }

    /** Records everything, runs "later" jobs at once. */
    static final class FakeSink implements TraceSink {
        final TraceFiles files;
        final List<AiSessionEntity> sessions = new ArrayList<>();
        final List<AiTurnEntity> turns = new ArrayList<>();
        final List<AiToolCallEntity> tools = new ArrayList<>();
        final List<AiTraceStore.Breakdown> splits = new ArrayList<>();
        volatile boolean tokenizerUp = true;

        FakeSink(TraceFiles files) {
            this.files = files;
        }

        AiSessionEntity lastSession() {
            synchronized (sessions) {
                return sessions.get(sessions.size() - 1);
            }
        }

        @Override public void saveSession(AiSessionEntity s) { synchronized (sessions) { sessions.add(s); } }
        @Override public synchronized void saveTurn(AiTurnEntity t) { turns.add(t); }
        @Override public synchronized void saveTool(AiToolCallEntity c) { tools.add(c); }
        @Override public synchronized void breakdown(UUID id, AiTraceStore.Breakdown b) { splits.add(b); }
        @Override public void toolTokens(UUID id, int tokens, boolean exact) { }
        @Override public TraceFiles files() { return files; }
        @Override public OptionalInt count(String text) {
            return tokenizerUp ? OptionalInt.of(text.length()) : OptionalInt.empty();
        }
        @Override public void later(Runnable job) { job.run(); }
    }
}
