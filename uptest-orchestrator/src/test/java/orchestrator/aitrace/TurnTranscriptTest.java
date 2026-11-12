package orchestrator.aitrace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn read back as one text.
 *
 * Written through a real {@link TraceSession} onto a real disk, not from a
 * hand-built JSON: the whole point of this view is that it shows what was
 * ACTUALLY stored, so a test that invents the stored shape would pass while
 * the panel showed nothing.
 */
class TurnTranscriptTest {

    @TempDir
    Path dir;

    private TraceSessionTest.FakeSink sink;

    /** 4.102 characters of rules, as a late walk sends them every single turn. */
    private static final String SYSTEM_TEXT = "You carry out ONE test scenario. Bu bir banka.";
    private static final String USER_TEXT = "Giriş yapıldı. Kullanıcı sifresi123 ile girdi.";
    private static final String PAGE = "### Page state\n- button \"Fatura İptal Et\" [ref=e8]";
    private static final String DEFS = "[{\"name\":\"browser_click\",\"description\":\"tıklar\"}]";

    @BeforeEach
    void setUp() {
        sink = new TraceSessionTest.FakeSink(new TraceFiles(dir, new ObjectMapper()));
    }

    private TraceSession walk(String... secrets) {
        return new TraceSession(UUID.randomUUID(), "ai-run",
                new AiTraces.Meta().title("Firma Fatura/01.feature").secrets(List.of(secrets)), null, sink);
    }

    /** A turn shaped like a real one: rules, tools, instruction, answer, page. */
    private static TurnRecord fullTurn() {
        return new TurnRecord(Instant.parse("2026-11-12T11:32:07Z"), 2140, "qwen3-6",
                24312, 118, null, 7L, "tool_calls",
                List.of(TurnRecord.Msg.of("system", SYSTEM_TEXT),
                        TurnRecord.Msg.of("user", USER_TEXT),
                        TurnRecord.Msg.of("assistant", "browser_click çağırıyorum"),
                        new TurnRecord.Msg("tool", PAGE, "browser_click", "c1")),
                DEFS, 9, "Fatura İptal Et düğmesine basıyorum", "kısa düşünce",
                List.of(new TurnRecord.Call("c2", "browser_type", "{\"target\":\"e15\",\"text\":\"955231\"}")),
                false, null);
    }

    private String transcriptOf(TraceSession session, int seq) {
        return TurnTranscript.of(sink.files, session.id, seq).orElseThrow();
    }

    @Test
    @DisplayName("every part is there, in the order the model read it")
    void everyPartInTheModelsOrder() {
        TraceSession session = walk();
        session.turn(fullTurn());

        String text = transcriptOf(session, 1);

        assertThat(text).contains("TUR 1").contains("model qwen3-6")
                .contains("girdi 24.312").contains("çıktı 118 token")
                .contains("bitiş: tool_calls").contains("4 mesaj").contains("9 tool tanımı");
        /* The order is the claim: system, then the definitions the template
           inserts after it, then the instruction, the model's own last answer
           and the page it got back. */
        assertThat(text.indexOf("[1] SYSTEM"))
                .isLessThan(text.indexOf("TOOL TANIMLARI"));
        assertThat(text.indexOf("TOOL TANIMLARI"))
                .isLessThan(text.indexOf("[2] KULLANICI"));
        assertThat(text.indexOf("[2] KULLANICI"))
                .isLessThan(text.indexOf("[3] MODEL"));
        assertThat(text.indexOf("[3] MODEL"))
                .isLessThan(text.indexOf("[4] TOOL SONUCU: browser_click"));
        assertThat(text).contains("(mesaj değil: isteğin kendi tools alanı");
    }

    @Test
    @DisplayName("no text is cut — a snapshot is shown whole, because that is the question")
    void nothingIsCut() {
        TraceSession session = walk();
        session.turn(fullTurn());

        String text = transcriptOf(session, 1);

        assertThat(text).contains(SYSTEM_TEXT).contains(PAGE).contains(DEFS)
                .doesNotContain("…");
    }

