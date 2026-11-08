package orchestrator.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tree an analyst draws: screens and the transitions between them.
 *
 * <p>This is what {@code flow.md} used to be, in a shape a machine can read.
 * A node is a screen, an edge is the action that moves between two of them,
 * and the edge's label is the text ON the control — "Fatura Oluştur", not
 * "faturayı oluşturma adımı" — because the generator turns it straight into
 * {@code click "Fatura Oluştur"}.
 */
public final class FlowModel {

    private FlowModel() {
    }

    /**
     * One screen.
     *
     * @param id   stable, filename-safe; the screenshot and the read draft are
     *             stored under it
     * @param flow the catalog name this screen will be saved as
     *             ({@code catalogs/<app>/<flow>.yaml}), blank until chosen
     * @param note the analyst's free text about what the screenshot cannot
     *             show — "bu ekran yalnız yetkili kullanıcıda açılır",
     *             "listede üç kayıt var". The cheapest context there is, and
     *             the only place facts outside the picture can enter
     */
    public record Node(String id, String title, String flow, String note, double x, double y) {
    }

    /** One transition. {@code label} is the control's own text. */
    public record Edge(String id, String from, String to, String label) {
    }

    /**
     * A whole drawing, plus the two things the analyst says about the batch.
     *
     * @param intent     what is to be tested — happy paths, refusals, permissions
     * @param outOfScope branches to leave alone ("dosya yükleme dalını test etme")
     */
    public record Flow(List<Node> nodes, List<Edge> edges, String intent, String outOfScope) {

        public static Flow empty() {
            return new Flow(List.of(), List.of(), "", "");
        }

        public Node node(String id) {
            return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
        }

        /** The nodes nothing points at: where a journey starts. */
        public List<Node> roots() {
            return nodes.stream()
                    .filter(n -> edges.stream().noneMatch(e -> e.to().equals(n.id())))
                    .toList();
        }

        /**
         * Every root-to-leaf path, as lists of node ids — one journey scenario
         * each.
         *
         * <p>Cycle-safe: a node already on the path being walked ends it. The
         * analyst is drawing a tree, but nothing stops them drawing a loop
         * back to the list screen, and an unguarded walk would not return.
         * Bounded as well, because a wide graph has exponentially many paths
         * and nobody is going to read 400 journeys.
         */
        public List<List<String>> paths(int limit) {
            List<List<String>> out = new ArrayList<>();
            for (Node root : roots()) {
                walk(root.id(), new ArrayList<>(), out, limit);
            }
            return out;
        }

        private void walk(String id, List<String> sofar, List<List<String>> out, int limit) {
            if (out.size() >= limit || sofar.contains(id)) {
                return;
            }
            List<String> path = new ArrayList<>(sofar);
            path.add(id);
            List<Edge> next = edges.stream().filter(e -> e.from().equals(id)).toList();
            if (next.isEmpty()) {
                out.add(path);
                return;
            }
            for (Edge edge : next) {
                walk(edge.to(), path, out, limit);
            }
        }

        /** The edge between two nodes, for the step its label becomes. */
        public Edge between(String from, String to) {
            return edges.stream()
                    .filter(e -> e.from().equals(from) && e.to().equals(to))
                    .findFirst().orElse(null);
        }
    }

    /**
     * One planned scenario, before anyone writes it — stage 3 of the pipeline.
     *
     * <p>A line, not a scenario: cheap to produce, cheap to read, and the
     * analyst deletes or adds lines before thirty expensive generation calls
     * are spent on the wrong things.
     *
     * @param path    node ids this scenario walks, in order; one id for a
     *                single-screen scenario
     * @param ruleIds the rules it is meant to exercise
     */
    public record Intent(String id, String title, String intent, List<String> path,
                         List<String> ruleIds, boolean negative) {
    }

    /** What stage 3 wrote, as it is stored and handed back for editing. */
    public record Plan(List<Intent> scenarios, List<String> notes) {

        public static Plan empty() {
            return new Plan(List.of(), List.of());
        }
    }

    /** A node's screenshot as the browser sends and receives it. */
    public record Screenshot(String nodeId, String dataUrl) {
    }

    /** For the prompt: the drawing as indented text the model can follow. */
    public static String describe(Flow flow, Map<String, String> readScreens) {
        StringBuilder sb = new StringBuilder("EKRANLAR:\n");
        for (Node node : flow.nodes()) {
            sb.append("- ").append(node.title());
            if (node.flow() != null && !node.flow().isBlank()) {
                sb.append("  (katalog: ").append(node.flow()).append(")");
            }
            sb.append('\n');
            if (node.note() != null && !node.note().isBlank()) {
                sb.append("    analistin notu: ").append(node.note().replace("\n", " ")).append('\n');
            }
            String read = readScreens == null ? null : readScreens.get(node.id());
            if (read != null && !read.isBlank()) {
                sb.append("    okunan alanlar:\n");
                read.lines().forEach(line -> sb.append("      ").append(line).append('\n'));
            }
        }
        sb.append("\nGEÇİŞLER (okun etiketi, o geçişi yapan kontrolün üstündeki yazıdır):\n");
        if (flow.edges().isEmpty()) {
            sb.append("  (yok)\n");
        }
        for (Edge edge : flow.edges()) {
            Node from = flow.node(edge.from());
            Node to = flow.node(edge.to());
            sb.append("- ").append(from == null ? edge.from() : from.title())
                    .append("  --[ ").append(edge.label()).append(" ]-->  ")
                    .append(to == null ? edge.to() : to.title()).append('\n');
        }
        return sb.toString();
    }

    /** The titles of one path, for a scenario's own description. */
    public static String pathTitle(Flow flow, List<String> path) {
        Map<String, String> titles = new LinkedHashMap<>();
        flow.nodes().forEach(n -> titles.put(n.id(), n.title()));
        List<String> names = new ArrayList<>();
        for (String id : path) {
            names.add(titles.getOrDefault(id, id));
        }
        return String.join(" → ", names);
    }
}
