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

  const remove = async (user: TestUser) => {
    const result = await users.remove(user.id);
    toast(result.ok ? "Giriş silindi" : "Silinemedi: " + result.error, !result.ok);
    if (result.ok && form.id === user.id) setForm(EMPTY);
  };

  return (
    <div id="side-users" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Test Kullanıcıları">
        {editing ? (
          <button className="btn btn-ghost" type="button" onClick={() => setForm(EMPTY)}>
            Vazgeç
          </button>
        ) : null}
      </PanelHead>

      <div className="suggest-form">
        <label className="suggest-label" htmlFor="userName">
          İsim <span className="suggest-optional">(opsiyonel)</span>
        </label>
        <input id="userName" className="search" value={form.name} onChange={set("name")}
               placeholder="Ana test kullanıcısı" />

        <label className="suggest-label" htmlFor="userCif">Firma / Müşteri kodu</label>
        <input id="userCif" className="search" value={form.cif} onChange={set("cif")}
               placeholder="LH01600254" />

        <label className="suggest-label" htmlFor="userUserid">
          Kullanıcı kodu <span className="suggest-optional">(NGI için boş)</span>
        </label>
        <input id="userUserid" className="search" value={form.userid} onChange={set("userid")}
               placeholder="1081" />

        <label className="suggest-label" htmlFor="userPassword">Şifre</label>
        <input id="userPassword" className="search" type="password"
               value={form.password} onChange={set("password")} />

        <label className="suggest-label" htmlFor="userRole">
          Rol <span className="suggest-optional">(opsiyonel)</span>
        </label>
        <input id="userRole" className="search" value={form.role} onChange={set("role")}
               placeholder="tam yetkili" />

        <label className="suggest-label" htmlFor="userNote">
          Açıklama <span className="suggest-optional">(opsiyonel)</span>
        </label>
        <input id="userNote" className="search" value={form.note} onChange={set("note")} />

        <button className="btn btn-primary suggest-go" type="button"
                disabled={save.busy} onClick={() => void save.run()}>
          {save.busy ? "Kaydediliyor…" : editing ? "Güncelle" : "Kaydet"}
        </button>

        <p className="suggest-hint">
          Bunlar test ortamı bilgileridir. <code>{users.file || "testdata/users.yaml"}</code>
          dosyasına yazılır; elle de düzenlenebilir.
        </p>
      </div>

      <div className="suggest-status">
        {!users.loaded ? null : users.users.length === 0 ? (
          <p className="registry-empty">Henüz kayıtlı giriş yok.</p>
        ) : (
          users.users.map((user) => (
            <div key={user.id} className={"user-row" + (form.id === user.id ? " is-active" : "")}>
              <div className="user-main">
                <span className="user-label">{user.label}</span>
                {user.role ? <span className="user-role">{user.role}</span> : null}
              </div>
              <div className="user-ids">
                <span className="rule-id">{user.cif}</span>
                {user.userid ? <span className="rule-id">{user.userid}</span> : null}
                {user.note ? <span className="user-note">{user.note}</span> : null}
              </div>
              <div className="user-actions">
                <button className="btn btn-ghost" type="button" onClick={() => edit(user)}>
                  Düzenle
                </button>
                <button className="btn btn-ghost" type="button" onClick={() => void remove(user)}>
                  Sil
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
