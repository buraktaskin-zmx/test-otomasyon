import { useEffect, useState } from "react";
import { PanelHead } from "./PanelHead";
import { suggestMany, suggestScenario } from "../../api/endpoints";
import { useAsyncAction } from "../../hooks/useAsyncAction";
import { buildMultiIntent } from "../../lib/multiDepth";
import { NO_PRODUCT, ProductPicker } from "../../product/ProductPicker";
import { UserPicker } from "../../users/UserPicker";
import type { useTestUsers } from "../../hooks/useTestUsers";
import type { ProductSelection } from "../../product/ProductPicker";
import type { useProducts } from "../../hooks/useProducts";
import type { Depth } from "../../lib/multiDepth";
import { Badge } from "../../ui/Badge";
import { useToast } from "../../ui/Toast";
import { DepthChoiceGroup } from "../multi/DepthChoiceGroup";
import type { Draft } from "../multi/draft";
import type { Origin, SuggestResponse } from "../../api/types";

const MAX_COUNT = 10;

type BatchSummary = { bad: number; total: number; tokens: number };

function SuggestStatus({
  busy,
  multi,
  count,
  response,
  batch,
  error,
}: {
  busy: boolean;
  multi: boolean;
  count: number;
  response: SuggestResponse | null;
  batch: BatchSummary | null;
  error: string | null;
}) {
  if (busy) {
    return (
      <div id="suggestStatus" className="suggest-status">
        <div className="s-line">
          {multi
            ? count + " senaryo eşzamanlı üretiliyor, bu 1-3 dakika sürebilir…"
            : "Model düşünüyor, bu 10-30 saniye sürebilir…"}
        </div>
      </div>
    );
  }
  if (error) {
    return (
      <div id="suggestStatus" className="suggest-status">
        <div className="s-line">{error}</div>
      </div>
    );
  }
  if (batch) {
    return (
      <div id="suggestStatus" className="suggest-status">
        <div className="s-head">
          {batch.bad
            ? <Badge variant="fail">{batch.bad} geçersiz</Badge>
            : <Badge variant="ok">hepsi geçerli</Badge>}
          <span className="s-line">{batch.total} senaryo</span>
          {batch.tokens
            ? <span className="s-line">· {batch.tokens.toLocaleString("tr-TR")} token</span>
            : null}
        </div>
      </div>
    );
  }
  if (!response) return <div id="suggestStatus" className="suggest-status" />;

  return (
    <div id="suggestStatus" className="suggest-status">
      <div className="s-head">
        <Badge variant={response.valid ? "ok" : "fail"}>{response.valid ? "geçerli" : "geçersiz"}</Badge>
        <span className="s-line">{response.attempts} model çağrısı</span>
      </div>
      {response.log.map((line, i) => (
        <div className="s-line mono" key={i}>
          {line}
        </div>
      ))}
    </div>
  );
}

