package orchestrator.aitrace;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Where code that calls a model says which session the call belongs to.
 *
 * <p>Static, deliberately. The places that need it — {@code ActionTape}'s
 * caller {@code BudgetedTool}, {@code VisionClient}, the observation handler
 * Spring AI invokes from deep inside its own tool loop — are not all beans,
 * and threading a session parameter from a controller through Spring AI's
 * call stack is impossible. The session rides on the THREAD instead: an entry
 * point opens one, everything that thread does until it closes is recorded
 * into it, and a worker pool carries it over with {@link #wrap}.
 *
 * <p><b>Nothing here may break an AI call.</b> Every method catches whatever
 * the recording throws, logs it once per session, and returns. With no
 * recorder installed (a unit test, a context that failed to build the bean)
 * every method is a no-op.
 *
 * <pre>
 * try (AiTraces.Scope trace = AiTraces.open("ai-run", meta)) {
 *     ... model calls ...
 *     trace.fail(reason);   // only if it failed
 * }
 * </pre>
 */
public final class AiTraces {

    private static volatile AiTraceRecorder recorder;

    private static final ThreadLocal<TraceSession> CURRENT = new ThreadLocal<>();

    private AiTraces() {
    }

    static void install(AiTraceRecorder installed) {
        recorder = installed;
    }

    static void uninstall(AiTraceRecorder installed) {
        if (recorder == installed) {
            recorder = null;
        }
    }

    /**
     * Opens a session on this thread.
     *
     * A session already open on the thread becomes the new one's parent and
     * is restored when the new one closes.
     *
     * @param kind see {@code AiSessionEntity.kind}
     */
    public static Scope open(String kind, Meta meta) {
        TraceSession enclosing = CURRENT.get();
        TraceSession session = null;
        AiTraceRecorder installed = recorder;
        if (installed != null) {
            try {
                session = installed.open(kind, meta == null ? new Meta() : meta, enclosing);
                CURRENT.set(session);
            } catch (RuntimeException failed) {
                System.out.println("[uptest] ai-trace: oturum acilamadi (" + failed.getClass().getSimpleName()
                        + ": " + failed.getMessage() + ") — AI cagrisi kayitsiz devam ediyor");
            }
        }
        return new Scope(session, enclosing);
    }

    /**
     * The same job, run inside the session that is open on the thread
     * submitting it. Without this a pool's workers record into nothing, and a
     * "Senaryo Üret" batch would show its planning call and none of its N
     * generations.
     */
    public static <T> Callable<T> wrap(Callable<T> job) {
        TraceSession session = CURRENT.get();
        if (session == null) {
            return job;
        }
        return () -> {
            TraceSession before = CURRENT.get();
            CURRENT.set(session);
            try {
                return job.call();
            } finally {
                restore(before);
            }
        };
    }

    /**
     * A model call finished. Called by the observation handler and by
     * {@code VisionClient}; a call made with no session open becomes a session
     * of its own, so nothing that spends tokens goes unrecorded.
     */
    public static void turn(TurnRecord call) {
        if (call == null) {
            return;
        }
        TraceSession session = CURRENT.get();
        if (session != null) {
            try {
                session.turn(call);
            } catch (RuntimeException failed) {
                session.complain(failed);
            }
            return;
        }
        AiTraceRecorder installed = recorder;
        if (installed == null) {
            return;
        }
        TraceSession alone = null;
        try {
            alone = installed.open("other", new Meta().title(firstUserLine(call)), null);
            alone.turn(call);
            alone.finish(call.error());
        } catch (RuntimeException failed) {
            if (alone != null) {
                alone.complain(failed);
            }
        }
    }

    /**
     * A tool call finished, on this thread.
     *
     * @param result the whole result
     * @param seen   what the model was given back, when that differs (trimmed)
     */
    public static void tool(String name, String args, String outcome, String result, String seen,
                            Long durationMs) {
        TraceSession session = CURRENT.get();
        if (session == null) {
            return;
        }
        try {
            session.tool(name, args, outcome, result, seen, durationMs);
        } catch (RuntimeException failed) {
            session.complain(failed);
        }
    }

    /** The open session's id on this thread, or null. */
    public static String currentId() {
        TraceSession session = CURRENT.get();
        return session == null ? null : session.id.toString();
    }

    /** The walk a tape was recorded in, so "Gherkin oluştur" can hang under it. */
    public static UUID sessionOfTape(String tapeId) {
        AiTraceRecorder installed = recorder;
        if (installed == null) {
            return null;
        }
        try {
            return installed.sessionOfTape(tapeId);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    private static void restore(TraceSession before) {
        if (before == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(before);
        }
    }

    private static String firstUserLine(TurnRecord call) {
        for (TurnRecord.Msg message : call.messages()) {
            if ("user".equals(message.role()) && message.text() != null && !message.text().isBlank()) {
                String line = message.text().strip().lines().findFirst().orElse("");
                return line.length() > 120 ? line.substring(0, 119) + "…" : line;
            }
        }
        return null;
    }

    /** What a session is about. Every field may be left null. */
    public static final class Meta {
        private String title;
        private String app;
        private String product;
        private String flow;
        private String scenarioPath;
        private String cif;
        private String tapeId;
        private UUID parentId;
        private List<String> secrets = List.of();

        public Meta title(String value) { this.title = value; return this; }
        public Meta app(String value) { this.app = value; return this; }
        public Meta product(String value) { this.product = value; return this; }
        public Meta flow(String value) { this.flow = value; return this; }
        public Meta scenarioPath(String value) { this.scenarioPath = value; return this; }
        public Meta cif(String value) { this.cif = value; return this; }
        public Meta tapeId(String value) { this.tapeId = value; return this; }
        public Meta parentId(UUID value) { this.parentId = value; return this; }

        /** Values masked in everything this session stores — the tape's password and SMS code. */
        public Meta secrets(List<String> value) { this.secrets = value == null ? List.of() : value; return this; }

        String title() { return title; }
        String app() { return app; }
        String product() { return product; }
        String flow() { return flow; }
        String scenarioPath() { return scenarioPath; }
        String cif() { return cif; }
        String tapeId() { return tapeId; }
        UUID parentId() { return parentId; }
        List<String> secrets() { return secrets; }
    }

    /** An open session; closing it finishes the session and restores the enclosing one. */
    public static final class Scope implements AutoCloseable {

        private final TraceSession session;
        private final TraceSession enclosing;
        private String failure;
        private boolean closed;

        private Scope(TraceSession session, TraceSession enclosing) {
            this.session = session;
            this.enclosing = enclosing;
        }

        /** The session's id, for the UI to link to; null when nothing is being recorded. */
        public String id() {
            return session == null ? null : session.id.toString();
        }

        /** Marks the session failed; the reason is shown with it. */
        public void fail(String reason) {
            this.failure = reason == null || reason.isBlank() ? "başarısız" : reason;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (session != null) {
                try {
                    session.finish(failure);
                } catch (RuntimeException failed) {
                    session.complain(failed);
                }
                if (CURRENT.get() == session) {
                    restore(enclosing);
                }
            }
        }
    }
}
