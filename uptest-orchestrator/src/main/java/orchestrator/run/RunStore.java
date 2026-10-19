package orchestrator.run;

import orchestrator.persistence.TestRunEntity;
import orchestrator.persistence.TestRunRepository;
import orchestrator.persistence.TestRunStepEntity;
import orchestrator.persistence.TestRunStepRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only bean in the run path that touches JPA.
 *
 * THE INVARIANT, and it is the reason this is a separate bean from RunService:
 * NO TRANSACTION IS EVER OPEN WHILE AN ExecutorClient CALL IS IN FLIGHT. Each
 * such call is bounded at executorTimeoutMs (5 s); ten of them held inside
 * transactions exhausts the default Hikari pool, and the symptom is a
 * 30-second JDBCConnectionException that looks nothing like its cause. So
 * RunService owns the HTTP and calls in here for each short database step.
 *
 * The split is also the only way to get the transactions at all: @Transactional
 * is applied by a proxy, so a @Transactional method called from ANOTHER METHOD
 * OF THE SAME BEAN gets no transaction — it just runs, and the dirty-check
 * flush silently never happens. If these methods lived on RunService, status()
 * calling this.apply() would quietly write nothing.
 *
 * It is additionally the seam that makes RunServiceTest possible with no
 * Spring context.
 */
@Service
public class RunStore {

    /** DONE/FAILED/CANCELED never change, so a terminal row is never asked
     *  about again. Mirrors the executor's ResultStatus minus RUNNING. */
    static final Set<String> TERMINAL = Set.of("DONE", "FAILED", "CANCELED");

    /** Matches TestRunEntity.status's column width. */
    private static final int STATUS_LENGTH = 32;

    /** Matches TestRunStepEntity.stepKeyword's column width. */
    private static final int KEYWORD_LENGTH = 32;

    private final TestRunRepository runs;
    private final TestRunStepRepository steps;

    /**
     * The Boot-managed Jackson 3 mapper, INJECTED rather than constructed.
     *
     * VocabularyService's `new ObjectMapper()` is the older in-module style;
     * injecting here means last_status_json is serialised by exactly the
     * mapper that put the live payload on the wire, so describe() reading it
     * back produces the same bytes app.js saw before persistence existed.
     */
    private final ObjectMapper mapper;

    public RunStore(TestRunRepository runs, TestRunStepRepository steps, ObjectMapper mapper) {
        this.runs = runs;
        this.steps = steps;
        this.mapper = mapper;
    }

    /**
     * Inserts the row for an accepted run.
     *
     * Status is "RUNNING", not "UNKNOWN": the executor answered 202, so
     * RUNNING is the measured truth, and the "Çalışıyor" chip finds the row
     * immediately instead of after the first poll.
     *
     * version is null on a fresh entity, which makes Spring Data's isNew()
     * true and save() a straight persist() — one INSERT, no SELECT, and the
     * instance returned is the managed one. See TestRunEntity.version.
     *
     * @param feature MUST already be redacted; see Redactor and the call site
     *                in RunService.start. Nothing downstream redacts it again.
     */
    @Transactional
    public TestRunEntity record(UUID id, String feature, String executorName,
                                String scenarioName, String app, String flow, String intent,
                                String product) {
        TestRunEntity row = new TestRunEntity(
                id, Instant.now(), "RUNNING", executorName, scenarioName,
                blankToNull(app), blankToNull(flow), blankToNull(intent), feature,
                blankToNull(product));
        return runs.save(row);
    }

    @Transactional(readOnly = true)
    public Optional<TestRunEntity> find(UUID id) {
        return runs.findById(id);
    }

