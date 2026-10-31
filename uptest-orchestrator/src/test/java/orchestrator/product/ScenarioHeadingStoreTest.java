package orchestrator.product;

import orchestrator.persistence.MainMenuRepository;
import orchestrator.persistence.ScenarioHeadingRepository;
import orchestrator.persistence.ScenarioRepository;
import orchestrator.persistence.ScreenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Başlık" in the store: a heading belongs to ONE screen, a name is the same
 * name with case and I/İ/ı ignored, and an id that does not belong to the
 * scenario's screen reads as başlıksız.
 *
 * @DataJpaTest, like TestRunRepositoryTest: an in-memory H2 rolled back per
 * test, and only the JPA slice -- never data/uptest.mv.db. The store is
 * built by hand rather than @Import-ed: the test transaction already wraps
 * every call, and an @Import makes Spring re-read this class as a classpath
 * resource, which fails under a checkout path holding a non-ASCII letter.
 */
@DataJpaTest
class ScenarioHeadingStoreTest {

    @Autowired
    private MainMenuRepository menuRows;

    @Autowired
    private ScreenRepository screenRows;

    @Autowired
    private ScenarioRepository scenarioRows;

    @Autowired
    private ScenarioHeadingRepository headingRows;

    private ScenarioStore store;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void menuWithTwoScreens() {
        store = new ScenarioStore(menuRows, screenRows, scenarioRows, headingRows, new ObjectMapper());
        store.createMenu("ngc", "odemeler", "Ödemeler", "Ödemeler", "");
        store.addScreen("ngc", "odemeler", "alici-listesi", "Alıcı Listesi İşlemleri");
        store.addScreen("ngc", "odemeler", "fatura", "Fatura");
    }

    private ScenarioStore.Stored scenario(String flow, String file, UUID heading) {
        ScenarioFields fields = new ScenarioFields("Senaryo " + file, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "");
        return store.createScenario("ngc", "odemeler", flow, file, fields, null, null, heading)
                .orElseThrow();
    }

    private UUID heading(String flow, String name) {
        return store.addHeading("ngc", "odemeler", flow, name).orElseThrow().heading().id();
    }

    @Test
    @DisplayName("the same name in another case or with I/İ/ı is the same heading")
    void namesFoldTurkishCase() {
        ScenarioStore.HeadingAdded first = store.addHeading("ngc", "odemeler", "alici-listesi", "Fatura İptal")
                .orElseThrow();
        ScenarioStore.HeadingAdded upper = store.addHeading("ngc", "odemeler", "alici-listesi", "FATURA IPTAL")
                .orElseThrow();
        ScenarioStore.HeadingAdded dotless = store.addHeading("ngc", "odemeler", "alici-listesi", "fatura  ıptal")
                .orElseThrow();

        assertThat(first.existed()).isFalse();
        assertThat(upper.existed()).isTrue();
        assertThat(dotless.existed()).isTrue();
        assertThat(upper.heading().id()).isEqualTo(first.heading().id());
        assertThat(dotless.heading().name()).isEqualTo("Fatura İptal");
        assertThat(store.headings("ngc", "odemeler")).hasSize(1);
    }

    @Test
    @DisplayName("a name is per screen: another screen gets its own heading")
    void headingsBelongToAScreen() {
        UUID here = heading("alici-listesi", "Fatura Oluştur");
        UUID there = heading("fatura", "Fatura Oluştur");

        assertThat(here).isNotEqualTo(there);
        assertThat(store.headings("ngc", "odemeler"))
                .extracting(ScenarioStore.Heading::flow)
                .containsExactlyInAnyOrder("alici-listesi", "fatura");
    }

    @Test
    @DisplayName("headings are listed in the order they were made")
    void creationOrder() throws InterruptedException {
        heading("alici-listesi", "Zeta");
        Thread.sleep(5);
        heading("alici-listesi", "Alfa");

        assertThat(store.headings("ngc", "odemeler"))
                .extracting(ScenarioStore.Heading::name)
                .containsExactly("Zeta", "Alfa");
    }

    @Test
    @DisplayName("a scenario is created under a heading and assigned to another or to none")
    void assignAndClear() {
        UUID one = heading("alici-listesi", "Bir");
        UUID two = heading("alici-listesi", "İki");
        ScenarioStore.Stored created = scenario("alici-listesi", "01-a.feature", one);
        assertThat(created.headingId()).isEqualTo(one);

        assertThat(store.assignHeading("ngc", "odemeler", created.path(), two).refused()).isNull();
        assertThat(store.scenario("ngc", "odemeler", created.path()).orElseThrow().headingId()).isEqualTo(two);

        assertThat(store.assignHeading("ngc", "odemeler", created.path(), null).refused()).isNull();
        assertThat(store.scenario("ngc", "odemeler", created.path()).orElseThrow().headingId()).isNull();
    }

    @Test
    @DisplayName("another screen's heading is refused, and ignored on create")
    void foreignHeadingRefused() {
        UUID foreign = heading("fatura", "Yabancı");
        ScenarioStore.Stored created = scenario("alici-listesi", "01-a.feature", foreign);

        assertThat(created.headingId()).isNull();
        assertThat(store.assignHeading("ngc", "odemeler", created.path(), foreign).refused())
                .isEqualTo("Bu ekranda böyle bir başlık yok");
    }

    @Test
    @DisplayName("a moved scenario arrives başlıksız")
    void moveClearsHeading() {
        UUID one = heading("alici-listesi", "Bir");
        ScenarioStore.Stored created = scenario("alici-listesi", "01-a.feature", one);

        ProductService.Created moved = store.move("ngc", "odemeler", created.path(), "odemeler", "fatura");
        em.flush();

        assertThat(moved.refused()).isNull();
        assertThat(store.scenario("ngc", "odemeler", moved.key()).orElseThrow().headingId()).isNull();
    }

    @Test
    @DisplayName("an id whose heading is gone reads as başlıksız, in the list and alone")
    void danglingIdReadsAsNone() {
        UUID one = heading("alici-listesi", "Bir");
        ScenarioStore.Stored created = scenario("alici-listesi", "01-a.feature", one);
        headingRows.deleteById(one);
        em.flush();
        em.clear();

        assertThat(store.scenario("ngc", "odemeler", created.path()).orElseThrow().headingId()).isNull();
        assertThat(store.scenarios("ngc", "odemeler")).singleElement()
                .extracting(ScenarioStore.Stored::headingId).isNull();
    }
}
