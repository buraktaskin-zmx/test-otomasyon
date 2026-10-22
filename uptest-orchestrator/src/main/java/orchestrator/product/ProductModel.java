package orchestrator.product;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A banking product's written knowledge: what it is, which screens it owns,
 * and the rules it must obey.
 *
 * <p><b>Why this is not part of the catalog.</b> A catalog is DISCOVERED —
 * {@code CatalogDiscovery} reads the DOM, the MCP agent reads an accessibility
 * snapshot, and both produce a description of a screen as it stands. A rule is
 * WRITTEN: it says what the application ought to do, which no amount of
 * looking at the application can establish. Different producer, different
 * source of truth, therefore a different document. Folding rules into the
 * catalog would also break the thing that makes them useful — rules exist
 * BEFORE the screen does, which is how a product can be specified while its
 * catalogs are still being walked.
 *
 * <p>Which is also why the binding runs this way round: the product declares
 * its flows, rather than each catalog naming its product. A flow listed here
 * with no catalog on disk is not an error, it is the work queue.
 *
 * <p>Lenient in exactly the way {@code CatalogModel} is lenient, and for the
 * same reason: this is not a schema validator and must never become one. An
 * unreadable rules file costs the rules, not the product; an unknown key is
 * ignored; a missing key is not an error. What it will NOT do is swallow a
 * mistake silently — {@link #duplicateIds()} reports what a lenient parse
 * would otherwise lose, the same way {@code CatalogSink} reports the labels it
 * could not match.
 */
public final class ProductModel {

    /**
     * The files that make up one product, as text.
     *
     * Grouped into a record rather than passed as seven parameters so the
     * service reads as "gather, then parse", and so a test can build a product
     * out of two string literals without naming the three it does not care
     * about ({@link #of}).
     */
    public record Sources(String productYaml, String rulesYaml, String testDataYaml,
                          String stateMachine, String roles, String openQuestions,
                          String knownIssues) {

        /** A product described only by its two YAML files. */
        public static Sources of(String productYaml, String rulesYaml) {
            return new Sources(productYaml, rulesYaml, "", "", "", "", "");
        }
    }

    private final String app;
    private final String key;
    private final String name;
    private final String category;
    private final String description;
    private final List<String> flows;
    private final Map<String, String> screenNames;
    private final Map<String, DataAlias> aliases;
    private final Map<String, Rule> byId;
    private final List<String> duplicateIds;
    private final String stateMachine;
    private final String roles;
    private final String openQuestions;
    private final String knownIssues;

    private ProductModel(String app, String key, String name, String category, String description,
                         List<String> flows, Map<String, String> screenNames,
                         Map<String, DataAlias> aliases,
                         Map<String, Rule> byId, List<String> duplicateIds,
                         String stateMachine, String roles, String openQuestions,
                         String knownIssues) {
        this.app = app;
        this.key = key;
        this.name = name;
        this.category = category;
        this.description = description;
        this.flows = flows;
        this.screenNames = screenNames;
        this.aliases = aliases;
        this.byId = byId;
        this.duplicateIds = duplicateIds;
        this.stateMachine = stateMachine;
        this.roles = roles;
        this.openQuestions = openQuestions;
        this.knownIssues = knownIssues;
    }

    public String app() {
        return app;
    }

    /** The directory name, e.g. {@code maas-odemeleri}. */
    public String key() {
        return key;
    }

    /** The human name, e.g. "Maaş Ödemeleri"; falls back to {@link #key()}. */
    public String name() {
        return name;
    }

    /**
     * The business area this product sits in, e.g. "Ödemeler" — the level
     * above the product in the Ürünler tree, and "" when none is declared.
     *
     * Deliberately NOT derived from {@link #app()}. NGC and NGI are
     * APPLICATIONS; Ödemeler and Hesap İşlemleri cut across them, and a
     * product that gains an NGI screen one day must not change business area
     * because of it.
     */
    public String category() {
        return category;
    }

    /** What the product is, in the analyst's words. Display only. */
    public String description() {
        return description;
    }

    /** Every alias in test-data.yaml, in the order the file declares them. */
    public List<DataAlias> aliases() {
        return List.copyOf(aliases.values());
    }

    /** One alias, or null when a scenario asks for a name nobody defined. */
    public DataAlias alias(String id) {
        return id == null ? null : aliases.get(id.trim());
    }

    /**
     * Bugs the team already knows about, as free text.
     *
     * Unlike {@link #openQuestions()} this one is meant to be readable by the
     * model — the file's own heading addresses it directly ("bu davranışlara
     * senaryo ÜRETME"). Whether it is injected is the caller's decision.
     */
    public String knownIssues() {
        return knownIssues;
    }

    /** The flows this product claims, as catalog names without the app prefix. */
    public List<String> flows() {
        return flows;
    }

    /** The screen's human name from {@code screen_names:}, or the flow itself. */
    public String screenName(String flow) {
        return screenNames.getOrDefault(flow, flow);
    }

    public List<Rule> rules() {
        return List.copyOf(byId.values());
    }

    /**
     * Rule ids that appeared more than once. The first wins; this is how the
     * rest become visible instead of vanishing into a map.
     */
    public List<String> duplicateIds() {
        return duplicateIds;
    }

    public String stateMachine() {
        return stateMachine;
    }

    public String roles() {
        return roles;
    }

    /**
     * What nobody has answered yet. Deliberately NOT sent to the model — its
     * whole purpose is to mark where an assumption would be invented, and
     * handing the list to something that invents plausible text defeats it.
     * The UI shows it to the analyst instead.
     */
    public String openQuestions() {
        return openQuestions;
    }

    /**
     * The rules the analyst picked, in the order the product declares them.
     *
     * Unknown ids are skipped rather than refused: the selection travels from a
     * browser that may be a rules file behind, and losing one rule is a smaller
     * failure than refusing to generate.
     */
    public List<Rule> selected(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.of();
        }
        return byId.values().stream().filter(rule -> ruleIds.contains(rule.id())).toList();
    }

    /**
     * Every sentence the application is known to print, from every rule —
     * regardless of which rules the analyst selected for this generation.
     *
     * The selection shapes the PROMPT; it must not shape the VALIDATOR. A
     * message either exists in this product or it does not, and that fact does
     * not change because an analyst left a checkbox unticked. Tying the two
     * together would mean a scenario that validated during generation failing
     * the identical check at the run gate, which is the regression this
     * codebase has already paid for once.
     */
    public List<String> messages() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Rule rule : byId.values()) {
            all.addAll(rule.messages());
        }
        return List.copyOf(all);
    }

    /**
     * Every value every rule fixes in its {@code degerler:} block — dropdown
     * options, defaults, whatever a rule writes down as literal.
     *
     * The same shape and the same reasoning as {@link #messages()}: every
     * rule's, never only the ticked ones, because the selection shapes the
     * prompt and must not shape the validator.
     */
    public List<String> values() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Rule rule : byId.values()) {
            all.addAll(rule.allValues());
        }
        return List.copyOf(all);
    }

    /**
     * Reads a product, or gives back nothing when {@code product.yaml} is
     * missing or unreadable.
     *
     * A product with zero rules parses fine — that is a product someone has
     * started, not a broken one. Only the identity file is load-bearing.
     */
    public static Optional<ProductModel> parse(String app, String key, Sources sources) {
        if (sources == null || sources.productYaml() == null || sources.productYaml().isBlank()) {
            return Optional.empty();
        }
        Map<?, ?> document;
        try {
            if (!(new Yaml().load(sources.productYaml()) instanceof Map<?, ?> loaded)) {
                return Optional.empty();
            }
            document = loaded;
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }

        String name = text(document.get("name"));
        List<String> duplicates = new ArrayList<>();
        return Optional.of(new ProductModel(
                app,
                key,
                name.isEmpty() ? key : name,
                text(document.get("category")),
                text(document.get("description")),
                strings(document.get("flows")),
                names(document.get("screen_names")),
                readAliases(sources.testDataYaml()),
                readRules(sources.rulesYaml(), duplicates),
                List.copyOf(duplicates),
                text(sources.stateMachine()),
                text(sources.roles()),
                text(sources.openQuestions()),
                text(sources.knownIssues())));
    }

    /**
     * Why {@link #parse} came back empty, in words an analyst can act on —
     * the same service {@code CatalogModel.diagnose} performs, and called the
     * same way: only after a failure, so the happy path pays nothing.
     */
    public static String diagnose(String productYaml) {
        if (productYaml == null || productYaml.isBlank()) {
            return "product.yaml dosyası yok ya da boş. Bir ürünü var eden dosya budur.";
        }
        try {
            Object root = new Yaml().load(productYaml);
            if (!(root instanceof Map<?, ?>)) {
                return "product.yaml'ın en üst seviyesi bir anahtar/değer bloğu değil. "
                        + "Beklenen: 'app:', 'name:', 'flows:' satırları.";
            }
            return "product.yaml okunabiliyor.";
        } catch (RuntimeException broken) {
            return "product.yaml sözdizimi hatalı: " + oneLine(broken.getMessage())
                    + " — en sık iki sebebi: girintide TAB kullanmak, ve tırnaksız bir "
                    + "metnin içinde ':' geçmesi.";
        }
    }

    /** The same, for the rules file; separate because it fails separately. */
    public static String diagnoseRules(String rulesYaml) {
        if (rulesYaml == null || rulesYaml.isBlank()) {
            return "rules.yaml yok ya da boş — ürünün hiç kuralı tanımlanmamış.";
        }
        Object root;
        try {
            root = new Yaml().load(rulesYaml);
        } catch (RuntimeException broken) {
            return "rules.yaml sözdizimi hatalı: " + oneLine(broken.getMessage())
                    + " — çok satırlı açıklamalar için 'description: >' kullanın, "
                    + "mesajları her zaman çift tırnak içinde yazın.";
        }
        if (!(root instanceof Map<?, ?> document)) {
            return "rules.yaml'ın en üst seviyesi bir anahtar/değer bloğu değil. "
                    + "Dosya 'rules:' ile başlamalı.";
        }
        Object rules = document.get("rules");
        if (rules == null) {
            return "'rules:' anahtarı yok. Kural listesi bu anahtarın altında olmalı.";
        }
        if (!(rules instanceof List<?> entries)) {
            return "'rules:' bir liste değil. Her kural '  - id: R-XXX-000' şeklinde "
                    + "tire ile başlayan bir madde olmalı.";
        }
        if (entries.isEmpty()) {
            return "'rules:' listesi boş.";
        }
        return "rules.yaml içindeki " + entries.size() + " maddenin hiçbirinde okunabilir bir "
                + "'id:' yok. Kimliksiz bir kural seçilemez ve raporlanamaz.";
    }

    /**
     * Rules in declaration order, first occurrence of an id winning.
     *
     * Order is preserved because it is the analyst's order: the UI lists the
     * checkboxes in it and the prompt block repeats it, so a rules file reads
     * the same in all three places.
     */
    private static Map<String, Rule> readRules(String rulesYaml, List<String> duplicates) {
        if (rulesYaml == null || rulesYaml.isBlank()) {
            return Map.of();
        }
        try {
            if (!(new Yaml().load(rulesYaml) instanceof Map<?, ?> document)) {
                return Map.of();
            }
            if (!(document.get("rules") instanceof List<?> entries)) {
                return Map.of();
            }
            Map<String, Rule> byId = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> rule)) {
                    continue;
                }
                String id = text(rule.get("id"));
                if (id.isEmpty()) {
                    continue;
                }
                if (byId.containsKey(id)) {
                    duplicates.add(id);
                    continue;
                }
                byId.put(id, new Rule(
                        id,
                        text(rule.get("title")),
                        text(rule.get("status")),
                        text(rule.get("description")),
                        strings(rule.get("fields")),
                        strings(rule.get("messages")),
                        valueLists(rule.get("degerler")),
                        text(rule.get("source"))));
            }
            return byId;
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /**
     * Aliases out of test-data.yaml.
     *
     * The grouping keys — kullanicilar, firmalar, listeler — are the
     * ANALYST'S, not a fixed set: a product invents the categories its data
     * actually has, and a parser that demanded known group names would reject
     * the next product for being about something else. So every top-level map
     * is walked, and a child counts as an alias when it carries a
     * {@code cozum} key. That key is the discriminator on purpose: it is the
     * one field without which an alias says nothing about whether a scenario
     * depending on it can run today.
     */
    private static Map<String, DataAlias> readAliases(String testDataYaml) {
        if (testDataYaml == null || testDataYaml.isBlank()) {
            return Map.of();
        }
        try {
            if (!(new Yaml().load(testDataYaml) instanceof Map<?, ?> document)) {
                return Map.of();
            }
            Map<String, DataAlias> byId = new LinkedHashMap<>();
            for (Map.Entry<?, ?> group : document.entrySet()) {
                if (!(group.getValue() instanceof Map<?, ?> entries)) {
                    continue;
                }
                String groupName = text(group.getKey());
                for (Map.Entry<?, ?> entry : entries.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> alias)
                            || !alias.containsKey("cozum")) {
                        continue;
                    }
                    String id = text(entry.getKey());
                    if (id.isEmpty() || byId.containsKey(id)) {
                        continue;
                    }
                    byId.put(id, new DataAlias(
                            id,
                            groupName,
                            text(alias.get("kisa")),
                            text(alias.get("aciklama")),
                            text(alias.get("cozum")),
                            text(alias.get("deger")),
                            text(alias.get("engel")),
                            text(alias.get("not"))));
                }
            }
            return byId;
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * A rule's {@code degerler:} block: named lists of literal values.
     *
     * Lenient like every other reader here — anything that is not a mapping
     * costs the values, not the rule. A scalar is wrapped into a one-item
     * list so that {@code varsayilan: "Son 1 Ay"} and
     * {@code secenekler: [...]} come out as the same type and no caller has
     * to ask which one it got.
     */
    private static Map<String, List<String>> valueLists(Object value) {
        if (!(value instanceof Map<?, ?> entries)) {
            return Map.of();
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String key = text(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            List<String> items;
            if (entry.getValue() instanceof List<?>) {
                items = strings(entry.getValue());
            } else {
                String single = text(entry.getValue());
                items = single.isEmpty() ? List.of() : List.of(single);
            }
            if (!items.isEmpty()) {
                lists.put(key, items);
            }
        }
        // Not Map.copyOf: that leaves iteration order unspecified, and these
        // lists are rendered to a person in the order the analyst wrote them.
        return Collections.unmodifiableMap(lists);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(ProductModel::text).filter(s -> !s.isEmpty()).toList();
    }

    private static Map<String, String> names(Object value) {
        if (!(value instanceof Map<?, ?> entries)) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        entries.forEach((flow, name) -> {
            if (!text(flow).isEmpty() && !text(name).isEmpty()) {
                names.put(text(flow), text(name));
            }
        });
        return names;
    }

    private static String oneLine(String message) {
        return message == null ? "(ayrıntı yok)" : message.replaceAll("\\s+", " ").trim();
    }
}
