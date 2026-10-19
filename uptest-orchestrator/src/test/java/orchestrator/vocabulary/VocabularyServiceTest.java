package orchestrator.vocabulary;

import orchestrator.executor.ExecutorClient;
import orchestrator.executor.ExecutorPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The vocabulary cache's two jobs: pick up a redeployed executor without
 * being told, and never make an outage worse than it is.
 *
 * It used to be cached for the life of the process. That is wrong because the
 * executor and the orchestrator restart SEPARATELY, and the failure is
 * silent: the validator keeps working, the prompt keeps working, and the only
 * symptom is a model that never uses a step it cannot see. It cost two
 * debugging rounds in one session before anyone looked at the cache.
 */
class VocabularyServiceTest {

    private static final String ONE_STEP = """
            {"steps":[{"pattern":"click {string}","keyword":"And",
                       "parameters":["string"],"className":"CommonSteps","method":"click"}]}
            """;

    private static final String TWO_STEPS = """
            {"steps":[{"pattern":"click {string}","keyword":"And",
                       "parameters":["string"],"className":"CommonSteps","method":"click"},
                      {"pattern":"upload {string} {string}","keyword":"And",
                       "parameters":["string","string"],"className":"CommonSteps","method":"upload"}]}
            """;

    /** Answers the given JSON in order; the last answer repeats thereafter. */
    private static ExecutorClient executorSaying(String first, String... then) {
        ExecutorClient executor = mock(ExecutorClient.class);
        var stub = when(executor.vocabularyJson()).thenReturn(first);
        for (String answer : then) {
            stub = stub.thenReturn(answer);
        }
        return executor;
    }

    private static VocabularyService service(ExecutorClient executor, Duration ttl, Duration retry) {
        ExecutorPool pool = mock(ExecutorPool.class);
        when(pool.all()).thenReturn(List.of(executor));
        return new VocabularyService(pool, ttl, retry);
    }

    @Test
    @DisplayName("inside the TTL the executor is asked once, not once per call")
    void cachesWithinTheTtl() {
        ExecutorClient executor = executorSaying(ONE_STEP);
        VocabularyService vocabulary = service(executor, Duration.ofMinutes(5), Duration.ofSeconds(15));

        for (int i = 0; i < 5; i++) {
            assertThat(vocabulary.get()).isPresent();
        }

        // Validation runs on a 700 ms debounce while someone types; a fetch
        // per call would put a network round trip in front of every keystroke.
        verify(executor, times(1)).vocabularyJson();
    }

    @Test
    @DisplayName("once the TTL passes, a redeployed executor is picked up")
    void refetchesWhenStale() throws InterruptedException {
        ExecutorClient executor = executorSaying(ONE_STEP, TWO_STEPS);
        VocabularyService vocabulary = service(executor, Duration.ofMillis(50), Duration.ofSeconds(15));

        assertThat(vocabulary.get().orElseThrow().has("upload {string} {string}")).isFalse();
        Thread.sleep(250);

        // Nobody poked anything: the new step arrives because the cache
        // expired, which is the whole point.
        assertThat(vocabulary.get().orElseThrow().has("upload {string} {string}")).isTrue();
    }

    @Test
    @DisplayName("a failed refresh keeps the vocabulary it already had")
    void staleBeatsAbsent() throws InterruptedException {
        // Cast required: a bare null binds to the whole varargs array, not to
        // a one-element array holding null, so the helper would see no answers
        // at all rather than one silent executor.
        ExecutorClient executor = executorSaying(ONE_STEP, (String) null);
        VocabularyService vocabulary = service(executor, Duration.ofMillis(50), Duration.ofMillis(50));

        assertThat(vocabulary.get()).isPresent();
        Thread.sleep(250);

        // The executor is restarting. Dropping the list would turn that into
        // "doğrulama yapılamıyor" on a screen that was fine a second ago --
        // and the step list changes on a deploy, not on a whim.
        assertThat(vocabulary.get()).isPresent();
    }

    @Test
    @DisplayName("a failed refresh backs off instead of retrying on every call")
    void backsOffAfterFailure() {
        ExecutorClient executor = executorSaying((String) null);
        VocabularyService vocabulary = service(executor, Duration.ofMinutes(5), Duration.ofMinutes(5));

        for (int i = 0; i < 5; i++) {
            assertThat(vocabulary.get()).isEmpty();
        }

        // Without the back-off, executors being down would add a blocking
        // call to every validation: the outage would become a hang.
        verify(executor, times(1)).vocabularyJson();
    }

    @Test
    @DisplayName("the manual refresh ignores the clock")
    void manualRefreshAlwaysFetches() {
        ExecutorClient executor = executorSaying(ONE_STEP, TWO_STEPS);
        VocabularyService vocabulary = service(executor, Duration.ofMinutes(5), Duration.ofSeconds(15));

        assertThat(vocabulary.get()).isPresent();
        // An operator pressing refresh means "now", not "when you were going
        // to anyway".
        assertThat(vocabulary.refresh().orElseThrow().has("upload {string} {string}")).isTrue();
        verify(executor, times(2)).vocabularyJson();
    }
}
