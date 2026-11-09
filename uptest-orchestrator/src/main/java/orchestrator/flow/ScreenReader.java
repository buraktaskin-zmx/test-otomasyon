package orchestrator.flow;

import orchestrator.flow.FlowModel.Edge;
import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Node;
import orchestrator.llm.LlmOutputCleaner;
import orchestrator.llm.VisionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stage 1: a screenshot in, a CATALOG DRAFT out — one model call per screen.
 *
 * <p><b>One call per screen, never all of them at once.</b> A screenshot costs
 * one to three thousand tokens, so four screens in one turn is a bloated
 * context for no gain; a misread on the third screen would poison the other
 * three; and separate calls run in parallel. It is the same reasoning that
 * makes a scenario batch N separate documents.
 *
 * <p><b>The YAML is rendered by code, not by the model.</b> The MCP agent
 * taught this: asked for "YAML and nothing else" it wrote pages of prose
 * first, and the fix was to make the catalog a structured call whose shape is
 * enforced by a schema. Here the model answers JSON, this class validates it
 * and writes the YAML, so a malformed answer costs one screen rather than an
 * unparseable catalog.
 *
 * <p>What comes out is a DRAFT. It is stored beside the screenshot and only
 * becomes a catalog once a person has read it — the rule every catalog
 * producer in this project obeys, and the vision model needs it most: it
 * guesses types from appearance and cannot see a closed dropdown's options.
 */
@Service
public class ScreenReader {

    /** The five types a step can drive, as catalogs/SCHEMA.md lists them. */
    private static final List<String> TYPES =
            List.of("text", "date", "amount", "dropdown", "account_picker", "checkbox");

    private final VisionClient vision;
    private final FlowStore store;
    private final ObjectMapper mapper;
    private final int concurrency;

    public ScreenReader(VisionClient vision, FlowStore store, ObjectMapper mapper,
                        @Value("${llm.batch-concurrency:4}") int concurrency) {
        this.vision = vision;
        this.store = store;
        this.mapper = mapper;
        this.concurrency = Math.max(1, concurrency);
    }

    /** One screen's result: the YAML that was written, or why it was not. */
    public record Read(String nodeId, String title, String yaml, String error, List<String> problems) {
    }

    /**
     * Reads every node that has a screenshot, in parallel.
     *
     * A node whose read fails becomes a {@link Read} with an error rather than
     * killing the batch — the same choice {@code awaitOne} makes for scenarios.
     */
    public List<Read> readAll(String app, String product, Flow flow) {
        return readAll(app, product, flow, false);
    }

