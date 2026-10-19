package orchestrator.users;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The saved test logins.
 *
 * <p>Passwords are returned to the browser. That is deliberate and it is the
 * whole point: the four generation panels have to put a real password into a
 * request, and an endpoint that withheld it would force the analyst to retype
 * the thing they just saved. These are test-environment credentials; see
 * {@link TestUser}.
 */
@RestController
@RequestMapping("/api/v1/users")
public class TestUserController {

    /** The form's fields. `id` is empty when adding, set when editing. */
    public record SaveUserRequest(String id, String name, String cif, String userid,
                                  String password, String role, String note) {
    }

    private final TestUserService users;

    public TestUserController(TestUserService users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<?> all() {
        return ResponseEntity.ok(Map.of(
                "file", users.file().toString(),
                "users", users.list().stream().map(TestUserController::row).toList()));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveUserRequest body) {
        if (body == null || body.cif() == null || body.cif().isBlank()) {
            // The cif is the only required field: it is what actually logs in,
            // and a row without one would sit in the picker failing every time.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Firma / Müşteri kodu zorunlu"));
        }
        TestUser saved = users.save(new TestUser(
                body.id(), text(body.name()), body.cif().trim(), text(body.userid()),
                text(body.password()), text(body.role()), text(body.note())));
        return ResponseEntity.ok(row(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return users.delete(id)
                ? ResponseEntity.ok(Map.of("deleted", id))
                : ResponseEntity.status(404).body(Map.of("error", "Kullanıcı yok: " + id));
    }

    private static Map<String, Object> row(TestUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("name", user.name());
        out.put("label", user.label());
        out.put("cif", user.cif());
        out.put("userid", user.userid());
        out.put("password", user.password());
        out.put("role", user.role());
        out.put("note", user.note());
        return out;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
