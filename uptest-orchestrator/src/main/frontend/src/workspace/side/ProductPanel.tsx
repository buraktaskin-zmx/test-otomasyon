import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { ProductTree, categoryKey } from "../../product/ProductTree";
import type { TreeSelection } from "../../product/ProductTree";
import { ScenarioDetail } from "../../product/ScenarioDetail";
import { ScenarioTable } from "../../product/ScenarioTable";
import type { useProducts } from "../../hooks/useProducts";
import type { useTestUsers } from "../../hooks/useTestUsers";
import {
  BROWSER_CLOSED, addScreen, createMenu, createScenarioFile, explore, exportScenarios, moveScenario,
} from "../../api/endpoints";
import type { MoveTarget } from "../../product/ScenarioTable";
import { NewScenario } from "../../product/NewScenario";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { docAsText } from "../../lib/scenarioProse";
import { useToast } from "../../ui/Toast";
import type { Origin, RunLogin } from "../../api/types";

/** Where the agent logs in, per application -- the same pages NGC.java and
 *  NGI.java open. */
export const LOGIN_URL: Record<string, string> = {
  ngc: "https://ifctest.yapikredi.com.tr/ngc/indexNgc.do",
  ngi: "https://intest.yapikredi.com.tr/ngi/passResetEnter.do",
};

/** The test environment's fixed Akıllı SMS code. */
export const TEST_SMS_CODE = "34020";

