package orchestrator.flow;

import orchestrator.flow.FlowModel.Flow;
import orchestrator.flow.FlowModel.Intent;
import orchestrator.product.Rule;

import java.util.List;
import java.util.Map;

/**
 * The two user turns this pipeline adds: plan the scenarios, then write one.
 *
 * <p>Kept here rather than in {@code PromptBuilder} for two reasons. That
 * class is already at the size this project splits things at, and these turns
 * belong to one pipeline — reading them beside {@link FlowPlanner} and
 * {@link FlowSuggestService} is how anyone will work on them. The SYSTEM turn
 * is still {@code PromptBuilder.system(vocabulary)}, unchanged: the step
 * vocabulary and the eleven hard rules are shared by every generator here.
 *
 * <p>The scenario turn follows {@code fromWalk}'s technique — it CANCELS the
 * system rules a drawn flow contradicts, by name. A contradiction between two
 * prompt rules is not settled by adding a third one somewhere else; it is
 * settled where the context is known, and only this turn knows this is a
 * drawing.
 */
final class FlowPrompts {

    private FlowPrompts() {
    }

    /* ------------------------------ planning ------------------------------ */

    static final String PLAN_SYSTEM = """
            Bir test analistinin yerine düşünüyorsun. Sana bir uygulamanın
            ekranları, aralarındaki geçişler, her ekranda okunan alanlar ve
            ürünün kuralları veriliyor.

            Görevin senaryoları YAZMAK DEĞİL, hangi senaryoların yazılacağına
            karar vermek. Her biri tek satırlık bir niyet. Bu liste bir insana
            gösterilecek; o silecek, ekleyecek, sonra senaryolar yazılacak.

            YALNIZCA şu JSON'u döndür, başka hiçbir şey yazma:
            {"scenarios":[
              {"title":"kısa Türkçe başlık",
               "intent":"bir cümle: ne yapılıyor ve ne bekleniyor",
               "path":["ekran kimliği","..."],
               "ruleIds":["R-..."],
               "negative":false}
            ]}
            """;

    /**
     * @param count how many intents are wanted — the analyst's number, and a
     *              real constraint: the model happily writes eighty
     */
    static String plan(String app, Flow flow, Map<String, String> reads, List<Rule> rules,
                       List<List<String>> paths, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("Uygulama: ").append(app.toUpperCase()).append('\n');
        sb.append(FlowModel.describe(flow, reads)).append('\n');

        sb.append("EKRAN KİMLİKLERİ (path alanında bunları kullan):\n");
        flow.nodes().forEach(node ->
                sb.append("- ").append(node.id()).append(" = ").append(node.title()).append('\n'));

        sb.append("\nÇİZİLEN YOLLAR (kökten yaprağa):\n");
        if (paths.isEmpty()) {
            sb.append("  (tek ekran; yol yok)\n");
        }
        paths.forEach(path -> sb.append("- ").append(String.join(" → ", path))
                .append("   [").append(FlowModel.pathTitle(flow, path)).append("]\n"));

        sb.append('\n').append(rulesBlock(rules));
        sb.append(freeTextBlocks(flow));

        if (notBlank(flow.intent())) {
            sb.append("\nANALİSTİN AMACI — öncelik bu:\n").append(flow.intent().trim()).append('\n');
        }
        if (notBlank(flow.outOfScope())) {
            sb.append("\nKAPSAM DIŞI — bunlara hiç senaryo yazma:\n")
                    .append(flow.outOfScope().trim()).append('\n');
        }

        sb.append("""

                KURALLAR:
                - Tam olarak %d niyet yaz. Daha fazla değil.
                - Her çizilen yol için en az bir "mutlu yol" niyeti; path alanına
                  o yolun ekran kimliklerini sırayla yaz.
                - Her kural için onu sınayan bir niyet. REDDETME kuralları (bir
                  şeyin olMAması gerektiğini söyleyenler) en değerlileridir;
                  onları negative=true ile işaretle.
                - Tek ekranda kalan bir niyetin path'i tek elemanlıdır.
                - KAPSAM DIŞI listesindeki hiçbir şeye niyet yazma.
                - Var olduğu söylenmemiş veriye dayanan niyet yazma. Ortamda
                  hangi firmanın, faturanın, bayinin bulunduğunu bilmiyorsan o
                  senaryoyu isteme.
                - Aynı şeyi iki kez isteme.
                """.formatted(count));
        return sb.toString();
    }

    /* ------------------------------ writing ------------------------------ */

