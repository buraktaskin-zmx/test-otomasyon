import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { ProductTree, categoryKey } from "../../product/ProductTree";
import type { TreeSelection } from "../../product/ProductTree";
import { ScenarioDetail } from "../../product/ScenarioDetail";
import { ScenarioTable } from "../../product/ScenarioTable";
import type { useProducts } from "../../hooks/useProducts";
import type { useTestUsers } from "../../hooks/useTestUsers";
import { explore } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
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
  onRun,
}: {
  active: boolean;
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  /** Hands a registered scenario to the ordinary run path, under the login
   *  picked above it. The registry does not grow a second way to start a run
   *  -- there is one gate, and it re-validates. */
  onRun: (feature: string, origin: Origin, login?: RunLogin) => void;
}) {
  const [selection, setSelection] = useState<TreeSelection | null>(null);

  /* The login this screen's scenarios run under. It belongs to the PANEL,
     not to the scenario: a file in products/ is written once and run by
     whoever needs it, so a cif inside it would bind a shared test to one
     analyst. Kept across scenario and screen changes on purpose -- picking
     a user once and walking a screen's fifty rows is the whole point. */
  const [loginId, setLoginId] = useState("");
  const chosenUser = loginId ? users.byId(loginId) : null;

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
     category reads every product in it. */
  useEffect(() => {
    for (const product of products.products) {
      const name = product.category?.trim() ?? "";
      const open = !name || expanded.has(categoryKey(name));
      if (open && !products.registry(product.app, product.product)) {
        void products.openRegistry(product.app, product.product);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [products.products, expanded]);

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
  useEffect(() => setAiAnswer(""), [selection?.path]);

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
      scenario.title,
      scenario.description,
    ].join("\n");

    setAiAnswer("");
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
      toast("Ajan çalıştırılamadı: " + result.error, true);
      return;
    }
    setAiAnswer(result.body.answer);
  });

  return (
    <div id="side-product" className={"side-panel product-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Kayıtlı Senaryolar">
        <button className="btn btn-ghost" type="button" onClick={() => void products.reload()}>
          Yenile
        </button>
      </PanelHead>

      <div className="registry-body">
        <ProductTree
          products={products.products}
          registryOf={products.registry}
          selection={selection}
          onSelect={setSelection}
          expanded={expanded}
          onToggle={toggle}
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
                {screen ? (
                  <span className="is-file">
                    {screen.catalog
                      ? "catalogs/" + screen.catalog + ".yaml"
                      : "katalog yok"}
                  </span>
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
                    <option value="">
                      {users.users.length === 0
                        ? "(kayıtlı kullanıcı yok)"
                        : "senaryodaki giriş"}
                    </option>
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
              {selection.path ? (
                <ScenarioDetail
                  detail={products.scenario}
                  busy={products.scenarioBusy}
                  onBack={() => setSelection({ ...selection, path: null })}
                  onRun={(feature) => onRun(
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
                  )}
                  onAiRun={() => aiRun.run()}
                  aiBusy={aiRun.busy}
                  aiAnswer={aiAnswer}
                />
              ) : (
                <ScenarioTable
                  scenarios={rows}
                  busy={busy}
                  onOpen={(scenario) =>
                    setSelection({ ...selection, flow: scenario.flow, path: scenario.path })}
                />
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
