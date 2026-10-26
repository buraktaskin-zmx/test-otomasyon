package orchestrator.explore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a recorded {@code browser_snapshot} back into references.
 *
 * Every fixture here is copied from a real agent walk of Firma Fatura
 * İşlemleri rather than invented, because the shapes that matter are the ones
 * this application actually renders and all of them were surprises: a form
 * control with no accessible name, a Select2 whose label sits a level above
 * the box, and a dialog row that packs label and value into one cell as two
 * sibling nodes.
 */
class AriaSnapshotTest {

    /**
     * The login form. Note what is NOT here: the textbox has no name, and the
     * label is the generic before it.
     */
    private static final String LOGIN = """
            ### Page
            - Page URL: https://ifctest.yapikredi.com.tr/ngc/entryNgc.nhnc.do
            ### Snapshot
            ```yaml
            - generic [ref=e25]:
              - generic [ref=e26]:
                - generic [ref=e27]: Firma Kodu / Müşteri Kodu
                - textbox [ref=e28]
                - generic [ref=e29] [cursor=pointer]
              - generic [ref=e30]:
                - generic [ref=e31]: Kullanıcı Kodu
                - textbox [ref=e32]
              - link "Giriş" [ref=e42] [cursor=pointer]:
                - /url: javascript:void(0)
            ```
            """;

    /** A Select2 and the detail dialog, as they come back after a row is opened. */
    private static final String DETAIL = """
            ### Snapshot
            ```yaml
            - generic [ref=e354]:
              - generic [ref=e355]: Firma Seçimi
              - combobox [ref=e539]:
                - textbox "ASAŞ ALÜMİNYUM SAN." [ref=e540]
              - combobox [aria-hidden] [ref=e360]
            - table [ref=e582]:
              - rowgroup [ref=e583]:
                - row [ref=e584]:
                  - columnheader "Bayi Kodu: activate to sort column ascending" [ref=e586] [cursor=pointer]:
                    - generic [ref=e587]: Bayi Kodu
              - rowgroup [ref=e598]:
                - row [ref=e599] [cursor=pointer]:
                  - cell [ref=e600]:
                    - radio [checked] [ref=e601]
                  - cell "81234567" [ref=e602]
                  - cell "HILL20082026-1" [ref=e603]
            - dialog [ref=e641]:
              - heading "Fatura Bilgileri" [level=1] [ref=e645]
              - table [ref=e653]:
                - rowgroup [ref=e654]:
                  - row [ref=e665]:
                    - cell "Bayi Kodu 81234567" [ref=e666]:
                      - generic [ref=e667]:
                        - generic [ref=e668]: Bayi Kodu
                        - generic [ref=e669]: "81234567"
                  - row [ref=e685]:
                    - cell "Vade Tarihi 19/10/2026" [ref=e686]:
                      - generic [ref=e687]:
                        - generic [ref=e688]: Vade Tarihi
                        - generic [ref=e689]: 19/10/2026
              - link "Kapat" [ref=e710] [cursor=pointer]
            ```
            """;

    @Test
    @DisplayName("a link answers with its accessible name")
    void readsAccessibleNames() {
        AriaSnapshot snapshot = AriaSnapshot.of(LOGIN);

        assertThat(snapshot.nameOf("e42")).isEqualTo("Giriş");
        assertThat(snapshot.node("e42")).get().extracting(AriaSnapshot.Node::role).isEqualTo("link");
    }

    /**
     * The whole reason labelOf exists. These boxes carry no name at all, so a
     * resolver that only read names would have nothing to call them.
     */
    @Test
    @DisplayName("a nameless form control is named by the sibling before it")
    void labelsAControlFromItsSibling() {
        AriaSnapshot snapshot = AriaSnapshot.of(LOGIN);

        assertThat(snapshot.nameOf("e28")).isEmpty();
        assertThat(snapshot.labelOf("e28")).isEqualTo("Firma Kodu / Müşteri Kodu");
        assertThat(snapshot.labelOf("e32")).isEqualTo("Kullanıcı Kodu");
    }

    /**
     * A Select2 renders as combobox > textbox, so the textbox has no sibling
     * at all and the label is one level up. The same climb CommonActions makes
     * to a fieldcontainer ancestor.
     */
    @Test
    @DisplayName("a Select2 is labelled from a level above, and shows its value as its name")
    void climbsToFindASelect2Label() {
        AriaSnapshot snapshot = AriaSnapshot.of(DETAIL);

        assertThat(snapshot.labelOf("e540")).isEqualTo("Firma Seçimi");
        assertThat(snapshot.nameOf("e540")).isEqualTo("ASAŞ ALÜMİNYUM SAN.");
    }