/*
  AI ile Senaryo Üret -- one scenario, or with "Çoklu üret" ticked, N of them.

  Two endpoints behind one form, unchanged: /suggest for one draft into the
  editor, /suggest-many for a batch into the draft list. The checkbox only
  chooses which one is called. In batch mode the free text is the optional
  NOTE that buildMultiIntent appends to the depth instruction, exactly as
  the separate Çoklu Üret panel did.

  Pins origin on a successful generation only -- see lib/originScreen.ts.
  The live selection is reported upward on every change via
  `onFallbackChange` (used only when nothing has been pinned yet).
*/
export function SuggestPanel({
  active,
  catalogs,
  products,
  users,
  preselect,
  onGenerated,
  onBatchGenerated,
  onFallbackChange,
}: {
  active: boolean;
  catalogs: string[];
  products: ReturnType<typeof useProducts>;
  users: ReturnType<typeof useTestUsers>;
  /** Set right after a catalog is saved from Ekran Keşfi, so the newly
   *  saved screen is selected without a manual pick. */
  preselect: string | null;
  onGenerated: (feature: string, origin: Origin) => void;
  onBatchGenerated: (drafts: Draft[], notes: string[]) => void;
  onFallbackChange: (origin: Origin) => void;
}) {
  const [selection, setSelection] = useState<ProductSelection>(NO_PRODUCT);
  const catalog = selection.catalog;

  useEffect(() => {
    if (preselect) setSelection((was) => ({ ...was, catalog: preselect }));
  }, [preselect]);

  const [userId, setUserId] = useState("");
  const chosen = userId ? users.byId(userId) : null;
  const cif = chosen?.cif ?? "";
  const userid = chosen?.userid ?? "";
  const [intent, setIntent] = useState("");

  const [multi, setMulti] = useState(false);
  const [countText, setCountText] = useState("3");
  const count = Number.parseInt(countText, 10);
  const [depth, setDepth] = useState<Depth>("confirm");

  const [response, setResponse] = useState<SuggestResponse | null>(null);
  const [batch, setBatch] = useState<BatchSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

  const [appPart = "", flowPart = ""] = catalog.split("/");
  const isNGI = appPart.toUpperCase() === "NGI";

  useEffect(() => {
    onFallbackChange({ app: appPart, flow: flowPart, intent, product: selection.product });
  }, [appPart, flowPart, intent, selection.product, onFallbackChange]);

  const generateOne = async () => {
    if (!intent.trim()) {
      toast("Ne test edileceğini yazın", true);
      return;
    }
    // The orchestrator builds the login step from these, so a missing
    // userid would otherwise surface as a 400 AFTER the model was called.
    if (cif && !isNGI && !userid) {
      toast("NGC için kullanıcı ID zorunlu", true);
      return;
    }

    /* `product` belongs in the origin and `ruleIds` does not: the ticks
       shape the prompt, the product shapes the verdict. */
    const origin: Origin = { app: appPart, flow: flowPart, intent, product: selection.product };
    const result = await suggestScenario({ ...origin, cif, userid, ruleIds: selection.ruleIds });
    if (!result.ok) {
      setError(result.error);
      toast("Üretim başarısız: " + result.error, true);
      return;
    }

    setResponse(result.body);
    onGenerated(result.body.feature, origin);
    toast(
      result.body.valid ? "Senaryo üretildi ve doğrulandı" : "Taslak üretildi ama geçersiz — hataları düzeltin",
      !result.body.valid,
    );
  };

  const generateMany = async () => {
    if (!Number.isInteger(count) || count < 1 || count > MAX_COUNT) {
      toast("Senaryo sayısı 1 ile " + MAX_COUNT + " arasında bir sayı olmalı", true);
      return;
    }
    // Required for a batch, optional for a single scenario -- each draft is
    // its own model call, so a blank cif means N independently invented
    // customer numbers for one analyst asking for one user, N ways.
    if (!cif) {
      toast(
        "Çoklu üretim için test kullanıcısı zorunlu: boş bırakılırsa her senaryoda farklı müşteri numarası çıkar",
        true,
      );
      return;
    }
    if (!isNGI && !userid) {
      toast("NGC için kullanıcı ID zorunlu", true);
      return;
    }

    const batchIntent = buildMultiIntent(depth, intent);
    const result = await suggestMany({
      app: appPart, flow: flowPart, intent: batchIntent, cif, userid, count,
      product: selection.product, ruleIds: selection.ruleIds,
    });
    if (!result.ok) {
      setError(result.error);
      toast("Üretim başarısız: " + result.error, true);
      return;
    }

    // Carried on EVERY draft, exactly as app/flow are: each one is
    // independently editable and runnable, and re-validated against the
    // rule book it was written under.
    const origin: Origin = { app: appPart, flow: flowPart, intent: batchIntent, product: selection.product };
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
    setBatch({ bad, total: drafts.length, tokens: result.body.tokens });
    onBatchGenerated(drafts, result.body.notes);
    toast(
      bad
        ? drafts.length + " senaryo üretildi, " + bad + " tanesi geçersiz"
        : drafts.length + " senaryo üretildi ve doğrulandı",
      bad > 0,
    );
  };

  const generate = useAsyncAction(async () => {
    if (!catalog) {
      toast("Önce bir ekran seçin", true);
      return;
    }
    setError(null);
    setResponse(null);
    setBatch(null);
    await (multi ? generateMany() : generateOne());
  });

  return (
    <div id="side-suggest" className={"side-panel" + (active ? " is-active" : "")}>
      <PanelHead title="AI ile Senaryo Üret" />
      <div className="suggest-form">
        <ProductPicker
          idPrefix="suggest"
          products={products}
          catalogs={catalogs}
          value={selection}
          onChange={setSelection}
          disabled={generate.busy}
        />
        <UserPicker
          idPrefix="suggest"
          users={users}
          value={userId}
          onChange={setUserId}
          needsUserid={!isNGI}
          disabled={generate.busy}
        />

        <label className="multi-toggle">
          <input
            type="checkbox"
            checked={multi}
            disabled={generate.busy}
            onChange={(event) => setMulti(event.target.checked)}
          />
          <span>
            <b>Çoklu üret</b>
            <small>
              Tek istekten birden çok varyant üretir; her biri ayrı doğrulanır, siz birini
              seçersiniz.
            </small>
          </span>
        </label>

        {multi ? (
          <>
            <label className="suggest-label" htmlFor="suggestCount">Senaryo sayısı</label>
            <input
              id="suggestCount"
              className="search"
              type="text"
              inputMode="numeric"
              autoComplete="off"
              placeholder={"1-" + MAX_COUNT}
              value={countText}
              disabled={generate.busy}
              onChange={(event) => setCountText(event.target.value)}
            />
            <label className="suggest-label">Akış nereye kadar gitsin?</label>
            <DepthChoiceGroup value={depth} onChange={setDepth} />
          </>
        ) : null}

        <label className="suggest-label" htmlFor="suggestIntent">
          {multi ? (
            <>Ek not <span className="suggest-optional">(opsiyonel — boş bırakabilirsiniz)</span></>
          ) : "Ne test edilsin?"}
        </label>
        <textarea
          id="suggestIntent"
          className="search suggest-intent"
          rows={multi ? 3 : 5}
          placeholder={multi
            ? "Örn: Fatura Tipi her senaryoda farklı olsun"
            : "Örn: 1.250,55 TL ödeme talebi oluştur ve tutarı onay ekranında doğrula"}
          value={intent}
          onChange={(event) => setIntent(event.target.value)}
          onKeyDown={(event) => {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") generate.run();
          }}
        />
        <button
          id="btnSuggest"
          className="btn btn-primary suggest-go"
          type="button"
          disabled={generate.busy || catalogs.length === 0}
          onClick={() => generate.run()}
        >
          {generate.busy ? "Üretiliyor…" : multi ? "Senaryoları Üret" : "Senaryo Üret"}
        </button>
        <p className="suggest-hint">
          {multi
            ? "Her senaryonun verisini planlayıcı seçer — nota değer yazmayın. Her senaryo ayrı "
              + "doğrulanır; sonuçlar editörde liste olarak açılır."
            : "Model yalnızca sözlükteki adımları kullanabilir. Giriş adımını orchestrator yazar. "
              + "Taslak doğrulanır; geçersizse model bir kez düzeltmeyi dener. Sonuç editöre yazılır."}
        </p>
      </div>
      <SuggestStatus
        busy={generate.busy}
        multi={multi}
        count={count}
        response={response}
        batch={batch}
        error={error}
      />
    </div>
  );
}
