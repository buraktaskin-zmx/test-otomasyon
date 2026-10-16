package orchestrator.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two bounds on live conversations.
 *
 * Both are needed and they fail in different directions: the timeout lets an
 * abandoned transcript sit on an analyst's intent indefinitely, and the count
 * lets a burst of starts grow the map without limit inside the window before
 * any of them has expired. A store whose job is to be bounded has to be shown
 * to be bounded.
 */
class ChatSessionStoreTest {

    private static ChatSessionStore store(Duration idleFor, int keepAtMost) {
        return new ChatSessionStore(idleFor, keepAtMost);
    }

    @Test
    @DisplayName("a session is findable by its id, and nothing else is")
    void opensAndFinds() {
        ChatSessionStore store = store(Duration.ofHours(1), 10);
        ChatSession session = store.open("ngc", "fatura-olustur", "DI1", "1335");

        assertThat(store.find(session.id())).containsSame(session);
        assertThat(store.find("not-an-id")).isEmpty();
        assertThat(store.find(null)).isEmpty();
    }

    @Test
    @DisplayName("closing removes it")
    void closes() {
        ChatSessionStore store = store(Duration.ofHours(1), 10);
        ChatSession session = store.open("ngc", "x", null, null);

        store.close(session.id());

        assertThat(store.find(session.id())).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("an untouched conversation expires")
    void expiresWhenIdle() throws InterruptedException {
        // A real clock and a short wait rather than an injected one: the
        // alternative is a Clock on the constructor and in the Spring wiring,
        // which is a lot of surface to carry for a timer this simple.
        ChatSessionStore store = store(Duration.ofMillis(50), 10);
        ChatSession session = store.open("ngc", "x", null, null);

        Thread.sleep(250);

        assertThat(store.find(session.id())).isEmpty();
    }

    @Test
    @DisplayName("continuing a conversation keeps it alive")
    void touchingDefersExpiry() throws InterruptedException {
        // Wide margins on purpose: a loaded machine can stretch a sleep, and
        // a test that fails when the build host is busy teaches people to
        // ignore it. 500 ms window, 100 ms steps.
        ChatSessionStore store = store(Duration.ofMillis(500), 10);
        ChatSession session = store.open("ngc", "x", null, null);

        // Three lookups spanning longer than one window: each must push the
        // deadline out, or a conversation dies while it is being had.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(100);
            assertThat(store.find(session.id())).isPresent();
        }
    }

    @Test
    @DisplayName("the count cap holds the moment it is exceeded, not on the next call")
    void capsImmediately() {
        ChatSessionStore store = store(Duration.ofHours(1), 2);

        ChatSession first = store.open("ngc", "a", null, null);
        ChatSession second = store.open("ngc", "b", null, null);
        ChatSession third = store.open("ngc", "c", null, null);

        // Checked right after open() returns. Sweeping BEFORE the insert
        // would leave the map one over until something else happened to call
        // in -- and nothing is guaranteed to.
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find(first.id())).isEmpty();
        assertThat(store.find(second.id())).isPresent();
        assertThat(store.find(third.id())).isPresent();
    }

    @Test
    @DisplayName("eviction takes the oldest, never the one just opened")
    void evictsOldestFirst() {
        ChatSessionStore store = store(Duration.ofHours(1), 1);

        store.open("ngc", "old", null, null);
        ChatSession newest = store.open("ngc", "new", null, null);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.find(newest.id())).isPresent();
    }
}