    @Transactional(readOnly = true)
    public List<TestRunEntity> recent(int limit) {
        return runs.findRecent(PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Optional<String> findExecutorName(UUID id) {
        return runs.findExecutorNameById(id);
    }

    /**
     * Folds a freshly measured executor payload onto the row.
     *
     * Returns the MANAGED entity: there is no save() call here, because
     * calling save() on a managed entity is a no-op that reads as if it were
     * doing the work. The dirty check flushes on commit.
     *
     * Handing that managed entity back across the transaction boundary to
     * RunService.describe() is safe ONLY because TestRunEntity has no lazy
     * state — the direct payoff of TEST_RUN_STEP carrying a plain run_id
     * column instead of being an association. A future @OneToMany breaks this
     * line, loudly, because spring.jpa.open-in-view is false.
     */
    @Transactional
    public TestRunEntity apply(UUID runId, Map<String, Object> fresh) {
        TestRunEntity row = runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("TEST_RUN row missing for run " + runId));

        Map<String, Object> storable = storable(fresh);
        String json = mapper.writeValueAsString(storable);

        // THE DATABASE IS ALSO THE WRITE-COALESCING STATE. An unchanged
        // payload costs zero statements. This is what the ConcurrentHashMap
        // used to do implicitly, made explicit and durable — and it is what
        // makes writing the step snapshot on every poll free.
        if (json.equals(row.getLastStatusJson())) {
            return row;
        }

        row.setLastStatusJson(json);
        row.setStatus(statusOf(fresh, row.getStatus()));
        row.setCurrentStepIndex(asInteger(fresh.get("currentStepIndex")));
        row.setErrorMessage(errorMessageOf(fresh.get("error")));

        // Read off `storable`, not `fresh`: these texts are the redacted ones.
        List<Map<String, Object>> timeline = stepsOf(storable);
        row.setStepCount(timeline.isEmpty() ? null : timeline.size());
        replaceSteps(runId, timeline);

        if (TERMINAL.contains(row.getStatus())) {
            row.setFinishedAt(Instant.now());
        }
        return row;
    }

    /**
     * Settles a run nobody will ever answer for.
     *
     * UNKNOWN, not FAILED. FAILED is a claim about the test and we have no
     * idea whether it passed — a run marked failed that actually passed is the
     * silent-pass shape this project keeps paying for. UNKNOWN is the honest
     * answer, it already has a .pill-UNKNOWN rule in app.css, and it is
     * exactly what describe() has always emitted for a run with no executor
     * result.
     */
    @Transactional
    public TestRunEntity settleUnknown(UUID runId, String note) {
        TestRunEntity row = runs.findById(runId).orElseThrow(
                () -> new IllegalStateException("TEST_RUN row missing for run " + runId));
        row.setStatus("UNKNOWN");
        row.setFinishedAt(Instant.now());
        row.setErrorMessage(note);
        return row;
    }

    /** One bulk UPDATE for rows a restart left in flight; see RunReconciler. */
    @Transactional
    public int settleStale(Instant cutoff, String note) {
        return runs.settleStale(cutoff, Instant.now(), note);
    }

    // ------------------------------------------------------------- internals

    /**
     * The payload as it will be STORED: credentials out of the step texts,
     * run.log out of `output`, everything else verbatim.
     *
     * Verbatim matters because describe() deserialises this straight back and
     * publishes it under "executorResult" — so every key the executor sends
     * survives, including ones a future executor release adds, which a
     * rebuild-from-TEST_RUN_STEP approach would silently drop.
     */
    private static Map<String, Object> storable(Map<String, Object> fresh) {
        Map<String, Object> copy = new LinkedHashMap<>(fresh);

        // replace(), not put(): replace only touches a key that is already
        // there, so a payload without `output` does not GAIN the key and
        // change shape. LinkedHashMap keeps the original position either way.
        //
        // Why null it at all: the executor fills `output` with the ENTIRE
        // run.log once a run finishes. That log is already an artifact on a
        // persistent volume, served by the proxy and linked in the UI as
        // "Günlük". Storing it here would put a blob in the database and
        // multiply the history listing's size by the log size.
        copy.replace("output", null);

        Object steps = copy.get("steps");
        if (steps instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            for (Object element : list) {
                redacted.add(element instanceof Map<?, ?> step ? redactStep(step) : element);
            }
            copy.put("steps", redacted);
        }
        return copy;
    }

    /**
     * A step's `text` IS the Gherkin line with its arguments, so the login
     * step's text carries the cif and the userid here too. Redacting only
     * TEST_RUN.feature would have looked complete and leaked through
     * last_status_json and TEST_RUN_STEP.step_text.
     */
    private static Map<String, Object> redactStep(Map<?, ?> step) {
        Map<String, Object> copy = new LinkedHashMap<>();
        step.forEach((key, value) -> copy.put(String.valueOf(key), value));
        if (copy.get("text") instanceof String text) {
            copy.put("text", Redactor.redactStepText(text));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> storable) {
        Object steps = storable.get("steps");
        if (!(steps instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> step) {
                out.add((Map<String, Object>) step);
            }
        }
        return out;
    }

    private void replaceSteps(UUID runId, List<Map<String, Object>> timeline) {
        // One DELETE, flushed, then one batch of INSERTs. The derived
        // deleteAllByRunId would SELECT then delete row by row.
        steps.deleteByRunId(runId);
        if (timeline.isEmpty()) {
            return;
        }

        List<TestRunStepEntity> rows = new ArrayList<>(timeline.size());
        for (int index = 0; index < timeline.size(); index++) {
            Map<String, Object> step = timeline.get(index);
            rows.add(new TestRunStepEntity(
                    runId,
                    index,
                    clamp(asString(step.get("keyword")), KEYWORD_LENGTH),
                    asString(step.get("text")),          // already redacted
                    clamp(asString(step.get("status")), STATUS_LENGTH),
                    asLong(step.get("durationMs")),
                    asString(step.get("error"))));
        }
        steps.saveAll(rows);
    }

    /**
     * Never returns null and never returns something too long for the column.
     *
     * status is NOT NULL, so an executor payload with no status must not wipe
     * what we already knew. And clamping rather than failing is the same
     * reasoning that made this a varchar instead of an enum: an unexpected
     * value must never turn the history page into a 500.
     */
    private static String statusOf(Map<String, Object> fresh, String current) {
        String value = asString(fresh.get("status"));
        if (value == null || value.isBlank()) {
            return current != null ? current : "UNKNOWN";
        }
        return clamp(value.trim(), STATUS_LENGTH);
    }

    /**
     * The executor sends `error` as an ErrorDetail object ({type, message,
     * exitCode}), so the message is one level down. Falls back to the type and
     * then to the whole thing, because an error we cannot parse is still worth
     * recording — losing it is how a FAILED row ends up explaining nothing.
     */
    private static String errorMessageOf(Object error) {
        if (error == null) {
            return null;
        }
        if (error instanceof Map<?, ?> detail) {
            String message = asString(detail.get("message"));
            if (message != null && !message.isBlank()) {
                return message;
            }
            String type = asString(detail.get("type"));
            return type != null && !type.isBlank() ? type : null;
        }
        return asString(error);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String text ? text : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    /** Jackson hands back Integer or Long depending on magnitude, and a
     *  hand-written payload could carry a numeric string. */
    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String clamp(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** A blank app/flow/intent is absence, and absence belongs in the column
     *  as NULL — otherwise "" and null both mean "no screen named" and every
     *  future query has to test for both. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
