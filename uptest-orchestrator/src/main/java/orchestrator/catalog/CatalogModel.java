package orchestrator.catalog;

import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As much of a screen catalog as the validator needs: what each label is, and
 * what it will accept.
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
     */
    public record CatalogField(String label, String type, List<String> options, boolean readonly) {
    }

    private final Map<String, CatalogField> byLabel;

    private CatalogModel(Map<String, CatalogField> byLabel) {
        this.byLabel = byLabel;
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
            return byLabel.isEmpty() ? Optional.empty() : Optional.of(new CatalogModel(byLabel));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
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
        return Optional.of(new CatalogField(
                label,
                text(field.get("type")),
                options,
                Boolean.TRUE.equals(field.get("readonly"))));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
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
}
