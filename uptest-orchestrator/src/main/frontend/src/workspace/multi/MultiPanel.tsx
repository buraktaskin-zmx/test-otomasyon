import { useEffect, useState } from "react";
import { PanelHead } from "../side/PanelHead";
import { suggestMany } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import type { useProducts } from "../../hooks/useProducts";
import { buildMultiIntent } from "../../lib/multiDepth";
import { NO_PRODUCT, ProductPicker } from "../../product/ProductPicker";
import { UserPicker } from "../../users/UserPicker";
import type { useTestUsers } from "../../hooks/useTestUsers";
import type { ProductSelection } from "../../product/ProductPicker";
import type { Depth } from "../../lib/multiDepth";
import { Badge } from "../../ui/Badge";
import { useToast } from "../../ui/Toast";
import { DepthChoiceGroup } from "./DepthChoiceGroup";
import type { Draft } from "./draft";
import type { Origin } from "../../api/types";

/* app.js:647-786 + index.html:102-161. */
export function MultiPanel({
  active,
  catalogs,
  products,
  users,
  preselect,
  onBatchGenerated,
  onFallbackChange,
}: {
  active: boolean;
  catalogs: string[];
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  preselect: string | null;
  onBatchGenerated: (drafts: Draft[], notes: string[]) => void;
  onFallbackChange: (origin: Origin) => void;
}) {
  const [selection, setSelection] = useState<ProductSelection>(NO_PRODUCT);
  const catalog = selection.catalog;
  const [userId, setUserId] = useState("");
  const chosen = userId ? users.byId(userId) : null;
  const cif = chosen?.cif ?? "";
  const userid = chosen?.userid ?? "";
  const [count, setCount] = useState(5);
  const [depth, setDepth] = useState<Depth>("confirm");
  const [note, setNote] = useState("");
  const [summary, setSummary] = useState<{ bad: number; total: number; tokens: number } | null>(null);
  const toast = useToast();

  const [appPart = "", flowPart = ""] = catalog.split("/");
  const isNGI = appPart.toUpperCase() === "NGI";

  useEffect(() => {
    if (preselect) setSelection((was) => ({ ...was, catalog: preselect }));
  }, [preselect]);

  useEffect(() => {
    onFallbackChange({ app: appPart, flow: flowPart, intent: note, product: selection.product });
  }, [appPart, flowPart, note, selection.product, onFallbackChange]);

  const generate = useAsyncAction(async () => {
    if (!catalog) {
      toast("Önce bir ekran seçin", true);
      return;
    }
    if (!count || count < 1) {
      toast("Senaryo sayısı en az 1 olmalı", true);
      return;
    }
    // Required for a batch, optional for a single scenario -- each draft is
    // its own model call, so a blank cif means N independently invented
    // customer numbers for one analyst asking for one user, N ways.
    if (!cif) {
      toast(
        "Çoklu üretim için CIF zorunlu: boş bırakılırsa her senaryoda farklı müşteri numarası çıkar",
        true,
      );
      return;
    }
    if (!isNGI && !userid) {
      toast("NGC için kullanıcı ID zorunlu", true);
      return;
    }

    const intent = buildMultiIntent(depth, note);
    setSummary(null);

    const result = await suggestMany({
      app: appPart, flow: flowPart, intent, cif, userid, count,
      product: selection.product, ruleIds: selection.ruleIds,
    });
    if (!result.ok) {
      toast("Üretim başarısız: " + result.error, true);
      return;
    }

    // Carried on EVERY draft of the batch, exactly as app/flow are: each one
    // is independently editable and runnable, and each gets re-validated
    // against the rule book it was written under.
    const origin: Origin = { app: appPart, flow: flowPart, intent, product: selection.product };
    const drafts: Draft[] = result.body.scenarios.map((s) => ({
      label: s.label,
      feature: s.feature,
      validation: s.validation,
      attempts: s.attempts,
      values: s.values,
      ticked: s.ticked,
      excluded: s.excluded,
      origin,
    }));

    if (drafts.length === 0) {
      toast("Senaryo üretilemedi", true);
      return;
    }

    const bad = drafts.filter((d) => !d.validation.valid).length;
    setSummary({ bad, total: drafts.length, tokens: result.body.tokens });
    onBatchGenerated(drafts, result.body.notes);
    toast(
      bad
        ? drafts.length + " senaryo üretildi, " + bad + " tanesi geçersiz"
        : drafts.length + " senaryo üretildi ve doğrulandı",
      bad > 0,
    );
  });

  return (
    <div id="side-multi" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="Çoklu Üret" />
      <div className="suggest-form">
        <ProductPicker
          idPrefix="multi"
          products={products}
          catalogs={catalogs}
          value={selection}
          onChange={setSelection}
          disabled={generate.busy}
        />
        <UserPicker
          idPrefix="multi"
          users={users}
          value={userId}
          onChange={setUserId}
          needsUserid={!isNGI}
          disabled={generate.busy}
        />
        <label className="suggest-label" htmlFor="multiCount">
          Senaryo sayısı
        </label>
        <input
          id="multiCount"
          className="search"
          type="number"
          min={1}
          max={10}
          step={1}
          value={count}
          onChange={(event) => setCount(Number(event.target.value) || 0)}
        />
        <label className="suggest-label">Akış nereye kadar gitsin?</label>
        <DepthChoiceGroup value={depth} onChange={setDepth} />
        <label className="suggest-label" htmlFor="multiIntent">
          Ek not <span className="suggest-optional">(opsiyonel — boş bırakabilirsiniz)</span>
        </label>
        <textarea
          id="multiIntent"
          className="search suggest-intent"
          rows={3}
          placeholder="Örn: Fatura Tipi her senaryoda farklı olsun"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          onKeyDown={(event) => {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") generate.run();
          }}
        />
        <button
          id="btnMulti"
          className="btn btn-primary suggest-go"
          type="button"
          disabled={generate.busy || catalogs.length === 0}
          onClick={() => generate.run()}
        >
          {generate.busy ? "Üretiliyor…" : "Senaryoları Üret"}
        </button>
        <p className="suggest-hint">
          Her senaryo için <b>farklı bir veri kombinasyonu</b> orchestrator'da hesaplanır — modele
          "çeşitlendir" denmez, seçilen değerler <b>harfi harfine</b> verilir. Açılır listelerde
          katalogdaki seçenekler sırayla dolaşılır; tutar, tarih ve metin alanlarında sınır değerleri
          denenir. <code>shown_when</code> ile görünen bir alan yalnızca onu açan seçimi yapan
          senaryoda doldurulur. <b>Değer yazmayın</b> — alanların değerlerini planlayıcı seçiyor;
          nota yazılan bir değer iki kaynağı çeliştirir. Her senaryo <b>ayrı</b> doğrulanır ve ayrı
          onarılır, bu yüzden 5 senaryo 1-2 dakika sürer.
        </p>
      </div>
      <div id="multiStatus" className="suggest-status">
        {generate.busy ? (
          <div className="s-line">{count} senaryo eşzamanlı üretiliyor, bu 1-3 dakika sürebilir…</div>
        ) : summary ? (
          <div className="s-head">
            {summary.bad ? (
              <Badge variant="fail">{summary.bad} geçersiz</Badge>
            ) : (
              <Badge variant="ok">hepsi geçerli</Badge>
            )}
            <span className="s-line">{summary.total} senaryo</span>
            {summary.tokens ? (
              <span className="s-line">· {summary.tokens.toLocaleString("tr-TR")} token</span>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
