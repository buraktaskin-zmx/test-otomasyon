import { useCallback, useEffect, useRef, useState } from "react";
import {
  createScenariosBulk, deleteScreenshot, getFlow, planFlow, putScreenshot,
  readScreen, saveCatalog, saveFlow, savePlan, screenshotHref, suggestFromFlow,
} from "../api/endpoints";
import type {
  FlowDraft, FlowNode, FlowTree, ProductSummary, ScreenRead, TestUser,
} from "../api/types";
import { PanelHead } from "../workspace/side/PanelHead";
import { ConfirmDialog } from "../ui/ConfirmDialog";
import { useToast } from "../ui/Toast";
import { useAsyncAction } from "../hooks/useAsyncAction";
import { FlowCanvas, newEdge, nextNodeId, placeChild } from "./FlowCanvas";
import { FlowRulesCard } from "./FlowRulesCard";
import { ScreenPreview } from "./ScreenPreview";

const EMPTY: FlowTree = {
  nodes: [], edges: [], intent: "", outOfScope: "", rules: "", preconditions: "",
};

/* A flow.json written before the free-text fields existed has no keys for
   them, and Jackson answers null rather than omitting them — `?? EMPTY` does
   not help, and a null in a <textarea value> makes React drop to uncontrolled
   and the box stops accepting input. */
function whole(flow: FlowTree | null | undefined): FlowTree {
  if (!flow) return EMPTY;
  return {
    nodes: flow.nodes ?? [],
    edges: flow.edges ?? [],
    intent: flow.intent ?? "",
    outOfScope: flow.outOfScope ?? "",
    rules: flow.rules ?? "",
    preconditions: flow.preconditions ?? "",
  };
}

