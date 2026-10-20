package orchestrator.product;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Tag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads a product's registered scenarios off disk.
 *
 * <pre>
 * products/&lt;app&gt;/&lt;product&gt;/scenarios/&lt;flow&gt;/NN-senaryo.feature
 * </pre>
 *
 * <p>One folder per screen, because the registry's first question is "what do
 * we test on this screen?" and a flat directory answers it only by reading
 * fifty filenames. The folder name IS the flow, which is also how a scenario
 * finds its catalog without anything being written down twice.
 *
 * <p>Parsed with Cucumber's own {@link GherkinParser} — the same one
 * {@code ScenarioValidator} uses — rather than with regexes over the text.
 * The step count, the title and the description are then the ones Cucumber
 * would report, so the registry cannot disagree with the runner about what a
 * file contains.
 */
public final class ScenarioLibrary {

    /** The directory under a product that holds its scenarios. */
    static final String SCENARIOS_DIR = "scenarios";

    /** {@code @needs:MAKER_01} — everything after the colon is an alias id. */
    private static final String NEEDS_PREFIX = "@needs:";

    /**
     * {@code @needs:none} — "this scenario consumes nothing", said out loud.
     *
     * Not the same as writing no tag at all, and the difference is the one
     * this project keeps relearning: an empty list means two things. "Nobody
     * recorded the preconditions" and "somebody checked and there are none"
     * look identical on disk, and only the second is a finished scenario.
     */
    private static final String NEEDS_NONE = "none";

    /** {@code @R-AL-011} — a rule binding. Matched by shape, not by a list of
     *  known ids: a tag naming a rule that no longer exists has to be
     *  REPORTED, and it can only be reported if it was read in the first
     *  place. Silently dropping it would leave the scenario looking unbound. */
    private static final String RULE_PREFIX = "@R-";

    private ScenarioLibrary() {
    }

