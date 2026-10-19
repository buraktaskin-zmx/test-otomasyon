import { useState } from "react";
import { PanelHead } from "./PanelHead";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { useToast } from "../../ui/Toast";
import type { useTestUsers } from "../../hooks/useTestUsers";
import type { TestUser } from "../../api/types";

const EMPTY = { id: "", name: "", cif: "", userid: "", password: "", role: "", note: "" };

/*
  Test Kullanıcıları — kayıtlı girişler, bir kez yazılır dört panelde kullanılır.

  Writable, unlike Kayıtlı Senaryolar. The difference is who the reader is: a
  product's rules are reviewed in git because someone should be able to refuse
  a change to them, while a test login is operational — it changes when the
  test environment changes, and the person who knows the new password is the
  analyst in front of the screen. Making them hand-edit a YAML file to try a
  different user would be ceremony with no reader.

  İsim is OPTIONAL and that is deliberate: a login is identified by its cif,
  so demanding a label for it would be a required field with no answer. The
  picker falls back to showing the cif.

  POOL LEFT, FORM RIGHT, and the panel takes the whole workspace to do it.
  The first version stacked the form above the list in the 40% column, which
  put the thing being READ (which logins do we have?) below the thing being
  WRITTEN (one of them, occasionally) and left a row's five facts folded into
  three lines. A pool is scanned far more often than it is edited.

  A row is EDITED by clicking it; Sil lives in the form, not in every row.
  That is one fewer column than the mock needs and it puts the irreversible
  button behind a deliberate selection rather than a pixel away from the
  one next to it.
*/
export function UsersPanel({
  active,
  users,
}: {
  active: boolean;
  users: ReturnType<typeof useTestUsers>;
}) {
  const [form, setForm] = useState(EMPTY);
  const toast = useToast();
  const editing = form.id !== "";

  const set = (field: keyof typeof EMPTY) =>
    (event: React.ChangeEvent<HTMLInputElement>) =>
      setForm((was) => ({ ...was, [field]: event.target.value }));

  const save = useAsyncAction(async () => {
    if (!form.cif.trim()) {
      toast("Firma / Müşteri kodu zorunlu", true);
      return;
    }
    const result = await users.save(form);
    if (!result.ok) {
      toast("Kaydedilemedi: " + result.error, true);
      return;
    }
    setForm(EMPTY);
    toast(editing ? "Giriş güncellendi" : "Giriş kaydedildi");
  });

  const edit = (user: TestUser) => setForm({
    id: user.id, name: user.name, cif: user.cif, userid: user.userid,
    password: user.password, role: user.role, note: user.note,
  });

  const remove = async () => {
    const result = await users.remove(form.id);
    toast(result.ok ? "Giriş silindi" : "Silinemedi: " + result.error, !result.ok);
    if (result.ok) setForm(EMPTY);
  };

  return (
    <div id="side-users" className={"side-panel users-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Test Kullanıcıları">
        <span className="users-count">{users.users.length} kayıt</span>
      </PanelHead>

      <div className="users-body">
        <section className="users-list">
          {!users.loaded ? null : users.users.length === 0 ? (
            <p className="registry-empty">
              Henüz kayıtlı giriş yok. Sağdaki formdan ekleyin — Firma / Müşteri
              kodu dışındaki her alan opsiyoneldir.
            </p>
          ) : (
            <div className="registry-scroll">
              <table className="registry">
                <thead>
                  <tr>
                    <th>İsim</th>
                    <th>Firma / Müşteri</th>
                    <th>Kullanıcı kodu</th>
                    <th>Rol</th>
                    <th>Açıklama</th>
                  </tr>
                </thead>
                <tbody>
                  {users.users.map((user) => (
                    <tr
                      key={user.id}
                      className={"is-clickable" + (form.id === user.id ? " is-chosen" : "")}
                      onClick={() => edit(user)}
                      title="Düzenlemek için tıklayın"
                    >
                      <td className="user-name">{user.name || "—"}</td>
                      <td className="user-cif">{user.cif}</td>
                      <td className="user-cif">{user.userid || "—"}</td>
                      <td className="user-role">{user.role || "—"}</td>
                      <td className="user-note">{user.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <aside className="users-form">
          <div className="users-card">
            <h3 className="users-card-title">{editing ? "DÜZENLE" : "YENİ OLUŞTUR"}</h3>

            <Field id="userName" label="İsim">
              <input id="userName" className="search" value={form.name}
                     onChange={set("name")} placeholder="MAKER_01" />
            </Field>

            <Field id="userCif" label="Firma / Müşteri">
              <input id="userCif" className="search" value={form.cif}
                     onChange={set("cif")} placeholder="LH01600254" />
            </Field>

            <Field id="userUserid" label="Kullanıcı kodu">
              <input id="userUserid" className="search" value={form.userid}
                     onChange={set("userid")} placeholder="1081" />
            </Field>

            <Field id="userPassword" label="Şifre">
              <input id="userPassword" className="search" type="password"
                     value={form.password} onChange={set("password")} />
            </Field>

            <Field id="userRole" label="Rol">
              <input id="userRole" className="search" value={form.role}
                     onChange={set("role")} placeholder="tam yetkili" />
            </Field>

            <Field id="userNote" label="Açıklama">
              <input id="userNote" className="search" value={form.note}
                     onChange={set("note")} placeholder="ana test kullanıcısı" />
            </Field>

            <div className="users-card-actions">
              {editing ? (
                <>
                  <button className="btn btn-ghost" type="button" onClick={() => setForm(EMPTY)}>
                    Vazgeç
                  </button>
                  <button className="btn btn-ghost" type="button" onClick={() => void remove()}>
                    Sil
                  </button>
                </>
              ) : null}
              <button className="btn btn-primary" type="button"
                      disabled={save.busy} onClick={() => void save.run()}>
                {save.busy ? "Kaydediliyor…" : editing ? "Güncelle" : "Kaydet"}
              </button>
            </div>
          </div>

          <p className="suggest-hint">
            Bunlar test ortamı bilgileridir, <code>{users.file || "testdata/users.yaml"}</code>{" "}
            dosyasına yazılır ve elle de düzenlenebilir. Yalnızca Firma / Müşteri kodu
            zorunlu. Rol bugün serbest metindir — doğru girişi gözle seçmek için var,
            senaryolar henüz role göre kullanıcı istemiyor.
          </p>
        </aside>
      </div>
    </div>
  );
}

/** Label beside its input rather than above it: six short fields stacked in
 *  pairs read as a record, which is what this is, and the column edge lines
 *  the inputs up so a mistyped cif is visible against the one above it. */
function Field({
  id,
  label,
  children,
}: {
  id: string;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="users-field">
      <label className="users-field-label" htmlFor={id}>{label}</label>
      {children}
    </div>
  );
}