/*
  Senaryo Üret — the analyst draws the flow, the vision model reads the
  screens, and the scenarios come from both plus the product's rules.

  Different from "AI ile Senaryo Üret" in two ways that matter: it is a FLOW
  rather than one screen, so it can produce journeys across screens; and it
  needs no running application, so tests can be written from a design mockup
  before the screen exists.

  The drawing is saved on the server, in the product's own folder — it is the
  documentation that replaces flow.md, and a canvas lost on F5 would be twenty
  minutes of work thrown away.
*/
export function FlowPanel({
  active,
  products,
  users,
  onBatch,
}: {
  active: boolean;
  products: ProductSummary[];
  users: TestUser[];
  /** Hands the produced scenarios to the existing batch review. */
  onBatch: (drafts: FlowDraft[], app: string, product: string) => void;
}) {
  const toast = useToast();
  const [app, setApp] = useState("");
  const [product, setProduct] = useState("");
  const [flow, setFlow] = useState<FlowTree>(EMPTY);
  /** nodeId -> how many pictures it has. A long page needs several. */
  const [screenshots, setScreenshots] = useState<Record<string, number>>({});
  const [reads, setReads] = useState<Record<string, string>>({});
  const [visionReady, setVisionReady] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [imageVersion, setImageVersion] = useState(1);
  const [deleting, setDeleting] = useState<FlowNode | null>(null);
  /** The node whose pictures are open full size, and which one. */
  const [preview, setPreview] = useState<{ nodeId: string; index: number } | null>(null);
  const [results, setResults] = useState<ScreenRead[] | null>(null);
  const [dirty, setDirty] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [count, setCount] = useState(25);
  const [loginId, setLoginId] = useState("");
  const [drafts, setDrafts] = useState<FlowDraft[] | null>(null);

  const chosenUser = users.find((u) => u.id === loginId) ?? users[0] ?? null;
  useEffect(() => {
    if (!loginId && users[0]) setLoginId(users[0].id);
  }, [loginId, users]);

  const chosen = products.find((p) => p.app === app && p.product === product) ?? null;

  /* First product by default: the panel is useless without one, and asking
     the analyst to pick before they can see anything is a step for nothing. */
  useEffect(() => {
    if (!chosen && products.length > 0 && products[0]) {
      setApp(products[0].app);
      setProduct(products[0].product);
    }
  }, [chosen, products]);

  const load = useCallback(async () => {
    if (!app || !product) return;
    setLoaded(false);
    const result = await getFlow(app, product);
    if (result.ok) {
      setFlow(whole(result.body.flow));
      setScreenshots(result.body.screenshots ?? {});
      setReads(result.body.reads ?? {});
      setVisionReady(result.body.visionReady);
      setResults(null);
      setDrafts(null);
      setSelected(null);
      setDirty(false);
    } else {
      toast("Akış okunamadı: " + result.error, true);
    }
    setLoaded(true);
  }, [app, product, toast]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const change = (next: FlowTree) => {
    setFlow(next);
    setDirty(true);
  };

  const save = useAsyncAction(async () => {
    const result = await saveFlow(app, product, flow);
    if (result.ok) {
      setDirty(false);
      toast("Akış kaydedildi");
    } else {
      toast("Kaydedilemedi: " + result.error, true);
    }
  });

  /* ------------------------------ the tree ------------------------------ */

  const addRoot = () => {
    const id = nextNodeId(flow.nodes);
    const y = flow.nodes.length === 0 ? 40
      : Math.max(...flow.nodes.map((n) => n.y)) + 220;
    change({
      ...flow,
      nodes: [...flow.nodes, { id, title: "Yeni ekran", flow: "", note: "", x: 40, y }],
    });
    setSelected(id);
  };

  const addChild = (parentId: string, side: "right" | "down") => {
    const parent = flow.nodes.find((n) => n.id === parentId);
    if (!parent) return;
    const id = nextNodeId(flow.nodes);
    const { x, y } = placeChild(flow, parent, side);
    change({
      ...flow,
      nodes: [...flow.nodes, { id, title: "Yeni ekran", flow: "", note: "", x, y }],
      edges: [...flow.edges, newEdge(parentId, id, flow.edges)],
    });
    setSelected(id);
  };

  const removeNode = async (node: FlowNode) => {
    // The screenshot is a file; deleting the box has to take it with it, or
    // the folder fills with images nothing points at.
    if ((screenshots[node.id] ?? 0) > 0) await deleteScreenshot(app, product, node.id);
    const next: FlowTree = {
      ...flow,
      nodes: flow.nodes.filter((n) => n.id !== node.id),
      edges: flow.edges.filter((e) => e.from !== node.id && e.to !== node.id),
    };
    setFlow(next);
    setSelected(null);
    const result = await saveFlow(app, product, next);
    setDirty(!result.ok);
    setScreenshots((was) => ({ ...was, [node.id]: 0 }));
  };

  const patchNode = (id: string, patch: Partial<FlowNode>) =>
    change({ ...flow, nodes: flow.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) });

  /* ---------------------------- screenshots ---------------------------- */

  const attach = useCallback(async (nodeId: string, file: Blob) => {
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
    const result = await putScreenshot(app, product, nodeId, dataUrl);
    if (result.ok) {
      // The count comes from the server rather than ++: it is the one that
      // knows whether this was an append or hit the per-screen ceiling.
      setScreenshots((was) => ({ ...was, [nodeId]: result.body.count }));
      setImageVersion((v) => v + 1);
      toast(result.body.count > 1
        ? result.body.count + ". görüntü eklendi — hepsi aynı ekranın parçası sayılır"
        : "Görüntü eklendi");
    } else {
      toast("Görüntü eklenemedi: " + result.error, true);
    }
  }, [app, product, toast]);

  /* Ctrl+V anywhere in the panel attaches to the SELECTED box — the way a
     person actually works: take a screenshot, click the box, paste. */
  const panel = useRef<HTMLDivElement>(null);
  /* One file input for the canvas, driven by whichever box's + was pressed.
     One per node would be N inputs in the DOM for a control used once. */
  const shotPicker = useRef<HTMLInputElement>(null);
  const pickingFor = useRef<string | null>(null);
  useEffect(() => {
    if (!active || !selected) return;
    const onPaste = (event: ClipboardEvent) => {
      const item = Array.from(event.clipboardData?.items ?? [])
        .find((entry) => entry.type.startsWith("image/"));
      const file = item?.getAsFile();
      if (!file) return;
      event.preventDefault();
      void attach(selected, file);
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }, [active, selected, attach]);

  /* ---------------------------- read screens ---------------------------- */

  /* Folds a read result into what the panel shows, whether it came from
     planning or from one box's "Yeniden oku". */
  const absorb = useCallback((screens: ScreenRead[]) => {
    if (screens.length === 0) return;
    setResults(screens);
    const next: Record<string, string> = {};
    screens.forEach((screen) => {
      if (screen.yaml) next[screen.nodeId] = screen.yaml;
    });
    setReads((was) => ({ ...was, ...next }));
  }, []);

  /* One screen, read again on purpose. There is no "read everything" button:
     planning reads what it has no draft for, because reading is what planning
     NEEDS rather than a decision an analyst makes. This is the stale case —
     the screenshot was replaced, or the note was written afterwards — which
     only the analyst can recognise, and it spends vision tokens every press. */
  const reread = useAsyncAction(async (nodeId: string) => {
    if (dirty) {
      const saved = await saveFlow(app, product, flow);
      if (!saved.ok) { toast("Önce akış kaydedilemedi: " + saved.error, true); return; }
      setDirty(false);
    }
    const result = await readScreen(app, product, nodeId);
    if (!result.ok) { toast("Ekran okunamadı: " + result.error, true); return; }
    absorb(result.body.screens);
    const failed = result.body.screens.find((screen) => screen.error);
    toast(failed ? "Okunamadı: " + failed.error : "Ekran yeniden okundu", Boolean(failed));
  });

  /* ------------------------- plan and generate ------------------------- */

  /* One button, both stages.

     The server side is unchanged and still two calls: one cheap planning call
     that writes the scenarios in Turkish, then one generation call per
     scenario. What is gone is the STOP in between -- the editable list of N
     scenarios that used to be shown for review before any generation was
     spent. It was the analyst's own request to remove it: twenty-five blocks
     of three textareas each is a screen nobody reads.

     The cost is real and worth stating once, where whoever reverses this will
     find it: nothing now checks WHICH scenarios were chosen before the
     expensive calls fire. A wrong plan is discovered as twenty-five wrong
     scenarios instead of as one wrong list. The plan is still written to
     plan.json, so it can be read after the fact.

     `planned` is used rather than the state that was just set: setPlan does
     not take effect until the next render, so savePlan would otherwise post
     the PREVIOUS plan -- or an empty one on the first run. */
  const produce = useAsyncAction(async () => {
    if (!chosenUser) { toast("Önce bir test kullanıcısı seçin.", true); return; }
    if (dirty) {
      const saved = await saveFlow(app, product, flow);
      if (!saved.ok) { toast("Önce akış kaydedilemedi: " + saved.error, true); return; }
      setDirty(false);
    }

    const planResult = await planFlow(app, product, count, []);
    if (!planResult.ok) { toast("Senaryolar planlanamadı: " + planResult.error, true); return; }
    const planned = planResult.body;
    setDrafts(null);
    absorb(planned.screens ?? []);
    if (planned.scenarios.length === 0) {
      planned.notes.forEach((note) => toast(note, true));
      toast("Model hiç senaryo döndürmedi; üretim yapılmadı.", true);
      return;
    }

    const saved = await savePlan(app, product, planned);
    if (!saved.ok) { toast("Plan kaydedilemedi: " + saved.error, true); return; }

    const result = await suggestFromFlow(app, product,
      { cif: chosenUser.cif, userid: chosenUser.userid, id: chosenUser.id }, []);
    if (!result.ok) { toast("Senaryolar üretilemedi: " + result.error, true); return; }
    setDrafts(result.body.scenarios);
    planned.notes.forEach((note) => toast(note, true));
    result.body.notes.forEach((note) => toast(note, true));
    const invalid = result.body.scenarios.filter((d) => !d.valid).length;
    toast(result.body.scenarios.length + " senaryo üretildi"
      + (invalid ? ", " + invalid + " doğrulamadan geçmedi" : "")
      + " · ~" + result.body.tokens.toLocaleString("tr-TR") + " token");
  });

  const saveAll = useAsyncAction(async () => {
    if (!drafts) return;
    /* A scenario is registered under the screen its path ENDS on: that is
       where the thing being tested happens. A journey through three screens
       belongs to the last one, not to the menu it started from. */
    const items = drafts.map((draft) => {
      const last = draft.path[draft.path.length - 1];
      const target = flow.nodes.find((n) => n.id === last);
      return { flow: (target?.flow || "").trim(), feature: draft.feature, heading: "" };
    });
    const missing = items.filter((item) => !item.flow).length;
    if (missing > 0) {
      toast(missing + " senaryonun ekranında katalog adı yok. Kutuları seçip"
        + " Katalog adı alanını doldurun.", true);
      return;
    }
    const result = await createScenariosBulk(app, product, items);
    if (!result.ok) { toast("Kaydedilemedi: " + result.error, true); return; }
    toast(result.body.created.length + " senaryo kaydedildi"
      + (result.body.refused.length ? ", " + result.body.refused.length + " reddedildi" : ""),
      result.body.refused.length > 0);
    result.body.refused.forEach((item) =>
      toast("#" + (item.index + 1) + " " + item.error, true));
  });

  const keepCatalog = async (screen: ScreenRead) => {
    const node = flow.nodes.find((n) => n.id === screen.nodeId);
    const name = node?.flow?.trim();
    if (!name) {
      toast("Önce bu ekrana bir katalog adı verin (ekran kutusunu seçip \"Katalog adı\").", true);
      return;
    }
    const result = await saveCatalog(app, name, screen.yaml ?? "");
    toast(result.ok ? "Katalog kaydedildi: " + app + "/" + name
      : "Katalog kaydedilemedi: " + result.error, !result.ok);
  };

  const node = flow.nodes.find((n) => n.id === selected) ?? null;
  /* Screens planning will have to pay a vision call for. Shown because that
     is the difference between a plan that takes seconds and one that takes a
     minute, and the analyst is the only one who can see it coming. */
  const unread = flow.nodes.filter((n) => (screenshots[n.id] ?? 0) > 0 && !reads[n.id]).length;
  /* A box with no screenshot is not an error — a screen may be drawn before
     anyone has one — but the model will see only its title, so say it once
     rather than let it surface as a plan that names no field. */
  const noShot = flow.nodes.filter((n) => (screenshots[n.id] ?? 0) === 0).length;
  /* Resolved from the id rather than stored whole: the box can be renamed or
     deleted while its picture is open, and a stale copy would outlive it. */
  const previewNode = preview
    ? flow.nodes.find((n) => n.id === preview.nodeId) ?? null
    : null;
  const busy = save.busy || reread.busy || produce.busy || saveAll.busy;

  return (
    <div id="side-flow" className={"side-panel flow-panel" + (active ? " is-active" : "")} ref={panel}>
      <PanelHead title="Senaryo Üret">
        <label className="fc-product">
          <span>Ürün</span>
          <select className="search" value={app + "/" + product} disabled={busy}
                  onChange={(event) => {
                    const [a, p] = event.target.value.split("/");
                    setApp(a ?? ""); setProduct(p ?? "");
                  }}>
            {products.length === 0 ? <option value="/">(ürün yok)</option> : null}
            {products.map((p) => (
              <option key={p.app + p.product} value={p.app + "/" + p.product}>
                {p.name} ({p.app.toUpperCase()})
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="btn btn-ghost" onClick={addRoot} disabled={save.busy}>
          + Ekran ekle
        </button>
        <button type="button" className="btn btn-ghost" disabled={!dirty || save.busy}
                onClick={() => void save.run()}>
          {save.busy ? "Kaydediliyor…" : dirty ? "Kaydet" : "Kaydedildi"}
        </button>
        <label className="fc-product">
          <span>Senaryo</span>
          <input className="search fc-count" type="number" min={1} max={40} value={count}
                 disabled={busy} aria-label="Kaç senaryo üretilsin"
                 onChange={(event) => setCount(Number(event.target.value) || 1)} />
        </label>
        {/* The login is chosen BEFORE the button now, not after the plan:
            there is no plan screen left to put it on, and the run grafts this
            user's login line into every scenario it writes. */}
        <label className="fc-product">
          <span>Test kullanıcısı</span>
          <select className="search" value={loginId} disabled={busy}
                  onChange={(event) => setLoginId(event.target.value)}>
            {users.length === 0 ? <option value="">(kayıtlı kullanıcı yok)</option> : null}
            {users.map((user) => (
              <option key={user.id} value={user.id}>
                {user.cif}{user.name ? " — " + user.name : ""}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="btn btn-primary"
                disabled={busy || flow.nodes.length === 0 || !chosenUser}
                title={!chosenUser
                  ? "Önce bir test kullanıcısı seçin"
                  : unread > 0
                    ? unread + " ekran henüz okunmadı; üretim önce onları okuyacak (yavaş)"
                    : undefined}
                onClick={() => void produce.run()}>
          {produce.busy
            ? (unread > 0 ? "Ekranlar okunuyor…" : "Üretiliyor…")
            : "Senaryo Üret"}
        </button>
      </PanelHead>

      <div className="flow-body">
        {!visionReady && loaded ? (
          <p className="product-warn">
            Görsel model kapalı: akışı çizebilir, görüntü ekleyebilir ve plan
            isteyebilirsiniz — ama ekranlar okunmaz, yani model alan adlarını
            görmez, yalnız kutu adlarını ve ok etiketlerini görür. Sunucuda
            <code> uptest.vision.enabled=true</code> ve
            <code> uptest.vision.api-key</code> gerekiyor.
          </p>
        ) : null}

        <p className="db-note">
          Kutular ekran, oklar geçiş. Bir okun etiketi, o geçişi yapan kontrolün
          ÜSTÜNDEKİ yazıdır ("Fatura Oluştur") — senaryo onu <code>click</code> olarak
          kullanır. Bir kutuyu seçip <strong>Ctrl+V</strong> ile ekran görüntüsü yapıştırın.
          Ekranları ayrıca okutmanız gerekmez: <strong>Senaryo Üret</strong> taslağı
          olmayanları kendisi okur.
        </p>

        {noShot > 0 && flow.nodes.length > 0 && loaded ? (
          <p className="db-note">
            {noShot} kutuda ekran görüntüsü yok — model o ekranların yalnız adını
            görecek, alanlarını görmeyecek.
          </p>
        ) : null}

        {flow.nodes.length === 0 && loaded ? (
          <p className="db-empty">Henüz ekran yok. "+ Ekran ekle" ile başlayın.</p>
        ) : (
          <FlowCanvas
            app={app} product={product} flow={flow} screenshots={screenshots}
            imageVersion={imageVersion} selected={selected} busy={busy}
            onSelect={setSelected} onChange={change} onAddChild={addChild}
            onDeleteNode={(id) => setDeleting(flow.nodes.find((n) => n.id === id) ?? null)}
            onPreview={(nodeId, index) => setPreview({ nodeId, index })}
            onAddShot={(nodeId) => { pickingFor.current = nodeId; shotPicker.current?.click(); }}
          />
        )}

        {node ? (
          <section className="fc-editor">
            <h3 className="db-card-title">{node.title}</h3>
            <div className="fc-editor-grid">
              <label className="users-field">
                <span>Ekran adı</span>
                <input className="search" value={node.title}
                       onChange={(event) => patchNode(node.id, { title: event.target.value })} />
              </label>
              <label className="users-field">
                <span>Katalog adı</span>
                <input className="search" value={node.flow} placeholder="ör. firma-fatura-islemleri"
                       onChange={(event) => patchNode(node.id, { flow: event.target.value })} />
              </label>
            </div>
            <label className="users-field">
              <span>Not — görüntüde görünmeyen, modelin bilmesi gereken şey</span>
              <textarea className="search fc-note" rows={2} value={node.note}
                        placeholder="ör. bu ekran yalnız yetkili kullanıcıda açılır; listede üç kayıt var"
                        onChange={(event) => patchNode(node.id, { note: event.target.value })} />
            </label>
            <div className="fc-editor-actions">
              <label className="btn btn-ghost fc-file">
                Görüntü seç
                <input type="file" accept="image/png,image/jpeg" hidden
                       onChange={(event) => {
                         const file = event.target.files?.[0];
                         if (file) void attach(node.id, file);
                         event.target.value = "";
                       }} />
              </label>
              {(screenshots[node.id] ?? 0) > 0 ? (
                <button type="button" className="btn btn-ghost" disabled={busy || !visionReady}
                        title={!visionReady ? "Görsel model kapalı (uptest.vision.enabled)"
                          : reads[node.id]
                            ? "Kayıtlı taslağı atıp bu ekranı yeniden okutur"
                            : "Bu ekranı şimdi okutur; planlama da okuyacaktı"}
                        onClick={() => void reread.run(node.id)}>
                  {reread.busy ? "Okunuyor…" : reads[node.id] ? "Yeniden oku" : "Ekranı oku"}
                </button>
              ) : null}
              {(screenshots[node.id] ?? 0) > 0 ? (
                <button type="button" className="btn btn-ghost" disabled={busy}
                        onClick={async () => {
                  await deleteScreenshot(app, product, node.id);
                  setScreenshots((was) => ({ ...was, [node.id]: 0 }));
                  setReads((was) => { const next = { ...was }; delete next[node.id]; return next; });
                }}>Tüm görüntüleri kaldır</button>
              ) : null}
              <span className="db-note">
                {(screenshots[node.id] ?? 0) > 0
                  ? (screenshots[node.id] ?? 0) + " görüntü · Ctrl+V bir tane daha ekler"
                  : "Ya da kutu seçiliyken Ctrl+V."}
              </span>
            </div>
            {reads[node.id] ? (
              <details className="fc-read">
                <summary>Modelin okuduğu katalog taslağı</summary>
                <pre className="yaml-output">{reads[node.id]}</pre>
              </details>
            ) : null}
          </section>
        ) : null}

        <section className="fc-editor">
          <h3 className="db-card-title">Üretim bağlamı</h3>
          <label className="users-field">
            <span>Neyi test etmek istiyorsunuz</span>
            <textarea className="search fc-note" rows={2} value={flow.intent}
                      placeholder="ör. fatura oluşturmanın mutlu yolu ve tutar/tarih doğrulamaları"
                      onChange={(event) => change({ ...flow, intent: event.target.value })} />
          </label>
          <label className="users-field">
            <span>Kapsam dışı — neye dokunulmasın</span>
            <textarea className="search fc-note" rows={2} value={flow.outOfScope}
                      placeholder="ör. dosya yükleme dalı; fatura iptali"
                      onChange={(event) => change({ ...flow, outOfScope: event.target.value })} />
          </label>
        </section>

        <FlowRulesCard flow={flow} busy={busy} onChange={change} />

        {drafts || produce.busy ? (
          <>
            <section className="fc-editor">
              <div className="fc-editor-actions">
                {drafts ? (
                  <button type="button" className="btn btn-ghost" disabled={busy}
                          onClick={() => void saveAll.run()}>
                    {saveAll.busy ? "Kaydediliyor…" : "Hepsini kütüphaneye kaydet"}
                  </button>
                ) : null}
                {drafts ? (
                  <button type="button" className="btn btn-ghost" disabled={busy}
                          onClick={() => onBatch(drafts, app, product)}>
                    Editörde incele
                  </button>
                ) : null}
              </div>
              {produce.busy ? (
                <p className="db-note">
                  Her senaryo ayrı bir model çağrısı, dörder dörder paralel.
                  {" " + count} senaryo birkaç dakika sürebilir.
                </p>
              ) : null}
              {drafts ? (
                <ul className="fc-drafts">
                  {drafts.map((draft) => (
                    <li key={draft.index}>
                      <span className={"pill pill-" + (draft.valid ? "DONE" : "FAILED")}>
                        {draft.valid ? "geçerli" : draft.validation.errors.length + " bulgu"}
                      </span>
                      <span className="fc-plan-title">{draft.title}</span>
                      {draft.attempts > 1 ? (
                        <span className="db-note">{draft.attempts} deneme</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : null}
            </section>
          </>
        ) : null}

        {results ? (
          <section className="fc-editor">
            <h3 className="db-card-title">Okuma sonucu</h3>
            <p className="db-note">
              Bu bir TASLAK. Bir insan okuyup düzeltmeden katalog değildir — görsel
              model etiketleri yanlış okuyabilir, kapalı bir listenin seçeneklerini göremez.
            </p>
            {results.map((screen) => (
              <div key={screen.nodeId} className="fc-result">
                <div className="fc-result-head">
                  <strong>{screen.title}</strong>
                  {screen.yaml ? (
                    <button type="button" className="btn btn-ghost"
                            onClick={() => void keepCatalog(screen)}>
                      Katalog olarak kaydet
                    </button>
                  ) : null}
                </div>
                {screen.error ? <p className="product-warn">{screen.error}</p> : null}
                {screen.problems.map((problem, i) => (
                  <p key={i} className="product-warn">{problem}</p>
                ))}
                {screen.yaml ? <pre className="yaml-output">{screen.yaml}</pre> : null}
              </div>
            ))}
          </section>
        ) : null}
      </div>

      <input ref={shotPicker} type="file" accept="image/png,image/jpeg" hidden
             onChange={(event) => {
               const file = event.target.files?.[0];
               const nodeId = pickingFor.current;
               if (file && nodeId) void attach(nodeId, file);
               event.target.value = "";
               pickingFor.current = null;
             }} />

      {previewNode ? (
        <ScreenPreview
          title={previewNode.title}
          count={screenshots[previewNode.id] ?? 0}
          start={preview?.index ?? 0}
          busy={busy}
          href={(i) => screenshotHref(app, product, previewNode.id, imageVersion, i)}
          onDelete={async (i) => {
            const result = await deleteScreenshot(app, product, previewNode.id, i);
            if (!result.ok) { toast("Silinemedi: " + result.error, true); return; }
            setScreenshots((was) => ({ ...was, [previewNode.id]: result.body.count }));
            setImageVersion((v) => v + 1);
            if (result.body.count === 0) setPreview(null);
          }}
          onClose={() => setPreview(null)}
        />
      ) : null}

      {deleting ? (
        <ConfirmDialog
          title="Ekranı silmek istediğinize emin misiniz?"
          detail={"“" + deleting.title + "” ve ona bağlı oklar silinecek"
            + (screenshots[deleting.id] ? ", ekran görüntüsü de kaldırılacak" : "") + "."}
          confirmLabel="Sil"
          onCancel={() => setDeleting(null)}
          onConfirm={async () => { await removeNode(deleting); setDeleting(null); }}
        />
      ) : null}
    </div>
  );
}