    /**
     * Every scenario under one product, sorted by path so the list reads the
     * same on every machine — {@code Files.walk} does not promise an order,
     * and a table whose rows shuffle between refreshes is unusable.
     */
    public static List<ScenarioCard> scan(Path productFolder) {
        Path root = productFolder.resolve(SCENARIOS_DIR);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<ScenarioCard> cards = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".feature"))
                    .sorted()
                    .forEach(file -> cards.add(read(root, file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list scenarios under " + root, e);
        }
        return List.copyOf(cards);
    }

    /**
     * How many scenarios a product has, without parsing any of them.
     *
     * The tree shows a count beside every product and category; parsing fifty
     * Gherkin files to render a number nobody has opened yet is work for its
     * own sake. {@link #scan} is called only when a screen is actually
     * selected.
     */
    public static long count(Path productFolder) {
        Path root = productFolder.resolve(SCENARIOS_DIR);
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".feature"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot count scenarios under " + root, e);
        }
    }

    private static ScenarioCard read(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        // The screen is the first segment; a .feature dropped loose in
        // scenarios/ has no screen, and is shown that way rather than
        // guessed into one.
        String flow = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "";

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return broken(relative, flow, "Dosya okunamadı: " + unreadable.getMessage());
        }
        return parse(relative, flow, text);
    }

    static ScenarioCard parse(String relative, String flow, String text) {
        GherkinParser parser = GherkinParser.builder().includePickles(false).build();
        Feature[] found = new Feature[1];
        String[] failure = new String[1];

        try (ByteArrayInputStream source =
                     new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
             Stream<Envelope> envelopes = parser.parse(relative, source)) {
            envelopes.forEach(envelope -> {
                envelope.getParseError().ifPresent(error -> {
                    if (failure[0] == null) {
                        failure[0] = error.getMessage();
                    }
                });
                envelope.getGherkinDocument()
                        .flatMap(document -> document.getFeature())
                        .ifPresent(feature -> found[0] = feature);
            });
        } catch (Exception broken) {
            return broken(relative, flow, "Ayrıştırılamadı: " + broken.getMessage());
        }

        if (failure[0] != null) {
            return broken(relative, flow, "Gherkin hatası: " + failure[0]);
        }
        if (found[0] == null) {
            return broken(relative, flow, "Dosyada Feature yok.");
        }

        Feature feature = found[0];
        List<Scenario> scenarios = scenariosOf(feature);

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        feature.getTags().forEach(tag -> tags.add(tag.getName()));
        for (Scenario scenario : scenarios) {
            scenario.getTags().forEach((Tag tag) -> tags.add(tag.getName()));
        }

        // One scenario per file is the shape the registry is built for, and
        // then the SCENARIO's own name is the sentence a person wrote about
        // this test. A file with several falls back to the Feature's name,
        // which is the only title that covers all of them.
        String title = scenarios.size() == 1 && !scenarios.get(0).getName().isBlank()
                ? scenarios.get(0).getName()
                : feature.getName();

        String description = scenarios.size() == 1 && !scenarios.get(0).getDescription().isBlank()
                ? scenarios.get(0).getDescription()
                : feature.getDescription();

        int steps = scenarios.stream().mapToInt(scenario -> scenario.getSteps().size()).sum()
                + backgroundSteps(feature);

        List<String> needs = idsWithPrefix(tags, NEEDS_PREFIX).stream()
                .filter(id -> !NEEDS_NONE.equalsIgnoreCase(id))
                .toList();
        boolean needsNone = tags.contains(NEEDS_PREFIX + NEEDS_NONE);

        return new ScenarioCard(relative, flow, title.trim(), collapse(description), steps,
                idsWithPrefix(tags, RULE_PREFIX), needs, needsNone, otherTags(tags), null);
    }

    /**
     * Tags that are neither a rule nor a need — {@code @mutating},
     * {@code @smoke}, {@code @regression}.
     *
     * Shown rather than discarded. They are how a scenario says it changes
     * data or which suite it belongs to, and a registry that silently drops
     * everything it does not understand teaches people that tags do nothing.
     */
    private static List<String> otherTags(LinkedHashSet<String> tags) {
        return tags.stream()
                .filter(tag -> !tag.startsWith(RULE_PREFIX) && !tag.startsWith(NEEDS_PREFIX))
                .map(tag -> tag.substring(1))
                .filter(tag -> !tag.isBlank())
                .toList();
    }

    private static List<Scenario> scenariosOf(Feature feature) {
        List<Scenario> scenarios = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getScenario().ifPresent(scenarios::add);
            child.getRule().ifPresent(rule -> {
                for (var ruleChild : rule.getChildren()) {
                    ruleChild.getScenario().ifPresent(scenarios::add);
                }
            });
        }
        return scenarios;
    }

    /**
     * Background steps count too. They run, so a scenario whose login and
     * navigation live in a Background is not a three-step test however short
     * its own body looks — the same reason {@code collectFromFeature} folds
     * them into one list before validating.
     */
    private static int backgroundSteps(Feature feature) {
        int total = 0;
        for (FeatureChild child : feature.getChildren()) {
            total += child.getBackground().map(background -> background.getSteps().size()).orElse(0);
        }
        return total;
    }

    /**
     * Tag ids of one kind, in the order written, without the prefix.
     *
     * {@code @R-AL-011} keeps its {@code R-} so it reads as the rule id does
     * everywhere else; {@code @data:MAKER_01} loses the whole prefix because
     * the alias is named {@code MAKER_01} in test-data.yaml.
     */
    private static List<String> idsWithPrefix(LinkedHashSet<String> tags, String prefix) {
        return tags.stream()
                .filter(tag -> tag.startsWith(prefix))
                .map(tag -> RULE_PREFIX.equals(prefix) ? tag.substring(1) : tag.substring(prefix.length()))
                .filter(id -> !id.isBlank())
                .toList();
    }

    /** One scenario's raw Gherkin, for the detail view. */
    public static String text(Path productFolder, String relative) {
        Path root = productFolder.resolve(SCENARIOS_DIR).normalize();
        Path file = root.resolve(relative).normalize();
        // The path arrives from a query string, so containment is checked
        // rather than trusted -- the same reasoning CatalogService spells out.
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Overwrites an EXISTING scenario file. Never creates one: a path that does
     * not already name a .feature under scenarios/ is refused, so a browser
     * cannot drop a new file anywhere, inside the folder or out of it.
     *
     * @return null when written, otherwise why not
     */
    public static String write(Path productFolder, String relative, String text) {
        Path root = productFolder.resolve(SCENARIOS_DIR).normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)
                || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".feature")) {
            return "Senaryo bulunamadı: " + relative;
        }
        // A file that no longer parses would vanish from the table as a
        // broken row, so it is refused here, where the edit is still open.
        String problem = parse(relative, "", text).problem();
        if (problem != null) {
            return "Gherkin okunamadı: " + problem;
        }
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** A Gherkin description is indented free text; the table wants one line. */
    private static String collapse(String description) {
        return description == null ? "" : description.replaceAll("\\s+", " ").trim();
    }

    private static ScenarioCard broken(String relative, String flow, String problem) {
        String name = relative.contains("/")
                ? relative.substring(relative.lastIndexOf('/') + 1)
                : relative;
        return new ScenarioCard(relative, flow, name, "", 0,
                List.of(), List.of(), false, List.of(), problem);
    }
}