    /**
     * One scenario from one planned intent.
     *
     * @param catalogYaml the single screen's catalog when the path is one
     *                    screen and it has one, else null — see
     *                    {@code SuggestService.suggestFromDrawing}
     */
    static String scenario(String app, Flow flow, Map<String, String> reads, Intent intent,
                           List<Rule> rules, String catalogYaml, String testData) {
        StringBuilder sb = new StringBuilder(WALK_RULES);

        sb.append("\nUygulama: ").append(app.toUpperCase()).append('\n');
        sb.append("\nBU SENARYONUN GEÇTİĞİ YOL:\n  ")
                .append(FlowModel.pathTitle(flow, intent.path())).append('\n');

        sb.append("\nYOLDAKİ GEÇİŞLER — her okun etiketi, o geçişi yapan kontroldür:\n");
        boolean any = false;
        for (int i = 0; i + 1 < intent.path().size(); i++) {
            FlowModel.Edge edge = flow.between(intent.path().get(i), intent.path().get(i + 1));
            if (edge != null) {
                FlowModel.Node from = flow.node(edge.from());
                FlowModel.Node to = flow.node(edge.to());
                sb.append("- ").append(from == null ? "?" : from.title())
                        .append(" ekranında \"").append(edge.label()).append("\" ile ")
                        .append(to == null ? "?" : to.title()).append(" ekranına geçilir\n");
                any = true;
            }
        }
        if (!any) {
            sb.append("  (tek ekran)\n");
        }

        sb.append("\nBU YOLDAKİ EKRANLARDA OKUNAN ALANLAR:\n");
        for (String nodeId : intent.path()) {
            FlowModel.Node node = flow.node(nodeId);
            if (node == null) {
                continue;
            }
            sb.append("--- ").append(node.title()).append(" ---\n");
            if (notBlank(node.note())) {
                sb.append("analistin notu: ").append(node.note().replace("\n", " ")).append('\n');
            }
            String read = reads.get(nodeId);
            sb.append(read == null || read.isBlank() ? "(bu ekran okunmadı)\n" : read + "\n");
        }

        if (catalogYaml != null && !catalogYaml.isBlank()) {
            sb.append("\nBU EKRANIN ONAYLANMIŞ KATALOĞU (etiketler burada yazdığı gibidir):\n")
                    .append(catalogYaml).append('\n');
        }

        sb.append('\n').append(rulesBlock(rules));
        sb.append(freeTextBlocks(flow));

        if (notBlank(testData)) {
            sb.append("\nORTAMDA BULUNAN TEST VERİSİ — değerler yalnız buradan:\n")
                    .append(testData).append('\n');
        }

        sb.append("\nYAZILACAK SENARYO:\n");
        sb.append("Başlık: ").append(intent.title()).append('\n');
        sb.append("Niyet: ").append(intent.intent()).append('\n');
        if (intent.negative()) {
            sb.append("Bu bir REDDETME senaryosu: uygulamanın bir şeyi YAPMAMASI"
                    + " bekleniyor. Beklenen, reddin kendisidir.\n");
        }
        sb.append("\n.feature dosyasını şimdi yaz. Dosya içeriğinden başka hiçbir şey yazma.\n");
        return sb.toString();
    }

