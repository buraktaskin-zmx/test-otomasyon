package orchestrator.catalog;

import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As much of a screen catalog as the validator and the variation planner need:
 * what each label is, and what it will accept.
 *
 * {@link CatalogService} deliberately does NOT parse the YAML, and says why —
 * the model reads YAML perfectly well, and a parser has to be kept in step
 * with catalogs/SCHEMA.md. That reasoning held while nothing needed the
 * structure. Something does now: a generated scenario's mistakes live entirely
 * inside the quotes ("Toplam Tutarı" for "Toplam Tutar", `enter` on a
 * dropdown), and only the catalog knows the right answer.
 *
 * The cost of parsing is paid down by being LENIENT rather than strict. This
 * is not a schema validator and must never become one: unknown keys are
 * ignored, missing keys are not errors, and a catalog that will not parse at
 * all simply switches the catalog checks off. A catalog is written by an
 * analyst or emitted by discovery; neither should be blocked by this class
 * having an opinion.
 */
public final class CatalogModel {

    /**
     * One catalogued field. {@code options} carries a dropdown's option texts
     * or an account picker's account numbers — empty when the catalog does not
     * say, which means "do not check", never "nothing is allowed".
     *
     * The four constraint components exist for VariationPlanner, which has to
     * invent a DIFFERENT value per scenario and can only do that honestly if it
     * knows what the box accepts: {@code maxLength} is the DOM's own maxlength,
     * {@code min}/{@code max} a number input's range, {@code numeric} whether
     * only digits go in. All boxed / defaulted, and null means "the catalog did
     * not say" rather than "unbounded" — the planner then falls back to a
     * conservative ladder AND says so, because a silently invented 16-character
     * value in a 3-character box is a failure the analyst cannot explain.
     *
     * {@code shownWhenField}/{@code shownWhenValue} were previously dropped on
     * the floor. The planner needs them: a field a choice reveals may be filled
     * ONLY in the variations that made that choice, and leaving that decision to
     * the model is how a scenario ends up hunting for "E-Fatura Hash Kodu" on a
     * screen showing "Normal" until it times out.
     */
    public record CatalogField(String label, String type, List<String> options, boolean readonly,
                               Integer maxLength, Integer min, Integer max, boolean numeric,
                               String shownWhenField, String shownWhenValue) {

        /** Whether this field only exists on screen once another field holds a value. */
        public boolean conditional() {
            return shownWhenField != null && !shownWhenField.isBlank();
        }
    }

    private final Map<String, CatalogField> byLabel;

    /**
     * The result-grid row the flow picks on its way in, or empty.
     *
     * The one piece of { navigation} this class reads, and only because a
     * generated scenario kept getting it wrong in a way nothing could catch: the
     * model emitted `click "FORD DBS ODEMELERI"` AND `select row "FORD DBS
     * ODEMELERI"`. Both are valid patterns and the value is a real one, so the
     * grammar and the catalog checks were both happy — and the click then spent
     * thirty seconds hunting for a button or link that a grid cell is not.
     * Knowing which value is a ROW is what makes that findable.
     */
    private final String entryRow;

    /**
     * The flow's confirmation text, or empty when the catalog does not give
     * one. Read because a message is NOT test data: a scenario that paraphrases
     * it ("Fatura başarıyla oluşturuldu" for "Fatura Oluşturma işleminiz
     * başarıyla gerçekleşmiştir.") passes validation today and fails in the
     * browser on a screen where nothing is wrong.
     */
    private final String successMessage;

    private CatalogModel(Map<String, CatalogField> byLabel, String successMessage, String entryRow) {
        this.byLabel = byLabel;
        this.successMessage = successMessage;
        this.entryRow = entryRow;
    }

    /** The grid row the flow enters through, or "" when it does not. */
    public String entryRow() {
        return entryRow;
    }

    public String successMessage() {
        return successMessage;
    }

