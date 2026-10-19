import type { useTestUsers } from "../hooks/useTestUsers";

/*
  Kayıtlı girişlerden biri seçilir; cif ve kullanıcı kodu elle yazılmaz.

  Replaces the two text inputs that stood in four panels. The same cif was
  being retyped into each of them, and a mistyped digit does not surface here
  -- it surfaces minutes later as a login failure in a browser, which is the
  most expensive place to find a typo.

  The NGI case is why `userid` is shown rather than assumed: NGC logs in with
  cif + user id and NGI with a customer code alone, so a saved login may
  legitimately have no user id and the picker must not imply one is missing.
*/
export function UserPicker({
  idPrefix,
  users,
  value,
  onChange,
  needsUserid,
  disabled,
}: {
  idPrefix: string;
  users: ReturnType<typeof useTestUsers>;
  /** The selected user's id, or "" for none. */
  value: string;
  onChange: (id: string) => void;
  /** NGC needs a user id; NGI does not. Only affects the warning below. */
  needsUserid: boolean;
  disabled?: boolean;
}) {
  const chosen = value ? users.byId(value) : null;

  return (
    <>
      <label className="suggest-label" htmlFor={idPrefix + "User"}>Test kullanıcısı</label>
      <select
        id={idPrefix + "User"}
        className="search"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">
          {users.users.length === 0 ? "(kayıtlı kullanıcı yok)" : "Seçiniz…"}
        </option>
        {users.users.map((user) => (
          <option key={user.id} value={user.id}>
            {user.label}
            {user.role ? " · " + user.role : ""}
          </option>
        ))}
      </select>

      {users.loaded && users.users.length === 0 ? (
        <p className="suggest-hint">
          Henüz kayıtlı giriş yok. Soldaki <b>Test Kullanıcıları</b> panelinden ekleyin.
        </p>
      ) : null}

      {chosen ? (
        <p className="user-chosen">
          <span className="rule-id">{chosen.cif}</span>
          {chosen.userid ? <span className="rule-id">{chosen.userid}</span> : null}
          {chosen.note ? <span className="user-note">{chosen.note}</span> : null}
        </p>
      ) : null}

      {chosen && needsUserid && !chosen.userid ? (
        <p className="product-warn">
          Bu girişin kullanıcı kodu yok — NGC için zorunlu. Test Kullanıcıları
          panelinden ekleyin.
        </p>
      ) : null}
    </>
  );
}