    /**
     * The rules of this mode, cancelling by NAME the system rules a drawing
     * contradicts. Rule 9's date offsets are deliberately NOT cancelled: these
     * are new scenarios and a literal date rots, unlike the walk's, which
     * replay dates a browser actually entered.
     */
    private static final String WALK_RULES = """
            BU SENARYO ÇİZİLMİŞ BİR AKIŞTAN YAZILIYOR. Çalışan bir ekran
            gezilmedi; aşağıdakiler analistin çizdiği akış ve ekran
            görüntülerinden okunanlar.

            BU MODDA, sistem kurallarıyla çeliştiği yerde BUNLAR geçer:
            A. GİRİŞ SATIRI YAZMA. Kural 7'nin login'ini iptal eder: koşu,
               seçilen test kullanıcısından ekler. `navigate to` ile başla.
            B. YOLU SIRAYLA İZLE. Bir okun ETİKETİ o geçişi yapan kontrolün
               üstündeki yazıdır: "Fatura Oluştur" etiketli ok
               `click "Fatura Oluştur"` olur.
            C. YALNIZ YOLUN GEREKTİRDİĞİ ALANLARI DOLDUR. Kural 8'in "her
               katalog alanını doldur"unu iptal eder: bir yolculuk ekranlar
               arasında geçer, tek ekranın formu değildir.
            D. HİÇBİR ŞEY UYDURMA. Tırnak içindeki her etiket, seçenek ve
               buton, yukarıda okunan ekranlarda ya da katalogda yazdığı gibi
               olmalı. Bir kontrolcü, hiçbirinde geçmeyen tırnaklı metni
               reddediyor ve senaryo sana geri dönüyor.
            E. DEĞERLER TEST VERİSİNDEN. Ortamda var olduğunu bilmediğin bir
               fatura numarası, firma ya da bayi yazma. Bilmiyorsan o adımı
               hiç yazma.
            F. TARİHLER GÖRELİ: `enter date "..." "+3 gün"`. Kural 9 geçerli.
               Sabit tarih bir ay sonra geçmişte kalır ve form reddeder.
            G. DOĞRULAMA: beklenen her şey için bir `verify`. Bir alanın
               DEĞERİNİ doğrulamak için o değeri bu senaryonun girmiş olması
               gerekir; girmediğin bir alanın değerini doğrulayamazsın.
            H. ÖN KOŞUL ile KURALI KARIŞTIRMA. Ortamda bulunması gereken şey
               (firma, fatura, yetki) "Ön koşul" bölümüne yazılır ve
               DOĞRULANMAZ — düşerse test koşamamıştır, uygulama hatalı
               değildir. Uygulamanın yapması gereken şey kuraldır ve `verify`
               ile doğrulanır.
            I. Tam olarak bir "Scenario:". Kural 5'in Outline'ını iptal eder:
               Examples yok, "#" yorum satırı yok, `wait` adımı yok.
            """;

    /* ------------------------------ shared ------------------------------- */

    /**
     * The analyst's free-text rules and preconditions, each labelled with what
     * the model is to DO with it.
     *
     * <p>They are two blocks and not one for the reason {@code WALK_RULES}
     * item H states: a rule becomes a {@code verify}, a precondition becomes a
     * line in the "Ön koşul" section and is never verified. Pasted into one
     * box the model cannot tell them apart, and the failure is the expensive
     * direction — "fatura ödenmemiş olmalı" asserted as an expectation turns a
     * missing test fixture into a red run that looks like a defect.
     *
     * <p>Marked as having no ids on purpose. {@code rules.yaml} rules are
     * quoted back with {@code [R-...]} and the validator checks their
     * messages; these cannot be checked by anything, so the model is told not
     * to treat their wording as text printed on a screen. Otherwise a sentence
     * an analyst wrote as a note comes back as an asserted error message that
     * the application never prints.
     */
    private static String freeTextBlocks(Flow flow) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(flow.rules())) {
            sb.append("\nANALİSTİN YAZDIĞI EK KURALLAR — uygulamanın yapmak ZORUNDA olduğu şeyler.\n")
                    .append("Bir mesajı ancak burada BİREBİR yazıyorsa doğrulayabilirsin; metni\n")
                    .append("kendi cümlenle yeniden yazma, kısaltma, tamamlama.\n")
                    .append(flow.rules().trim()).append('\n');
        }
        if (notBlank(flow.preconditions())) {
            sb.append("\nORTAM ÖN KOŞULLARI — koşmadan ÖNCE doğru olması gerekenler.\n")
                    .append("Bunlar DOĞRULANMAZ: senaryonun \"Ön koşul\" bölümüne yazılır. Biri\n")
                    .append("tutmazsa test koşamamıştır, uygulama hatalı değildir — bu yüzden\n")
                    .append("hiçbiri için `verify` yazma.\n")
                    .append(flow.preconditions().trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The rules, as {@code PromptBuilder.rulesBlock} renders them — messages
     * quoted and marked copy-exactly, because the validator compares them
     * character for character.
     */
    private static String rulesBlock(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "ÜRÜN KURALLARI: (yok)\n";
        }
        StringBuilder sb = new StringBuilder("ÜRÜN KURALLARI — test edilecek olan bunlar:\n");
        for (Rule rule : rules) {
            sb.append("[").append(rule.id()).append("] ").append(rule.title());
            if (!"DOĞRULANDI".equals(rule.status())) {
                sb.append(" (VARSAYIM — kesinleşmemiş)");
            }
            sb.append('\n');
            if (notBlank(rule.description())) {
                sb.append("    ").append(rule.description().replaceAll("\\s+", " ").trim()).append('\n');
            }
            for (String message : rule.messages()) {
                sb.append("    Uygulamanın yazdığı mesaj, BİREBİR kopyala: \"")
                        .append(message).append("\"\n");
            }
            for (String value : rule.allValues()) {
                sb.append("    Bu kuralın sabitlediği değer: \"").append(value).append("\"\n");
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