    @Test
    @DisplayName("what came back is there too: the answer, the reasoning and the calls it asked for")
    void theOutgoingHalfIsThere() {
        TraceSession session = walk();
        session.turn(fullTurn());

        String text = transcriptOf(session, 1);
        int boundary = text.indexOf("BU TURDAN ÇIKAN");

        assertThat(boundary).isGreaterThan(0);
        assertThat(text.substring(boundary))
                .contains("MODELİN CEVABI").contains("Fatura İptal Et düğmesine basıyorum")
                .contains("MODELİN DÜŞÜNCESİ (reasoning)").contains("kısa düşünce")
                .contains("İSTEDİĞİ TOOL ÇAĞRILARI (1)")
                .contains("browser_type {\"target\":\"e15\",\"text\":\"955231\"}");
    }

    @Test
    @DisplayName("each part carries its own size, so the expensive one is visible")
    void eachPartCarriesItsSize() {
        TraceSession session = walk();
        session.turn(fullTurn());

        String text = transcriptOf(session, 1);

        /* FakeSink's tokenizer answers one token per character, so a part's
           token figure equals its character figure -- which is what makes
           "both numbers are printed" readable in one assertion. */
        assertThat(text).contains(SYSTEM_TEXT.length() + " karakter")
                .contains(SYSTEM_TEXT.length() + " token");
    }

    @Test
    @DisplayName("a message sent in an earlier turn is not marked YENİ in this one")
    void onlyFreshMessagesAreMarkedNew() {
        TraceSession session = walk();
        session.turn(fullTurn());
        session.turn(fullTurn());

        String second = transcriptOf(session, 2);

        assertThat(second).contains("[1] SYSTEM").doesNotContain("YENİ");
        assertThat(transcriptOf(session, 1)).contains("YENİ");
    }

    @Test
    @DisplayName("the password masked on its way to disk stays masked here")
    void secretsStayMasked() {
        TraceSession session = walk("sifresi123");
        session.turn(fullTurn());

        String text = transcriptOf(session, 1);

        assertThat(text).doesNotContain("sifresi123").contains(SecretMask.MASK);
    }

    @Test
    @DisplayName("a turn with no tools and no answer still reads, and says so rather than leaving a gap")
    void aBareTurnStillReads() {
        TraceSession session = walk();
        session.turn(new TurnRecord(Instant.now(), 90, "qwen3-6", 10, 0, null, null, "stop",
                List.of(TurnRecord.Msg.of("user", "merhaba")), null, 0, null, null, List.of(), false, null));

        String text = transcriptOf(session, 1);

        assertThat(text).contains("[1] KULLANICI").contains("merhaba")
                .doesNotContain("TOOL TANIMLARI")
                .contains("MODELİN CEVABI")
                .contains("(metin yok — model yalnız tool çağrısı istedi")
                .contains("İSTEDİĞİ TOOL ÇAĞRILARI (0)").contains("(yok)");
    }

    @Test
    @DisplayName("a turn that was never written has no transcript, instead of an empty one")
    void anUnknownTurnIsAbsent() {
        TraceSession session = walk();
        session.turn(fullTurn());

        assertThat(TurnTranscript.of(sink.files, session.id, 2)).isEmpty();
        assertThat(TurnTranscript.of(sink.files, UUID.randomUUID(), 1)).isEmpty();
    }

    @Test
    @DisplayName("the preview of a stored text is its opening, unmarked, and it is not read whole")
    void previewIsTheOpeningOnly() {
        TraceSession session = walk();
        session.turn(fullTurn());
        String sha = TraceFiles.sha(PAGE);

        assertThat(sink.files.preview(session.id, sha, 10)).hasValue(PAGE.substring(0, 10));
        assertThat(sink.files.preview(session.id, sha, 10_000)).hasValue(PAGE);
        assertThat(sink.files.preview(session.id, "not-a-sha", 10)).isEmpty();
        assertThat(sink.files.preview(session.id, TraceFiles.sha("never stored"), 10)).isEmpty();
    }
}
