import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { ProductTree } from "../../product/ProductTree";
import type { TreeSelection } from "../../product/ProductTree";
import { ScenarioDetail } from "../../product/ScenarioDetail";
import { ScenarioTable } from "../../product/ScenarioTable";
import type { useProducts } from "../../hooks/useProducts";
import type { Origin } from "../../api/types";

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
  onRun,
}: {
  active: boolean;
  products: ReturnType<typeof useProducts>;
  /** Hands a registered scenario to the ordinary run path. The registry does
   *  not grow a second way to start a run -- there is one gate, and it
   *  re-validates. */
  onRun: (feature: string, origin: Origin) => void;
}) {
  const [selection, setSelection] = useState<TreeSelection | null>(null);

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

  useEffect(() => {
    if (selection) void products.open(selection.app, selection.product);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection?.app, selection?.product]);

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

  const detail = selection ? products.detail(selection.app, selection.product) : null;
  const screen = selection?.flow
    ? registry?.screens.find((candidate) => candidate.flow === selection.flow) ?? null
    : null;

  const rows = selection?.flow
    ? (registry?.scenarios ?? []).filter((row) => row.flow === selection.flow)
    : registry?.scenarios ?? [];

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
              Soldan bir ürün ya da ekran seçin. Ekran seçtiğinizde o ekrana kayıtlı
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

              <h2 className="registry-title">
                {selection.flow ?? registry?.name ?? selection.product}
              </h2>

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
                  travels -- the breadcrumb above says "katalog yok" in three
                  words -- and the product overview still lists every screen
                  missing one, which is where a work queue belongs. */}
              {problem ? <p className="product-warn">Okunamadı: {problem}</p> : null}

              {selection.flow ? (
                selection.path ? (
                  <ScenarioDetail
                    detail={products.scenario}
                    busy={products.scenarioBusy}
                    onBack={() => setSelection({ ...selection, path: null })}
                    onRun={(feature) => onRun(feature, {
                      app: selection.app,
                      flow: selection.flow ?? "",
                      intent: products.scenario?.description ?? "",
                      product: selection.product,
                    })}
                  />
                ) : (
                  <ScenarioTable
                    scenarios={rows}
                    busy={busy}
                    onOpen={(scenario) =>
                      setSelection({ ...selection, path: scenario.path })}
                  />
                )
              ) : (
                <Overview products={products} selection={selection} detail={detail}
                          registry={registry} busy={busy} />
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

/** The product itself: what it claims, what it is missing, and its rule book. */
function Overview({
  selection,
  detail,
  registry,
  busy,
}: {
  products: ReturnType<typeof useProducts>;
  selection: TreeSelection;
  detail: ReturnType<ReturnType<typeof useProducts>["detail"]>;
  registry: ReturnType<ReturnType<typeof useProducts>["registry"]>;
  busy: boolean;
}) {
  if (!registry) {
    return <p className="registry-empty">{busy ? "Okunuyor…" : "Ürün okunamadı."}</p>;
  }

  return (
    <div className="product-body">
      {registry.description ? (
        <p className="product-rule-text">{registry.description}</p>
      ) : null}

      <p className="product-counts">
        {registry.scenarioCount} senaryo · {registry.ruleCount} kural ·{" "}
        {registry.assumptionCount} varsayım · {registry.messageCount} tanımlı mesaj ·{" "}
        {registry.screens.length} ekran
      </p>

      {registry.duplicateIds.length > 0 ? (
        <p className="product-warn">
          Aynı kimlik birden çok kez yazılmış: {registry.duplicateIds.join(", ")}. İlki
          geçerli, diğerleri yok sayıldı — rules.yaml’ı düzeltin.
        </p>
      ) : null}

      {registry.missingCatalogs.length > 0 ? (
        <p className="product-gap">
          Kataloğu olmayan ekranlar: {registry.missingCatalogs.join(", ")}. Hata değil,
          yapılacak işler listesi — kurallar ekrandan önce yazılabilsin diye böyle.
        </p>
      ) : null}

      {detail ? (
        <>
          {detail.openQuestions.trim() ? (
            <Section title="Cevaplanmamış sorular" warn>
              <p className="product-note">
                Bu metin modele <b>gönderilmez</b>. Varsayım üretilmesini engellemek için var.
              </p>
              <pre className="product-text">{detail.openQuestions}</pre>
            </Section>
          ) : null}

          <Section title={"Kurallar (" + detail.rules.length + ")"}>
            {detail.rules.length === 0 ? (
              <p className="suggest-hint">Hiç kural yazılmamış.</p>
            ) : (
              detail.rules.map((rule) => (
                <div key={rule.id} className="product-rule">
                  <div className="product-rule-head">
                    <span className="rule-id">{rule.id}</span>
                    <span className="rule-title">{rule.title || "(başlıksız)"}</span>
                    <span className={rule.confirmed ? "rule-ok" : "rule-guess"}>
                      {rule.confirmed ? "DOĞRULANDI" : "VARSAYIM"}
                    </span>
                  </div>
                  {rule.description ? (
                    <p className="product-rule-text">{rule.description}</p>
                  ) : null}
                  {rule.messages.map((message) => (
                    <p key={message} className="product-message">“{message}”</p>
                  ))}
                  {rule.source ? <p className="product-source">Kaynak: {rule.source}</p> : null}
                </div>
              ))
            )}
          </Section>

          {detail.stateMachine.trim() ? (
            <Section title="Akış">
              <pre className="product-text">{detail.stateMachine}</pre>
            </Section>
          ) : null}

          {detail.roles.trim() ? (
            <Section title="Roller">
              <pre className="product-text">{detail.roles}</pre>
            </Section>
          ) : null}
        </>
      ) : null}

      <p className="suggest-hint">
        Ürün: <code>products/{selection.app}/{selection.product}/</code>
      </p>
    </div>
  );
}

function Section({
  title,
  warn,
  children,
}: {
  title: string;
  warn?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section className={"product-section" + (warn ? " is-warn" : "")}>
      <h3 className="product-section-title">{title}</h3>
      {children}
    </section>
  );
}