    /**
     * Reads a catalog, or gives back nothing at all when it cannot.
     *
     * Empty rather than an exception: a malformed catalog must not stop a
     * scenario from being generated. The caller treats "no model" as "no
     * catalog checks", which is exactly the behaviour before this existed.
     */
    public static Optional<CatalogModel> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return Optional.empty();
        }
        try {
            Object root = new Yaml().load(yaml);
            if (!(root instanceof Map<?, ?> document)) {
                return Optional.empty();
            }
            Object fields = document.get("fields");
            if (!(fields instanceof List<?> entries)) {
                return Optional.empty();
            }

            Map<String, CatalogField> byLabel = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> field) {
                    read(field).ifPresent(f -> byLabel.put(f.label(), f));
                }
            }
            return byLabel.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new CatalogModel(byLabel, text(document.get("success_message")),
                            navigationValue(document, "entry_row")));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }


    /**
     * Why {@link #parse} came back empty, in words an analyst can act on.
     *
     * The parse itself stays silent and lenient — that is deliberate and
     * documented above. But the CALLER then tells the analyst "the catalog
     * could not be parsed, check the fields block", which is true and useless:
     * it names neither the line nor the reason, and the reasons are not
     * guessable (a tab, a colon inside an unquoted Turkish sentence, a field
     * written as a string instead of a map). SnakeYAML knows the line and
     * column; throwing that away was the waste.
     *
     * Called ONLY when parse already failed, so the happy path pays nothing and
     * the work is done twice only when something is wrong anyway.
     */
    public static String diagnose(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "Katalog dosyası boş.";
        }
        Object root;
        try {
            root = new Yaml().load(yaml);
        } catch (RuntimeException broken) {
            // The message carries line and column; keep it on one line so it
            // survives a UI that renders notes as single paragraphs.
            return "YAML sözdizimi hatalı: " + oneLine(broken.getMessage())
                    + " — en sık iki sebebi: girintide TAB kullanmak, ve tırnaksız bir "
                    + "metnin içinde ':' geçmesi (örn. success_message). Üstteki satır ve "
                    + "sütun numarası yeri gösteriyor.";
        }
        if (!(root instanceof Map<?, ?> document)) {
            return "Dosyanın en üst seviyesi bir anahtar/değer bloğu değil.";
        }
        Object fields = document.get("fields");
        if (fields == null) {
            return "'fields:' anahtarı yok. Katalogda alan listesi zorunlu.";
        }
        if (!(fields instanceof List<?> entries)) {
            return "'fields:' bir liste değil. Her alan '  - label: \"...\"' şeklinde "
                    + "tire ile başlayan bir madde olmalı.";
        }
        if (entries.isEmpty()) {
            return "'fields:' listesi boş.";
        }
        long maps = entries.stream().filter(e -> e instanceof Map<?, ?>).count();
        if (maps == 0) {
            return "'fields:' içindeki maddelerin hiçbiri anahtar/değer bloğu değil — "
                    + "düz metin satırı gibi yazılmışlar. Beklenen: '  - label: \"Tutar\"' "
                    + "ve altında '    type: amount'.";
        }
        return "'fields:' içindeki " + entries.size() + " maddenin hiçbirinde okunabilir bir "
                + "'label:' yok. Etiket zorunlu ve boş olamaz.";
    }

    private static String oneLine(String message) {
        return message == null ? "(ayrıntı yok)" : message.replaceAll("\s+", " ").trim();
    }

    private static Optional<CatalogField> read(Map<?, ?> field) {
        String label = text(field.get("label"));
        if (label.isEmpty()) {
            return Optional.empty();
        }
        // Both keys mean "the values this field accepts"; which one a catalog
        // uses follows from the type (SCHEMA.md), so read whichever is there.
        List<String> options = strings(field.get("options"));
        if (options.isEmpty()) {
            options = strings(field.get("accounts"));
        }

        // shown_when is a nested {field, value} map. Anything else there — a
        // string, a list, a half-written entry — reads as "not conditional"
        // rather than as an error, because a catalog is hand-edited and this
        // class must never be the reason one stops working.
        String shownWhenField = null;
        String shownWhenValue = null;
        if (field.get("shown_when") instanceof Map<?, ?> condition) {
            shownWhenField = emptyToNull(text(condition.get("field")));
            shownWhenValue = text(condition.get("value"));
        }

        return Optional.of(new CatalogField(
                label,
                text(field.get("type")),
                options,
                Boolean.TRUE.equals(field.get("readonly")),
                integer(field.get("max_length")),
                integer(field.get("min")),
                integer(field.get("max")),
                Boolean.TRUE.equals(field.get("numeric")),
                shownWhenField,
                shownWhenValue));
    }


    /** One key out of the navigation block, or "" when the block or key is absent. */
    private static String navigationValue(Map<?, ?> document, String key) {
        return document.get("navigation") instanceof Map<?, ?> navigation
                ? text(navigation.get(key))
                : "";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * A whole number, or null when the catalog did not give one.
     *
     * Null rather than a sentinel such as -1: the caller has to tell "no limit
     * was recorded" apart from "the limit is zero", and a sentinel makes those
     * the same value. Unparseable text is also null — an analyst writing
     * {@code max_length: onbir} should lose the constraint, not the catalog.
     */
    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String digits = text(value);
            return digits.isEmpty() ? null : Integer.valueOf(digits);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(CatalogModel::text).filter(s -> !s.isEmpty()).toList();
    }

    public Optional<CatalogField> field(String label) {
        return Optional.ofNullable(byLabel.get(label == null ? "" : label.trim()));
    }

    public List<String> labels() {
        return List.copyOf(byLabel.keySet());
    }

    /**
     * Every field, in the order the catalog lists them — which SCHEMA.md
     * requires to be screen order.
     *
     * That ordering is load-bearing for VariationPlanner rather than cosmetic:
     * it walks the list once and a {@code shown_when} parent must already have
     * been assigned by the time its dependent field is reached.
     */
    public List<CatalogField> fields() {
        return List.copyOf(byLabel.values());
    }
}