    /**
     * The dialog's shape, and the one a verify had to learn to read: label and
     * value are siblings inside a single cell whose own name is both of them
     * run together.
     */
    @Test
    @DisplayName("a dialog row gives up its value from beside the label")
    void readsAValueBesideItsLabel() {
        AriaSnapshot snapshot = AriaSnapshot.of(DETAIL);

        assertThat(snapshot.nameOf("e666")).isEqualTo("Bayi Kodu 81234567");
        assertThat(snapshot.valueBeside("e668")).isEqualTo("81234567");
        assertThat(snapshot.valueBeside("e688")).isEqualTo("19/10/2026");
    }

    /**
     * The dump quotes a value it would otherwise read as something else.
     * Keeping the quotes would put them into a Gherkin argument and compare
     * them against a screen that does not show them.
     */
    @Test
    @DisplayName("a quoted value loses its quotes")
    void unquotesAmbiguousValues() {
        assertThat(AriaSnapshot.of(DETAIL).node("e669"))
                .get().extracting(AriaSnapshot.Node::text).isEqualTo("81234567");
    }

    /**
     * Measured, not feared: "Bayi Kodu" is on this screen twice — once as a
     * sortable column header, once inside the dialog — which is why a verify
     * scoped to the page picked the wrong one.
     */
    @Test
    @DisplayName("the same caption really does appear twice on one screen")
    void findsTheAmbiguityThatBrokeVerify() {
        long matches = AriaSnapshot.of(DETAIL).nodes().stream()
                .filter(node -> "Bayi Kodu".equals(node.caption()))
                .count();

        assertThat(matches).isEqualTo(2);
    }

    /**
     * A page with an iframe numbers its references by frame. The first walk
     * produced only {@code eNN} and a later one produced {@code f1eNNN}, so
     * matching the whole token rather than {@code e\\d+} is load-bearing.
     */
    @Test
    @DisplayName("a frame-prefixed reference is read like any other")
    void readsFramePrefixedReferences() {
        AriaSnapshot snapshot = AriaSnapshot.of("""
                ### Snapshot
                ```yaml
                - generic [ref=f1e718]: Bayi Kodu
                - generic [ref=f1e719]: 151732
                ```
                """);

        assertThat(snapshot.node("f1e719")).isPresent();
        assertThat(snapshot.valueBeside("f1e718")).isEqualTo("151732");
    }

    /**
     * YAML wraps a scalar it would otherwise read as something else, and every
     * sortable column header is in that shape because its accessible name
     * contains a colon. Unwrapped, the node is dropped and its child attaches
     * to the GRANDparent — which made all six headers siblings and had
     * "Bayi Kodu" report the header beside it as its value.
     */
    @Test
    @DisplayName("a single-quoted line is read like any other, and keeps the tree's shape")
    void readsQuotedLines() {
        AriaSnapshot snapshot = AriaSnapshot.of("""
                ### Snapshot
                ```yaml
                - row [ref=e1]:
                  - 'columnheader "Bayi Kodu: activate to sort column ascending" [ref=e2]':
                    - generic [ref=e3]: Bayi Kodu
                  - 'columnheader "Fatura No: activate to sort column ascending" [ref=e4]':
                    - generic [ref=e5]: Fatura No
                ```
                """);

        assertThat(snapshot.node("e2")).get()
                .extracting(AriaSnapshot.Node::role).isEqualTo("columnheader");
        assertThat(snapshot.valueBeside("e3")).isEmpty();
        assertThat(snapshot.labelledValues()).doesNotContainKey("Bayi Kodu");
    }

    /**
     * Results that are not snapshots reach this class constantly — refusal
     * sentences, errors, a click's one-line confirmation. Losing a whole walk
     * because one entry is not a tree would be the wrong trade.
     */
    @Test
    @DisplayName("anything that is not a tree reads as an empty one")
    void toleratesResultsThatAreNotSnapshots() {
        assertThat(AriaSnapshot.of("The application is already open").isEmpty()).isTrue();
        assertThat(AriaSnapshot.of(null).isEmpty()).isTrue();
        assertThat(AriaSnapshot.of("").isEmpty()).isTrue();
        assertThat(AriaSnapshot.of("### Snapshot\n```yaml\n```\n").isEmpty()).isTrue();
    }
}
