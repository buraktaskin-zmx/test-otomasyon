package orchestrator.users;

/**
 * One saved test login.
 *
 * <p>These are TEST-ENVIRONMENT credentials. Nothing here reaches a real
 * customer, so they are stored as written rather than behind an indirection:
 * an alias plus a resolver would be real machinery bought with no safety.
 *
 * <p>The point of saving them is not secrecy, it is repetition. The same cif
 * and user id were being retyped into four panels — AI Üret, Çoklu Üret, Chat
 * and Ekran Keşfi — and a mistyped digit surfaces minutes later as a login
 * failure in a browser, which is the most expensive place to discover a typo.
 *
 * @param id       the stable handle the pickers select by; derived from
 *                 {@link #name} when the analyst does not give one
 * @param name     what a person calls this login ("Ana test kullanıcısı").
 *                 OPTIONAL — a login is identified by its cif, and demanding
 *                 a label for it would be a required field with no answer
 * @param userid   NGC needs one, NGI does not. Empty is legitimate, which is
 *                 why it is not validated here
 * @param role     free text, e.g. "tam yetkili" / "yalnızca görüntüleme".
 *                 Shown beside the name so the right login is picked for a
 *                 permission scenario
 */
public record TestUser(String id, String name, String cif, String userid,
                       String password, String role, String note) {

    /** The label a picker shows: the name when there is one, else the cif. */
    public String label() {
        return name == null || name.isBlank() ? cif : name;
    }
}