    /**
     * @param onlyMissing skip the nodes already read. Planning calls it this
     *                    way: it needs every screen read, but a screenshot
     *                    that has not changed costs one to three thousand
     *                    vision tokens to read again for the same answer, and
     *                    an analyst presses "Planla" repeatedly while editing
     *                    the plan. A re-read is asked for explicitly instead,
     *                    per node, where the analyst can see what it said.
     */
    public List<Read> readAll(String app, String product, Flow flow, boolean onlyMissing) {
        List<Node> withImage = flow.nodes().stream()
                .filter(node -> store.screenshotCount(app, product, node.id()) > 0)
                .filter(node -> !onlyMissing || store.read(app, product, node.id()).isEmpty())
                .toList();
        if (withImage.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, withImage.size()));
        try {
            List<Future<Read>> futures = new ArrayList<>();
            for (Node node : withImage) {
                Callable<Read> job = () -> readOne(app, product, flow, node);
                futures.add(pool.submit(job));
            }
            List<Read> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Node node = withImage.get(i);
                try {
                    out.add(futures.get(i).get());
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    out.add(new Read(node.id(), node.title(), null,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage(), List.of()));
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * One named screen, read again whether or not it has been read before.
     *
     * The only way to spend vision tokens on a screen deliberately, which is
     * what the analyst wants after replacing a screenshot or correcting the
     * node's note — and the reason {@link #readAll} may skip what it already
     * has without that becoming "the draft can never be refreshed".
     */
    public Optional<Read> readNode(String app, String product, Flow flow, String nodeId) {
        Node node = flow.node(nodeId);
        return node == null ? Optional.empty() : Optional.of(readOne(app, product, flow, node));
    }

    private Read readOne(String app, String product, Flow flow, Node node) {
        List<byte[]> images = store.screenshots(app, product, node.id());
        if (images.isEmpty()) {
            return new Read(node.id(), node.title(), null, "Bu ekranın görüntüsü yok", List.of());
        }
        String answer = LlmOutputCleaner.clean(
                vision.ask(SYSTEM, user(app, flow, node, images.size()), images));
        JsonNode json = parse(answer);
        if (json == null) {
            return new Read(node.id(), node.title(), null,
                    "Model JSON döndürmedi. Ham cevabın başı: " + head(answer), List.of());
        }
        String yaml = toYaml(app, node, json);
        store.writeRead(app, product, node.id(), yaml);
        return new Read(node.id(), node.title(), yaml, null, problems(json));
    }

    /* ------------------------------ prompts ------------------------------ */

    static final String SYSTEM = """
            Sana bir bankacılık ekranının görüntüsü veriliyor. Görevin o ekranda
            NE OLDUĞUNU yazmak; ne olması gerektiğini değil, ne yapılabileceğini
            değil. Bu okuma, test senaryosu yazacak bir sistemin sözlüğü olacak;
            uydurduğun her şey koşamayacak bir teste dönüşür.

            1. YALNIZCA GÖRÜNENİ YAZ. Görüntüde olmayan bir alanı, butonu ya da
               seçeneği ekleme. Emin değilsen alanı yaz ve "uncertain" alanına
               neden emin olmadığını yaz.
            2. ETİKETİ BİR İNSANIN OKUDUĞU GİBİ KOPYALA. İki satıra bölünmüş bir
               etiket tek boşlukla birleşir: "Fatura Sorgulama Tarih Kriteri".
               Kısaltma, düzeltme, çeviri, büyük/küçük harf değiştirme yok.
            3. TİPİ ETİKETTEN TAHMİN ETME. "Fatura No" harf de kabul edebilir.
               Tip için kutunun GÖRÜNÜŞÜNE bak: açılır listenin oku, takvim
               simgesi, onay kutusu, yan yana iki küçük kutu.
            4. AÇILIR LİSTENİN SEÇENEKLERİ, LİSTE KAPALIYKEN GÖRÜNMEZ. Kapalıysa
               options'ı BOŞ bırak, seçili değeri "selected" alanına yaz ve
               uncertain'a "liste kapalı" yaz. Seçenek uydurmak, senaryonun asla
               seçemeyeceği bir değer yazmaktır.
            5. TUTAR BU UYGULAMADA İKİ KUTUDUR (lira ve kuruş). Yan yana iki
               küçük kutu TEK bir "amount" alanıdır, iki alan değil.
            6. SALT OKUNUR alanları işaretle: gri zemin, kenarlık yok, içinde
               uygulamanın doldurduğu bir değer var.
            7. Ekranın başlığını, üstteki ana menü ve alt menü adını, butonları
               ve varsa başarı/hata mesajını ayrıca yaz.

            YALNIZCA şu JSON'u döndür, başka hiçbir şey yazma:
            {
              "title": "ekranın başlığı",
              "mainMenu": "üst menüde seçili olan",
              "subMenu": "alt menüde seçili olan",
              "fields": [
                {"label":"...", "type":"text|date|amount|dropdown|account_picker|checkbox",
                 "options":["..."], "selected":"...", "readonly":false, "uncertain":""}
              ],
              "buttons": ["..."],
              "message": ""
            }
            """;

    /**
     * The user turn: this screen's name, the analyst's note, and the edges
     * touching it.
     *
     * The edges matter to a READER, not just to the generator: an outgoing
     * edge labelled "Fatura Oluştur" tells the model that text is a control
     * on this screen, so a button it half-sees is confirmed rather than
     * guessed at.
     */
    /**
     * @param images how many pictures are attached. Said out loud because the
     *               model cannot otherwise tell several captures of ONE long
     *               page from several different screens -- and reading them
     *               as alternatives would produce a catalog listing the same
     *               field twice, or only the half that was in the first one.
     */
    private static String user(String app, Flow flow, Node node, int images) {
        StringBuilder sb = new StringBuilder();
        sb.append("Uygulama: ").append(app.toUpperCase(Locale.ROOT)).append('\n');
        sb.append("Bu ekranın adı (analist verdi): ").append(node.title()).append('\n');
        if (images > 1) {
            sb.append("DİKKAT: ").append(images).append(" görüntü ekli ve hepsi AYNI ekranın")
                    .append(" parçaları — sayfa tek ekran görüntüsüne sığmadığı için analist")
                    .append(" kaydırarak çekti. Sırayla yukarıdan aşağıya okunurlar. Hepsini")
                    .append(" TEK bir ekran gibi birleştir: alanları tek listede topla, iki")
                    .append(" görüntüde de görünen alanı bir kez yaz, ve bunları birbirinin")
                    .append(" alternatifi ya da ayrı ekranlar sanma.\n");
        }
        if (node.note() != null && !node.note().isBlank()) {
            sb.append("Analistin notu — görüntüde görünmeyen, bilmen gereken şey:\n")
                    .append(node.note()).append('\n');
        }
        List<Edge> in = flow.edges().stream().filter(e -> e.to().equals(node.id())).toList();
        if (in.isEmpty()) {
            sb.append("Bu ekrana çizimde hiç ok girmiyor: kullanıcı onu MENÜDEN açıyor,")
                    .append(" yani üstteki ana menü ve alt menü adını okuman önemli.\n");
        } else {
            sb.append("Bu ekrana başka bir ekrandan geliniyor — analist şu kontrole")
                    .append(" basıldığını söyledi: \"").append(in.get(0).label()).append("\".")
                    .append(" Menüden açılmıyor, bu yüzden ana/alt menü okunamıyorsa boş bırak,")
                    .append(" uydurma.\n");
        }
        List<Edge> out = flow.edges().stream().filter(e -> e.from().equals(node.id())).toList();
        if (!out.isEmpty()) {
            sb.append("Analist bu ekrandan şu kontrollerle çıkılabildiğini söyledi")
                    .append(" (bunlar bu ekranda bir buton ya da bağlantıdır):\n");
            out.forEach(edge -> sb.append("- ").append(edge.label()).append('\n'));
        }
        sb.append("\nEkranı oku ve JSON'u döndür.");
        return sb.toString();
    }

    /* ------------------------------ parsing ------------------------------ */

    private JsonNode parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        // A model that wrapped the JSON in prose: take the outermost braces.
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        try {
            return mapper.readTree(answer.substring(open, close + 1));
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String head(String text) {
        String clean = text == null ? "" : text.strip().replace("\n", " ");
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }

    /* ------------------------------- output ------------------------------ */

    /**
     * The draft as catalogs/SCHEMA.md spells it. Written here rather than by
     * the model so the shape is ours; {@code flow} is left as the node's, or
     * blank for the analyst to choose.
     */
    private static String toYaml(String app, Node node, JsonNode json) {
        StringBuilder sb = new StringBuilder();
        sb.append("app: ").append(app.toUpperCase(Locale.ROOT)).append('\n');
        sb.append("flow: ").append(quote(blank(node.flow()) ? text(json, "title") : node.flow())).append('\n');
        sb.append('\n');
        sb.append("navigation:\n");
        sb.append("  main_menu: ").append(quote(text(json, "mainMenu"))).append('\n');
        sb.append("  sub_menu: ").append(quote(text(json, "subMenu"))).append('\n');
        sb.append('\n');
        sb.append("fields:\n");
        JsonNode fields = json.get("fields");
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            sb.append("  []\n");
        }
        if (fields != null && fields.isArray()) {
            for (JsonNode field : fields) {
                String label = text(field, "label");
                if (label.isBlank()) {
                    continue;
                }
                String uncertain = text(field, "uncertain");
                if (!uncertain.isBlank()) {
                    sb.append("  # emin degil: ").append(uncertain.replace("\n", " ")).append('\n');
                }
                sb.append("  - label: ").append(quote(label)).append('\n');
                sb.append("    type: ").append(type(text(field, "type"))).append('\n');
                if (field.path("readonly").asBoolean(false)) {
                    sb.append("    readonly: true\n");
                }
                JsonNode options = field.get("options");
                if (options != null && options.isArray() && !options.isEmpty()) {
                    sb.append("    options:\n");
                    for (JsonNode option : options) {
                        sb.append("      - ").append(quote(option.asString(""))).append('\n');
                    }
                }
                String selected = text(field, "selected");
                if (!selected.isBlank()) {
                    sb.append("    # ekranda seçili olan: ").append(selected).append('\n');
                }
            }
        }
        JsonNode buttons = json.get("buttons");
        if (buttons != null && buttons.isArray() && !buttons.isEmpty()) {
            sb.append('\n').append("buttons:\n");
            for (JsonNode button : buttons) {
                sb.append("  - ").append(quote(button.asString(""))).append('\n');
            }
        }
        String message = text(json, "message");
        if (!message.isBlank()) {
            sb.append('\n').append("success_message: ").append(quote(message)).append('\n');
        }
        return sb.toString();
    }

    /**
     * What is mechanically wrong with this draft, before anyone looks — the
     * same service {@code ExploreController.problems()} does for the agent.
     */
    private static List<String> problems(JsonNode json) {
        List<String> out = new ArrayList<>();
        JsonNode fields = json.get("fields");
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            out.add("Hiç alan okunmadı — görüntü okunaksız olabilir.");
            return out;
        }
        for (JsonNode field : fields) {
            String label = text(field, "label");
            String type = text(field, "type");
            if (!TYPES.contains(type)) {
                out.add("\"" + label + "\": tanınmayan tip \"" + type + "\" — text olarak yazıldı.");
            }
            boolean choice = "dropdown".equals(type) || "account_picker".equals(type);
            JsonNode options = field.get("options");
            boolean empty = options == null || !options.isArray() || options.isEmpty();
            if (choice && empty && text(field, "uncertain").isBlank()) {
                out.add("\"" + label + "\": açılır liste ama seçeneği yok ve sebebi yazılmamış."
                        + " Liste kapalıysa ekranı listeyi açık yakalayıp tekrar okutun.");
            }
        }
        if (text(json, "mainMenu").isBlank() || text(json, "subMenu").isBlank()) {
            out.add("Ana menü ya da alt menü okunamadı — ekrana nasıl gidileceği eksik kalır.");
        }
        return out;
    }

    private static String type(String raw) {
        return TYPES.contains(raw) ? raw : "text";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString("").trim();
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** Always quoted: a label with a colon or a leading digit is not a string otherwise. */
    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