/*
  Kayıtlı Senaryolar — ürün > ekran > senaryo kütüğü.

  Read-only, and that is a decision rather than an unfinished half. Rules,
  test data and scenarios all live in the repository, where git gives review,
  history and a diff, and where a reviewer can refuse a change. A Save button
  here would turn them into free text arriving over HTTP.

  What the screen is FOR is the two joins nothing else can show. A .feature
  file on disk states its steps; it cannot state that the rule it claims to
  cover was deleted last week, or that the alias it needs is still nobody's.
  Without those columns a registry is a directory listing in a nicer font.
*/
export function ProductPanel({
  active,
  products,
  users,
  onOpen,
}: {
  active: boolean;
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  /** "Git": opens a registered scenario in the Çalıştır editor, with the
   *  login picked above it and the file it came from, so it can be edited,
   *  saved back and run there. */
  onOpen: (feature: string, origin: Origin, login: RunLogin | undefined,
           file: { app: string; product: string; path: string }) => void;
}) {
  const [selection, setSelection] = useState<TreeSelection | null>(null);
  // "+" beside a screen: the main area shows the new-scenario form for it.
  const [creating, setCreating] = useState(false);
  useEffect(() => {
    if (selection?.path) setCreating(false);
  }, [selection?.path]);

  /* The login this screen's scenarios run under. It belongs to the PANEL,
     not to the scenario: a file in products/ is written once and run by
     whoever needs it, so a cif inside it would bind a shared test to one
     analyst. Kept across scenario and screen changes on purpose -- picking
     a user once and walking a screen's fifty rows is the whole point. */
  const [loginId, setLoginId] = useState("");
  const chosenUser = loginId ? users.byId(loginId) : null;

  /* Always a saved user: the list has no "use the scenario's own login"
     entry, so the first user is chosen until the analyst picks another. */
  useEffect(() => {
    const first = users.users[0];
    if (!chosenUser && first) setLoginId(first.id);
  }, [chosenUser, users.users]);

  /* Which tree nodes are open. Kept apart from `selection` because the first
     version expanded by selecting and so could never close anything -- with
     fifty scenarios under one screen the panel only ever grew. */
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (key: string) =>
    setExpanded((was) => {
      const next = new Set(was);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  const registry = selection
    ? products.registry(selection.app, selection.product)
    : null;
  const busy = selection
    ? products.registryBusy(selection.app, selection.product)
    : false;
  const problem = selection
    ? products.problem(selection.app, selection.product)
    : null;

  /* Re-read on every product change rather than trusting a cache: the
     .feature files are edited by hand in an IDE while this screen is open,
     and a step count from five minutes ago is the kind of wrong nobody
     notices. */
  useEffect(() => {
    if (selection) void products.openRegistry(selection.app, selection.product);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection?.app, selection?.product]);

  /* The tree lists SCREENS straight under a category, and a product's
     screens are only known once its registry is read -- so opening a
     category reads every product in it. A selected screen reads every
     product of its app too: "Taşı" lists their screens by name. */
  useEffect(() => {
    for (const product of products.products) {
      const name = product.category?.trim() ?? "";
      const open = !name || expanded.has(categoryKey(name))
        || product.app === selection?.app;
      if (open && !products.registry(product.app, product.product)) {
        void products.openRegistry(product.app, product.product);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.products, expanded, selection?.app]);

  /** Declared screens of every product of this app, the row's own excluded. */
  const targetsFor = (row: { flow: string }): MoveTarget[] => {
    if (!selection) return [];
    return products.products
      .filter((product) => product.app === selection.app)
      .flatMap((product) => {
        const reg = products.registry(product.app, product.product);
        const screens = reg
          ? reg.screens.filter((s) => s.declared).map((s) => ({ flow: s.flow, name: s.name || s.flow }))
          : product.flows.map((flow) => ({ flow, name: flow }));
        return screens.map((s) => ({
          product: product.product,
          flow: s.flow,
          label: s.name + (product.product === selection.product ? "" : " · " + product.name),
        }));
      })
      .filter((t) => !(t.product === selection.product && t.flow === row.flow));
  };

  const moveTo = async (row: { path: string }, target: MoveTarget) => {
    if (!selection) return;
    const result = await moveScenario(
      selection.app, selection.product, row.path, target.product, target.flow);
    if (!result.ok) {
      toast("Taşınamadı: " + result.error, true);
      return;
    }
    await products.openRegistry(selection.app, selection.product);
    if (target.product !== selection.product) {
      void products.openRegistry(selection.app, target.product);
    }
    toast("Senaryo " + target.label + " ekranına taşındı");
  };

  /* Changing screen closes whatever scenario was open. Without this the
     detail view would survive into a screen the file does not belong to,
     and the breadcrumb above it would be lying. */
  useEffect(() => {
    if (selection?.path) {
      void products.openScenario(selection.app, selection.product, selection.path);
    } else {
      products.closeScenario();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection?.app, selection?.product, selection?.flow, selection?.path]);

  const screen = selection?.flow
    ? registry?.screens.find((candidate) => candidate.flow === selection.flow) ?? null
    : null;

  const rows = selection?.flow
    ? (registry?.scenarios ?? []).filter((row) => row.flow === selection.flow)
    : registry?.scenarios ?? [];

  /* AI ile çalıştır: the same POST /explore as Ekran Keşfi's Serbest Gözlem.
     What that panel asks a person to type -- address, login, main menu, which
     screen -- is already known here from the tree, so only the scenario's own
     prose is left for the instruction. */
  const toast = useToast();
  const [aiAnswer, setAiAnswer] = useState("");
  const [aiClosed, setAiClosed] = useState(false);
  /* The walk rendered into steps, and whatever it could not express. Held
     beside the prose rather than written anywhere: it is a proposal until the
     analyst presses save. */
  const [aiGherkin, setAiGherkin] = useState("");
  const [aiNotes, setAiNotes] = useState<string[]>([]);
  useEffect(() => {
    setAiAnswer("");
    setAiClosed(false);
    setAiGherkin("");
    setAiNotes([]);
  }, [selection?.path]);

  const aiRun = useAsyncAction(async () => {
    const scenario = products.scenario;
    if (!selection || !scenario) return;
    if (!chosenUser) {
      toast("Önce yukarıdan bir CIF seçin — ajan onunla giriş yapar", true);
      return;
    }
    const screenName = screen?.name || selection.flow || "";
    const category = registry?.category ?? "";
    const text = [
      category
        ? `${category} menüsü altındaki "${screenName}" ekranına git.`
        : `"${screenName}" ekranına git.`,
      "Sonra şu senaryoyu uygula:",
      "",
      docAsText(scenario.doc),
    ].join("\n");

    setAiAnswer("");
    setAiClosed(false);
    setAiGherkin("");
    setAiNotes([]);
    const result = await explore({
      text,
      url: LOGIN_URL[selection.app.toLowerCase()],
      cif: chosenUser.cif,
      userId: chosenUser.userid,
      password: chosenUser.password,
      smsCode: TEST_SMS_CODE,
      mainMenu: category ? category.toLocaleUpperCase("tr-TR") : undefined,
    });
    if (!result.ok) {
      if (result.error === BROWSER_CLOSED) {
        setAiClosed(true);
        return;
      }
      toast("Ajan çalıştırılamadı: " + result.error, true);
      return;
    }
    setAiAnswer(result.body.answer);
    setAiGherkin(result.body.gherkin ?? "");
    setAiNotes(result.body.notes ?? []);
  });

  /* Senaryolar veritabanında; git'e girmeleri için .feature olarak yazılır.
     Hiçbir dosya silinmez -- veritabanında artık olmayan dosyalar yalnızca
     bildirilir, silmek bir insanın kararı. */
  const exportRun = useAsyncAction(async () => {
    const result = await exportScenarios();
    if (!result.ok) {
      toast("Dışa aktarılamadı: " + result.error, true);
      return;
    }
    const { written, unchanged, notInDatabase } = result.body;
    toast(written + " dosya yazıldı, " + unchanged + " dosya zaten güncel"
      + (notInDatabase.length > 0
        ? ". Veritabanında olmayan " + notInDatabase.length + " dosya silinmedi: "
          + notInDatabase.join(", ")
        : ""));
  });

  return (
    <div id="side-product" className={"side-panel product-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Kayıtlı Senaryolar">
        <button className="btn btn-ghost" type="button" disabled={exportRun.busy}
                title="Veritabanındaki senaryoları products/ altına .feature dosyası olarak yazar"
                onClick={() => exportRun.run()}>
          {exportRun.busy ? "Aktarılıyor…" : "Dosyaya aktar"}
        </button>
        <button className="btn btn-ghost" type="button" onClick={() => void products.reload()}>
          Yenile
        </button>
      </PanelHead>

      <div className="registry-body">
        <ProductTree
          products={products.products}
          registryOf={products.registry}
          selection={selection}
          onSelect={(next) => {
            setCreating(false);
            setSelection(next);
          }}
          onNewScenario={(screen) => {
            setSelection(screen);
            setCreating(true);
          }}
          expanded={expanded}
          onToggle={toggle}
          onCreateMenu={async (name, app) => {
            const result = await createMenu(app, name);
            if (!result.ok) return result.error;
            await products.reload();
            // Opened straight away, so its "+ Ekran ekle" is right there.
            setExpanded((was) => new Set(was).add(categoryKey(result.body.category ?? name)));
            toast(name + " eklendi");
            return null;
          }}
          onAddScreen={async (app, product, name) => {
            const result = await addScreen(app, product, name);
            if (!result.ok) return result.error;
            await products.openRegistry(app, product);
            setSelection({ app, product, flow: result.body.flow, path: null });
            toast(name + " eklendi");
            return null;
          }}
        />

        <section className="registry-main">
          {selection === null ? (
            <p className="registry-empty">
              Soldan bir ekran seçin. Ekran seçtiğinizde o ekrana kayıtlı
              senaryolar, bağlı oldukları kurallar ve koşmak için gereken veri listelenir.
            </p>
          ) : (
            <>
              <div className="registry-crumbs">
                {registry?.category ? <span>{registry.category}</span> : null}
                <span>{registry?.name ?? selection.product}</span>
                {screen?.catalog ? (
                  <span className="is-file">{"catalogs/" + screen.catalog + ".yaml"}</span>
                ) : null}
              </div>

              <div className="registry-titlebar">
                <h2 className="registry-title">
                  {screen?.name || selection.flow || registry?.name || selection.product}
                </h2>

                {/* The login sits ABOVE the scenario, beside the screen it
                    belongs to, because that is the scope it has: one choice
                    covers every row of this screen. Putting it inside the
                    detail view would ask the same question again for each
                    of fifty scenarios. It is a plain inline select rather
                    than the UserPicker the generation forms use -- that one
                    is a stacked field with its own hint and warnings, which
                    is right in a form and three lines too tall in a title
                    bar. */}
                <label className="registry-login">
                  <span className="registry-login-label">CIF</span>
                  <select
                    className="search"
                    value={loginId}
                    onChange={(event) => setLoginId(event.target.value)}
                  >
                    {users.users.length === 0 ? (
                      <option value="">(kayıtlı kullanıcı yok)</option>
                    ) : null}
                    {users.users.map((user) => (
                      <option key={user.id} value={user.id}>
                        {user.cif}
                        {user.name ? " — " + user.name : ""}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              {/* NGC logs in with cif AND user id. A saved login missing the
                  second half would be written as `userid ""` and fail in a
                  browser, so it is refused here, where it is fixable in one
                  click. NGI legitimately has no user id and is not asked. */}
              {chosenUser && selection.app.toUpperCase() !== "NGI" && !chosenUser.userid ? (
                <p className="product-warn">
                  Bu girişin kullanıcı kodu yok — NGC için zorunlu. Test Kullanıcıları
                  panelinden ekleyin.
                </p>
              ) : null}

              {/* A read failure, and nothing else, is allowed between the
                  title and the scenarios. It is not decoration: without it
                  the table below falls back to "Bu ekranda kayıtlı senaryo
                  yok", which is a different claim and a false one.

                  Two paragraphs that used to live here are gone. Neither was
                  wrong, both were PERMANENT: no screen in this product has a
                  catalog yet, so "kataloğu henüz üretilmedi" printed three
                  lines above every table, every time, and an undeclared
                  folder was already named "bildirilmemiş" in the tree. A
                  warning that is always on is furniture, and this screen is
                  read by someone scanning fifty rows. The catalog fact still
                  travels in three words: the breadcrumb above says "katalog
                  yok", and ProductPicker names every screen missing one at
                  the moment it matters -- the generation that would have to
                  run without it. */}
              {problem ? <p className="product-warn">Okunamadı: {problem}</p> : null}

              {/* Scenarios at every level. Picking the PRODUCT used to open
                  an overview instead -- its description, its counts, its
                  fifty rules, the state machine, the roles and the whole of
                  open-questions.md dumped as preformatted text. All of it
                  real, none of it what the panel is called: a registry of
                  scenarios answering "which of these can I run". The product
                  row now lists every scenario it owns, across screens, and a
                  screen row narrows that to one.

                  Where the rest went, since it was not noise:
                  - the rules are in the generation panels' ProductPicker,
                    beside the checkbox that decides whether each one reaches
                    the model, which is the only moment they are acted on;
                  - open-questions.md, flow.md and roles.md are repository
                    files. This panel is read-only BECAUSE they live in git,
                    so an IDE already shows them with history and a diff --
                    rendering them here bought a second, worse reader. */}
              {creating && selection.flow ? (
                <NewScenario
                  screen={screen?.name || selection.flow}
                  onCancel={() => setCreating(false)}
                  onSave={async (feature) => {
                    const result = await createScenarioFile(
                      selection.app, selection.product, selection.flow!, feature);
                    if (!result.ok) {
                      toast("Kaydedilemedi: " + result.error, true);
                      return false;
                    }
                    await products.openRegistry(selection.app, selection.product);
                    toast("Senaryo oluşturuldu");
                    setCreating(false);
                    setSelection({ ...selection, path: result.body.path });
                    return true;
                  }}
                />
              ) : selection.path ? (
                <ScenarioDetail
                  detail={products.scenario}
                  busy={products.scenarioBusy}
                  onBack={() => setSelection({ ...selection, path: null })}
                  onOpen={(feature) => onOpen(
                    feature,
                    {
                      app: selection.app,
                      flow: selection.flow ?? "",
                      intent: products.scenario?.description ?? "",
                      product: selection.product,
                    },
                    chosenUser
                      ? { cif: chosenUser.cif, userid: chosenUser.userid }
                      : undefined,
                    { app: selection.app, product: selection.product, path: selection.path! },
                  )}
                  onAiRun={() => aiRun.run()}
                  aiBusy={aiRun.busy}
                  aiAnswer={aiAnswer}
                  aiClosed={aiClosed}
                  aiGherkin={aiGherkin}
                  aiNotes={aiNotes}
                  onSave={async (edit) => {
                    const result = await products.saveScenario(
                      selection.app, selection.product, selection.path!, edit);
                    toast(result.ok ? "Senaryo kaydedildi" : "Kaydedilemedi: " + result.error,
                      !result.ok);
                    return result.ok;
                  }}
                />
              ) : (
                <ScenarioTable
                  scenarios={rows}
                  busy={busy}
                  onOpen={(scenario) =>
                    setSelection({ ...selection, flow: scenario.flow, path: scenario.path })}
                  targetsFor={targetsFor}
                  onMove={(scenario, target) => void moveTo(scenario, target)}
                />
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
