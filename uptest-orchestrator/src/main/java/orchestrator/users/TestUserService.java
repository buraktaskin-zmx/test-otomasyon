package orchestrator.users;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The saved test logins, in one YAML file the UI can write.
 *
 * <p><b>Why this is writable when products/ is not.</b> A product's rules are
 * a reviewed artefact: they live in git because someone should be able to
 * refuse a change to them. A test login is the opposite — it is operational,
 * it changes when the test environment changes, and the person who knows the
 * new password is the analyst sitting in front of the screen, not a reviewer.
 * Making them edit a YAML file by hand to try a different user would be
 * ceremony with no reader.
 *
 * <p>One global file rather than per product: the same NGC login is used
 * across every product, and copying it into each product's test-data.yaml
 * would be the same credential in several places, drifting.
 *
 * <p>Read on every call. The file is small, it is edited by hand as often as
 * through the UI, and a cache would show a password that was changed in an
 * editor two minutes ago — the one kind of staleness that costs a browser run
 * to discover.
 */
@Service
public class TestUserService {

    private final Path file;

    public TestUserService(@Value("${uptest.users.file:./testdata/users.yaml}") String path) {
        this.file = Paths.get(path).toAbsolutePath().normalize();
    }

    public List<TestUser> list() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Object root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> document)
                    || !(document.get("users") instanceof List<?> entries)) {
                return List.of();
            }
            List<TestUser> users = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> row) {
                    String cif = text(row.get("cif"));
                    if (cif.isEmpty()) {
                        // A login with no cif cannot log anything in; it would
                        // sit in the picker as an option that always fails.
                        continue;
                    }
                    users.add(new TestUser(
                            idOf(text(row.get("id")), text(row.get("name")), cif),
                            text(row.get("name")),
                            cif,
                            text(row.get("userid")),
                            text(row.get("password")),
                            text(row.get("role")),
                            text(row.get("note"))));
                }
            }
            return List.copyOf(users);
        } catch (RuntimeException unreadable) {
            // Same leniency as every other YAML reader here: a broken file
            // costs the list, never the application.
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public Optional<TestUser> find(String id) {
        return list().stream().filter(user -> user.id().equals(id)).findFirst();
    }

    /**
     * Adds a login, or replaces the one with the same id.
     *
     * Replace rather than refuse: the common edit is "same user, new
     * password", and making that a delete plus an add would lose the id every
     * saved scenario selection refers to.
     */
    public synchronized TestUser save(TestUser incoming) {
        String id = idOf(incoming.id(), incoming.name(), incoming.cif());
        TestUser user = new TestUser(id, incoming.name(), incoming.cif(), incoming.userid(),
                incoming.password(), incoming.role(), incoming.note());

        List<TestUser> users = new ArrayList<>(list());
        users.removeIf(existing -> existing.id().equals(id));
        users.add(user);
        write(users);
        return user;
    }

    public synchronized boolean delete(String id) {
        List<TestUser> users = new ArrayList<>(list());
        if (!users.removeIf(user -> user.id().equals(id))) {
            return false;
        }
        write(users);
        return true;
    }

    public Path file() {
        return file;
    }

    private void write(List<TestUser> users) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (TestUser user : users) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", user.id());
            row.put("name", user.name());
            row.put("cif", user.cif());
            row.put("userid", user.userid());
            row.put("password", user.password());
            row.put("role", user.role());
            row.put("note", user.note());
            rows.add(row);
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // Quote everything: a cif like 01600254 is a NUMBER to YAML and comes
        // back as 1600254 with the leading zero gone -- a login that fails for
        // a reason nobody would look for.
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);

        String body = new Yaml(options).dump(Map.of("users", rows));
        try {
            Files.createDirectories(file.getParent());
            // UTF-8 explicitly, like every other file this project writes:
            // the platform default mangles "Ana test kullanıcısı" on a
            // differently configured host.
            Files.writeString(file, HEADER + body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static final String HEADER = """
            # Kayitli test girisleri. Test Kullanicilari panelinden yazilir,
            # elle de duzenlenebilir.
            #
            # Bunlar TEST ORTAMI bilgileridir. Gercek bir musteriye ulasmaz.
            """;

    /**
     * A stable, path-safe handle. Derived from the name when there is one and
     * from the cif otherwise, because the name is optional and an id is not —
     * every picker selection stores one.
     */
    private static String idOf(String given, String name, String cif) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        String base = (name == null || name.isBlank()) ? cif : name;
        String slug = base.trim().toLowerCase(Locale.ROOT)
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? cif : slug;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
