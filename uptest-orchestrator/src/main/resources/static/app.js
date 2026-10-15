/* =========================================================================
   Test Senaryo Stüdyosu — analyst front end.

   Plain ES modules-free JavaScript on purpose: corporate SSL inspection blocks
   outbound requests, so there is no CDN and no build step. Everything the page
   needs is served from the orchestrator jar.

   The whole UI is driven by the same three endpoints the orchestrator already
   exposes; nothing here re-derives state the backend owns.
   ========================================================================= */

(function () {
    "use strict";

    var POLL_MS = 2000;
    var VALIDATE_IDLE_MS = 700;

    var SAMPLE = [
        "Feature: Banko Ödeme Talebi",
        "",
        "  Scenario: Kullanıcı Banko ekranından ödeme talep eder",
        '    Given login with cif "DI00002018" and userid "3014"',
        '    And navigate to "NAKİT YÖNETİMİ" "Ödeme Oluştur / Talep Et"',
        '    And click "Ödeme Talep Et"',
        '    And enter "TCKN / VKN" "21203030786"',
        '    And enter amount "Ödeme Tutarı" "1250,55"',
        '    And select account "0006 7010 0000 0003 4322" from "Alıcı Hesabı"',
        '    And click "Devam"',
        '    Then verify the "Ödeme Tutarı" has amount "1250,55"',
        ""
    ].join("\n");

    /* Grouping is presentation only — the vocabulary itself always comes from
       GET /api/v1/vocabulary, never from a list maintained here. */
    var GROUPS = [
        { title: "Giriş",        test: function (p) { return /^login/.test(p); } },
        { title: "Navigasyon",   test: function (p) { return /^(go to|navigate to|click)/.test(p); } },
        { title: "Veri Girişi",  test: function (p) { return /^enter/.test(p); } },
        { title: "Seçim",        test: function (p) { return /^select/.test(p); } },
        { title: "Doğrulama",    test: function (p) { return /^verify/.test(p); } },
        { title: "Diğer",        test: function () { return true; } }
    ];

    var state = {
        vocabulary: [],
        runId: null,
        pollTimer: null,
        validateTimer: null,
        historyFilter: "ALL",
        lastErrors: [],
        /* The screen and intent the scenario in the editor was GENERATED
           from, remembered so that browsing the catalog list afterwards does
           not silently re-point validation at a different screen. */
        origin: null,
        /* A batch of generated drafts, each one its own scenario with its own
           validation result and its own origin. Empty for the ordinary
           one-scenario workflow, which must look and behave exactly as before.

           activeDraft is the index the editor currently holds, or null for
           "showing the list". Those are the only two states: there is no third
           where the editor shows a draft the list does not know about. */
        drafts: [],
        activeDraft: null,
        multiNotes: [],
    };

    var el = {};

    /* ============================ helpers ============================ */

    function $(id) { return document.getElementById(id); }

    function esc(s) {
        return String(s == null ? "" : s)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function toast(message, isError) {
        el.toast.textContent = message;
        el.toast.className = "toast is-shown" + (isError ? " is-error" : "");
        clearTimeout(toast._t);
        toast._t = setTimeout(function () { el.toast.className = "toast"; }, 3800);
    }

    function api(method, path, body) {
        var options = { method: method, headers: {} };
        if (body !== undefined) {
            options.headers["Content-Type"] = "application/json; charset=utf-8";
            options.body = JSON.stringify(body);
        }
        return fetch(path, options).then(function (response) {
            return response.text().then(function (text) {
                var parsed = null;
                if (text) { try { parsed = JSON.parse(text); } catch (e) { parsed = text; } }
                return { ok: response.ok, status: response.status, body: parsed };
            });
        });
    }

    function formatDuration(ms) {
        if (ms == null) { return "—"; }
        if (ms < 1000) { return ms + " ms"; }
        if (ms < 60000) { return (ms / 1000).toFixed(1) + " sn"; }
        return Math.floor(ms / 60000) + " dk " + Math.round((ms % 60000) / 1000) + " sn";
    }

    function formatTime(iso) {
        if (!iso) { return "—"; }
        var d = new Date(iso);
        if (isNaN(d.getTime())) { return "—"; }
        return d.toLocaleString("tr-TR", {
            day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit"
        });
    }

    /* Highlights {string}/{int} so an analyst can see at a glance which parts
       of a pattern they are expected to fill in. */
    function renderPattern(pattern) {
        return esc(pattern).replace(/\{(\w*)\}/g, '<span class="ph">{$1}</span>');
    }

    /* =========================== editor ============================ */

    function syncGutter() {
        var lines = el.editor.value.split("\n").length;
        var bad = {};
        state.lastErrors.forEach(function (e) { if (e.line) { bad[e.line] = true; } });

        var out = "";
        for (var i = 1; i <= lines; i++) {
            out += bad[i] ? '<span class="ln-bad">' + i + "</span>\n" : i + "\n";
        }
        el.gutter.innerHTML = out;
        el.gutter.scrollTop = el.editor.scrollTop;
    }

    function goToLine(line) {
        var lines = el.editor.value.split("\n");
        var offset = 0;
        for (var i = 0; i < line - 1 && i < lines.length; i++) { offset += lines[i].length + 1; }
        el.editor.focus();
        el.editor.setSelectionRange(offset, offset + (lines[line - 1] || "").length);
        // Rough scroll: line height is fixed by the monospace stack in app.css.
        el.editor.scrollTop = Math.max(0, (line - 6) * 21);
        syncGutter();
    }

    function replaceLine(line, text) {
        var lines = el.editor.value.split("\n");
        if (line < 1 || line > lines.length) { return; }
        var indent = (lines[line - 1].match(/^\s*/) || [""])[0];
        var keyword = (lines[line - 1].trim().match(/^(Given|When|Then|And|But)\s+/) || ["And "])[0];
        lines[line - 1] = indent + keyword.trim() + " " + text;
        el.editor.value = lines.join("\n");
        scheduleValidate();
        goToLine(line);
    }

    function insertAtCursor(pattern) {
        var value = el.editor.value;
        var pos = el.editor.selectionStart;
        var lineStart = value.lastIndexOf("\n", pos - 1) + 1;
        var lineEnd = value.indexOf("\n", pos);
        if (lineEnd === -1) { lineEnd = value.length; }

        var current = value.slice(lineStart, lineEnd);
        var text = current.trim() === ""
            ? "    And " + pattern
            : current + "\n    And " + pattern;

        el.editor.value = value.slice(0, lineStart) + text + value.slice(lineEnd);
        el.editor.focus();
        var caret = lineStart + text.length;
        el.editor.setSelectionRange(caret, caret);
        scheduleValidate();
        syncGutter();
    }

    /* ========================= vocabulary ========================== */

    function loadVocabulary() {
        return api("GET", "/api/v1/vocabulary").then(function (result) {
            if (!result.ok) {
                el.vocabList.innerHTML =
                    '<div class="empty"><div class="empty-title">Sözlük alınamadı</div>' +
                    "Hiçbir executor'a ulaşılamıyor.</div>";
                return;
            }
            state.vocabulary = result.body.steps || [];
            renderVocabulary("");
        });
    }

    function renderVocabulary(filter) {
        var needle = filter.trim().toLowerCase();
        var matching = state.vocabulary.filter(function (step) {
            return !needle || step.pattern.toLowerCase().indexOf(needle) !== -1;
        });

        if (!matching.length) {
            el.vocabList.innerHTML =
                '<div class="empty"><div class="empty-title">Eşleşen adım yok</div>' +
                esc(filter) + " aramasına karşılık gelen bir adım bulunamadı.</div>";
            return;
        }

        var used = {};
        var html = "";
        GROUPS.forEach(function (group) {
            var inGroup = matching.filter(function (step) {
                return !used[step.pattern] && group.test(step.pattern);
            });
            inGroup.forEach(function (step) { used[step.pattern] = true; });
            if (!inGroup.length) { return; }

            html += '<div class="vocab-group-title">' + esc(group.title) + "</div>";
            inGroup.forEach(function (step) {
                html += '<button class="vocab-item" type="button" data-pattern="' +
                    esc(step.pattern) + '" title="Senaryoya ekle">' +
                    renderPattern(step.pattern) + "</button>";
            });
        });
        el.vocabList.innerHTML = html;
    }

    /* ========================= validation ========================== */

    function scheduleValidate() {
        clearTimeout(state.validateTimer);
        el.validationBadge.className = "badge badge-idle";
        el.validationBadge.textContent = "yazılıyor…";
        state.validateTimer = setTimeout(validate, VALIDATE_IDLE_MS);
    }

    function validate() {
        var feature = el.editor.value;
        if (!feature.trim()) {
            state.lastErrors = [];
            el.validation.innerHTML = "";
            el.validationBadge.className = "badge badge-idle";
            el.validationBadge.textContent = "boş";
            syncGutter();
            return Promise.resolve(false);
        }

        var where = originScreen();
        return api("POST", "/api/v1/scenarios/validate", {
            feature: feature, app: where.app, flow: where.flow, intent: where.intent
        })
            .then(function (result) {
                if (result.status === 503) {
                    state.lastErrors = [];
                    el.validationBadge.className = "badge badge-fail";
                    el.validationBadge.textContent = "executor yok";
                    el.validation.innerHTML =
                        '<div class="v-error"><div class="v-error-head">' +
                        '<span class="v-msg">Hiçbir executor\'a ulaşılamadığı için doğrulama yapılamıyor.</span>' +
                        "</div></div>";
                    syncGutter();
                    return false;
                }
                if (!result.ok) {
                    toast("Doğrulama başarısız (HTTP " + result.status + ")", true);
                    return false;
                }
                renderValidation(result.body);
                return !!result.body.valid;
            });
    }

    function renderValidation(result) {
        state.lastErrors = result.errors || [];
        syncGutter();

        /* A draft carries its own verdict, so the card the analyst came from
           shows what the editor shows. Without this the badge on the list stays
           at whatever generation produced, and an error the analyst has just
           fixed still reads as an error. */
        if (state.activeDraft !== null && state.drafts[state.activeDraft]) {
            state.drafts[state.activeDraft].validation = result;
        }

        if (result.valid) {
            el.validationBadge.className = "badge badge-ok";
            el.validationBadge.textContent = result.stepCount + " adım geçerli";
            el.validation.innerHTML =
                '<div class="v-ok">✓ Senaryo geçerli — ' + result.stepCount + " adımın tamamı tanımlı.</div>";
            return;
        }

        el.validationBadge.className = "badge badge-fail";
        el.validationBadge.textContent = state.lastErrors.length + " hata";

        el.validation.innerHTML = state.lastErrors.map(function (error) {
            var head = '<div class="v-error-head">';
            if (error.line) {
                head += '<span class="v-line" data-line="' + error.line + '">Satır ' + error.line + "</span>";
            }
            head += '<span class="v-msg">' + esc(error.message) + "</span>";
            if (error.step) { head += '<span class="v-step">' + esc(error.step) + "</span>"; }
            head += "</div>";

            var suggestions = "";
            if (error.suggestions && error.suggestions.length) {
                suggestions = '<div class="v-suggestions"><span class="v-sug-label">Bunu mu demek istediniz:</span>' +
                    error.suggestions.map(function (s) {
                        return '<button class="sug" type="button" data-line="' + (error.line || 0) +
                            '" data-fix="' + esc(s) + '">' + renderPattern(s) + "</button>";
                    }).join("") + "</div>";
            }
            return '<div class="v-error">' + head + suggestions + "</div>";
        }).join("");
    }

    /* ============================= runs ============================= */

    /* The screen the scenario was written against, and what was asked of it.

       The analyst types nothing new for this: both already exist on screen,
       chosen and written to generate the scenario. They were simply being
       dropped — validate and run sent the feature text alone, so the server
       could apply the grammar checks and not the CATALOG ones. That is how an
       invented value survived an edit and cost a browser run: "Bayi Ünvanı"
       is readonly, the scenario never fills it, and the model asserted a
       company name it made up.

       The intent travels for a reason of its own: asserting a readonly field
       IS allowed when the analyst supplied the expected value, and without
       the intent the server cannot tell that apart from invention.

       Generation-time value first, the live dropdown only as a fallback. The
       list is there to be browsed, and a scenario must not start being
       checked against whichever screen the analyst last looked at — wrong
       catalog means invented errors, which teach an analyst to ignore the
       validator. Falling back keeps the check alive for a scenario typed by
       hand against a screen picked from the list. */
    function originScreen() {
        if (state.origin) {
            return state.origin;
        }
        var choice = (el.suggestCatalog.value || "").split("/");
        return {
            app: choice[0] || "",
            flow: choice[1] || "",
            intent: el.suggestIntent.value.trim()
        };
    }

    function run() {
        el.btnRun.disabled = true;
        var where = originScreen();
        api("POST", "/api/v1/runs", {
            feature: el.editor.value, app: where.app, flow: where.flow, intent: where.intent
        }).then(function (result) {
            /* Not unconditionally false: the analyst may have gone back to the
               draft list while this was in flight, and there is nothing to run
               from there. */
            el.btnRun.disabled = state.activeDraft === null && state.drafts.length > 0;

            if (result.status === 422) {
                renderValidation(result.body);
                showSide("vocab");
                toast("Senaryo çalıştırılmadı: tanımsız adım var", true);
                return;
            }
            if (result.status === 503) {
                toast((result.body && result.body.error) || "Uygun executor yok", true);
                return;
            }
            if (!result.ok) {
                toast("Koşu başlatılamadı (HTTP " + result.status + ")", true);
                return;
            }

            state.runId = result.body.runId;
            showSide("timeline");
            el.timelineDot.className = "tab-dot is-live";
            toast("Koşu başladı · " + result.body.executor);
            poll();
        });
    }

    function poll() {
        clearTimeout(state.pollTimer);
        if (!state.runId) { return; }

        api("GET", "/api/v1/runs/" + state.runId).then(function (result) {
            if (!result.ok) { return; }
            renderTimeline(result.body);

            if (result.body.status === "RUNNING") {
                state.pollTimer = setTimeout(poll, POLL_MS);
            } else {
                el.timelineDot.className = "tab-dot";
                loadHistory();
            }
        });
    }

    function renderTimeline(run) {
        var steps = run.steps || [];
        var current = typeof run.currentStepIndex === "number" ? run.currentStepIndex : -1;

        var statusPill = '<span class="pill pill-' + esc(run.status || "UNKNOWN") + '">' +
            esc(run.status || "UNKNOWN") + "</span>";
        el.runHeader.className = "run-header";
        el.runHeader.innerHTML = statusPill +
            '<span class="mono">' + esc(String(run.runId).slice(0, 8)) + "</span>" +
            "<span>" + esc(run.executor || "") + "</span>" +
            "<span>" + esc(formatTime(run.submittedAt)) + "</span>";

        if (!steps.length) {
            el.timeline.innerHTML =
                '<li class="empty"><div class="empty-title">Adımlar bekleniyor…</div>' +
                "Cucumber senaryoyu hazırlıyor.</li>";
            el.runFooter.innerHTML = "";
            return;
        }

        el.timeline.innerHTML = steps.map(function (step, index) {
            var status = step.status || "PENDING";
            var icon = { PASSED: "✓", FAILED: "✕", RUNNING: "●", SKIPPED: "–", UNDEFINED: "?", PENDING: "" }[status] || "";
            var error = step.error
                ? '<div class="tl-error">' + esc(step.error) + "</div>"
                : "";
            return '<li class="tl-step st-' + esc(status) + (index === current ? " is-current" : "") + '">' +
                '<span class="tl-icon">' + icon + "</span>" +
                '<div class="tl-body"><div class="tl-text">' +
                '<span class="tl-kw">' + esc(step.keyword || "") + "</span> " + esc(step.text || "") +
                "</div>" + error + "</div>" +
                '<span class="tl-time">' + esc(formatDuration(step.durationMs)) + "</span>" +
                "</li>";
        }).join("");

        var passed = steps.filter(function (s) { return s.status === "PASSED"; }).length;
        var total = steps.length;
        el.runFooter.innerHTML = '<span class="h-mono">' + passed + " / " + total + " adım geçti</span>";

        // Artifacts only exist once the run has written them.
        if (run.status !== "RUNNING") { renderArtifacts(run.runId); }
    }

    /* =========================== artifacts ========================== */

    var ARTIFACT_LABELS = {
        "report.html": "Raporu Aç",
        "run.log": "Günlüğü Aç",
        "scenario.feature": "Senaryoyu Aç"
    };

    function artifactLabel(name) {
        if (ARTIFACT_LABELS[name]) { return ARTIFACT_LABELS[name]; }
        if (/^traces\//.test(name)) { return "Trace İndir"; }
        return name;
    }

    function formatBytes(bytes) {
        if (bytes == null || bytes < 0) { return ""; }
        if (bytes < 1024) { return bytes + " B"; }
        if (bytes < 1024 * 1024) { return Math.round(bytes / 1024) + " KB"; }
        return (bytes / 1048576).toFixed(1) + " MB";
    }

    function renderArtifacts(runId) {
        api("GET", "/api/v1/runs/" + runId + "/artifacts").then(function (result) {
            if (!result.ok || !Array.isArray(result.body) || !result.body.length) { return; }

            var links = result.body.map(function (artifact) {
                var url = "/api/v1/runs/" + runId + "/artifacts/" + artifact.name
                    .split("/").map(encodeURIComponent).join("/");
                var isZip = /\.zip$/i.test(artifact.name);
                return '<a class="artifact" href="' + url + '"' +
                    (isZip ? "" : ' target="_blank" rel="noopener"') + '>' +
                    esc(artifactLabel(artifact.name)) +
                    '<span class="artifact-size">' + esc(formatBytes(artifact.sizeBytes)) + "</span></a>";
            }).join("");

            el.runFooter.innerHTML +=
                '<span class="artifact-sep"></span>' + links;
        });
    }

    /* ============================ history =========================== */

    function loadHistory() {
        return api("GET", "/api/v1/runs").then(function (result) {
            if (!result.ok) { return; }
            renderHistory(result.body || []);
        });
    }

    function renderHistory(runs) {
        var filtered = runs.filter(function (r) {
            return state.historyFilter === "ALL" || r.status === state.historyFilter;
        });

        if (!filtered.length) {
            el.historyBody.innerHTML =
                '<div class="empty"><div class="empty-title">Kayıt yok</div>' +
                "Bir senaryo çalıştırdığınızda burada listelenecek.</div>";
            return;
        }

        var rows = filtered.map(function (r) {
            var steps = r.steps || [];
            var passed = steps.filter(function (s) { return s.status === "PASSED"; }).length;
            var total = steps.reduce(function (sum, s) { return sum + (s.durationMs || 0); }, 0);
            return "<tr>" +
                '<td><span class="pill pill-' + esc(r.status || "UNKNOWN") + '">' + esc(r.status || "UNKNOWN") + "</span></td>" +
                '<td class="h-name">' + esc(r.name || "(isimsiz)") + "</td>" +
                '<td class="h-mono">' + esc(r.executor || "—") + "</td>" +
                '<td class="h-mono">' + esc(formatTime(r.submittedAt)) + "</td>" +
                '<td class="h-mono">' + esc(formatDuration(total || null)) + "</td>" +
                '<td class="h-mono">' + (steps.length ? passed + " / " + steps.length : "—") + "</td>" +
                '<td class="h-mono">' + esc(String(r.runId).slice(0, 8)) + "</td>" +
                '<td class="h-links">' +
                '<a class="artifact" target="_blank" rel="noopener" href="/api/v1/runs/' +
                encodeURIComponent(r.runId) + '/artifacts/report.html">Rapor</a>' +
                '<a class="artifact" target="_blank" rel="noopener" href="/api/v1/runs/' +
                encodeURIComponent(r.runId) + '/artifacts/run.log">Günlük</a>' +
                "</td>" +
                "</tr>";
        }).join("");

        el.historyBody.innerHTML =
            '<table class="history"><thead><tr>' +
            "<th>Durum</th><th>Senaryo</th><th>Executor</th><th>Başlangıç</th>" +
            "<th>Süre</th><th>Adım</th><th>Koşu</th><th>Dosyalar</th>" +
            "</tr></thead><tbody>" + rows + "</tbody></table>";
    }

    /* =========================== executors ========================== */

    function loadExecutors() {
        return api("GET", "/api/v1/executors").then(function (result) {
            if (!result.ok) { return; }
            var nodes = result.body || [];
            var up = nodes.filter(function (n) { return n.reachable; }).length;
            el.executorStatus.querySelector(".dot").className =
                "dot " + (up === 0 ? "dot-down" : "dot-up");
            el.executorLabel.textContent = up + " / " + nodes.length + " executor";
        });
    }

    /* ============================ AI suggest ========================= */

    /* The catalog list comes from the orchestrator, never from a list kept
       here: a new catalogs/<app>/<flow>.yaml must show up without a UI change. */
    function loadCatalogs() {
        return api("GET", "/api/v1/scenarios/catalogs").then(function (result) {
            var names = (result.ok && result.body && result.body.catalogs) || [];
            var options = names.length
                ? names.map(function (n) {
                    return '<option value="' + esc(n) + '">' + esc(n) + "</option>";
                  }).join("")
                : '<option value="">(katalog bulunamadı)</option>';

            /* Both generators read the same folder, so both lists are filled
               from this one response. A second fetch for the second panel would
               be the same answer twice and could disagree with itself. */
            el.suggestCatalog.innerHTML = options;
            el.multiCatalog.innerHTML = options;
            el.btnSuggest.disabled = names.length === 0;
            el.btnMulti.disabled = names.length === 0;
        });
    }

    function suggest() {
        var choice = el.suggestCatalog.value;
        var intent = el.suggestIntent.value.trim();

        if (!choice) { toast("Önce bir ekran seçin", true); return; }
        if (!intent) { toast("Ne test edileceğini yazın", true); el.suggestIntent.focus(); return; }

        var parts = choice.split("/");
        var cif = el.suggestCif.value.trim();
        var userid = el.suggestUserid.value.trim();

        /* The orchestrator builds the login step from these, so a missing
           userid would otherwise surface as a 400 after the model had already
           been called. */
        if (cif && parts[0].toUpperCase() !== "NGI" && !userid) {
            toast("NGC için kullanıcı ID zorunlu", true);
            el.suggestUserid.focus();
            return;
        }

        el.btnSuggest.disabled = true;
        el.btnSuggest.textContent = "Üretiliyor…";
        el.suggestStatus.innerHTML = '<div class="s-line">Model düşünüyor, bu 10-30 saniye sürebilir…</div>';

        api("POST", "/api/v1/scenarios/suggest", {
            app: parts[0], flow: parts[1], intent: intent, cif: cif, userid: userid
        }).then(function (result) {
            if (!result.ok) {
                var message = (result.body && (result.body.error || result.body.detail)) || ("HTTP " + result.status);
                el.suggestStatus.innerHTML = '<div class="s-line">' + esc(message) + "</div>";
                toast("Üretim başarısız: " + message, true);
                return;
            }

            var data = result.body || {};
            /* A single generation owns the editor outright: whatever batch was
               open is gone, and leaving its list half-showing would put two
               panels in charge of one textarea. */
            clearBatch();
            /* Pinned BEFORE validate() runs below, and before the analyst can
               touch the catalog list: this is the screen the text in the
               editor actually belongs to. */
            state.origin = { app: parts[0], flow: parts[1], intent: intent };
            /* Straight into the editor: the analyst reviews and edits there, and
               the existing validation panel already explains what is wrong. */
            el.editor.value = data.feature || "";
            syncGutter();
            validate();
            renderSuggestStatus(data);

            toast(data.valid
                ? "Senaryo üretildi ve doğrulandı"
                : "Taslak üretildi ama geçersiz — hataları düzeltin", !data.valid);
        }).catch(function (e) {
            el.suggestStatus.innerHTML = '<div class="s-line">' + esc(String(e)) + "</div>";
            toast("Üretim başarısız", true);
        }).then(function () {
            el.btnSuggest.disabled = false;
            el.btnSuggest.textContent = "Senaryo Üret";
        });
    }

    /* How many model calls it took is worth showing: two attempts means the
       first draft broke the vocabulary, which is a signal about the prompt. */
    function renderSuggestStatus(data) {
        var badge = data.valid
            ? '<span class="badge badge-ok">geçerli</span>'
            : '<span class="badge badge-fail">geçersiz</span>';

        var html = '<div class="s-head">' + badge +
            '<span class="s-line">' + (data.attempts || 1) + " model çağrısı</span></div>";

        (data.log || []).forEach(function (line) {
            html += '<div class="s-line mono">' + esc(line) + "</div>";
        });
        el.suggestStatus.innerHTML = html;
    }

    /* ========================= multi generation ====================== */

    /* One request, N drafts. The variation that makes each one different is
       decided server-side (VariationPlanner) and travels back with the draft, so
       the card titles say something real — "Fatura Tipi = E-Fatura" rather than
       "Senaryo 2". Nothing here re-derives it. */
    /* What the chosen depth means, spelled out for the model.

       These sentences are the whole reason the free-text box stopped being
       mandatory. Every other thing an intent used to carry is already decided
       elsewhere — the catalog names the fields and the navigation, the planner
       picks every value, LoginStep writes the login line — so the analyst was
       being made to type a paragraph that changed nothing. What it DID carry,
       invisibly, was this: whether to press the final confirm. Nothing in the
       system prompt says which buttons to press, so that decision was reaching
       the model as prose or not at all, on a screen where pressing it creates
       an irreversible record. Now it is a radio button and the wording is
       identical on every run.

       Deliberately silent about the success banner: rule 3 already tells the
       model to assert one only when the catalog carries success_message, so the
       catalog governs it and a sentence here could only contradict it. */
    var DEPTH = {
        confirm: "Formu doldur ve Devam'a bas. Onay düğmesine BASMA — bu senaryo " +
                 "kayıt oluşturmamalı. Devam'dan sonra gelen özet ekranında girdiğin " +
                 "değerleri doğrula.",
        submit:  "Formu doldur, Devam'a bas, özet ekranındaki değerleri doğrula, " +
                 "sonra Onay'a basarak işlemi tamamla."
    };

    function suggestMany() {
        var choice = el.multiCatalog.value;
        var count = Number(el.multiCount.value);

        if (!choice) { toast("Önce bir ekran seçin", true); return; }
        if (!count || count < 1) { toast("Senaryo sayısı en az 1 olmalı", true); el.multiCount.focus(); return; }

        /* Composed, never empty — so the server's "intent is required" gate is
           satisfied without the analyst typing anything. The optional note is
           appended rather than replacing: it adds to the instruction, it does
           not override which buttons get pressed. */
        var depth = document.querySelector('input[name="multiDepth"]:checked');
        var note = el.multiIntent.value.trim();
        var intent = DEPTH[depth ? depth.value : "confirm"] + (note ? "\n" + note : "");

        var parts = choice.split("/");
        var cif = el.multiCif.value.trim();
        var userid = el.multiUserid.value.trim();

        /* Required for a batch, optional for a single scenario. Each scenario
           of a batch is its own model call, so a blank cif means the model
           writes its own login line N separate times — N scenarios with N
           different customer numbers, when the analyst asked for ONE user
           exercising one screen N ways. The server refuses it too; checking
           here only saves the round trip. */
        if (!cif) {
            toast("Çoklu üretim için CIF zorunlu: boş bırakılırsa her senaryoda "
                + "farklı müşteri numarası çıkar", true);
            el.multiCif.focus();
            return;
        }
        if (parts[0].toUpperCase() !== "NGI" && !userid) {
            toast("NGC için kullanıcı ID zorunlu", true);
            el.multiUserid.focus();
            return;
        }

        el.btnMulti.disabled = true;
        el.btnMulti.textContent = "Üretiliyor…";
        /* Concurrent now (llm.batch-concurrency), so the wait is closer to one
           scenario's than to N. Each one still carries its own
           generate-validate-repair round, so it is not instant either — saying
           roughly how long beats a spinner that looks stuck. */
        el.multiStatus.innerHTML = '<div class="s-line">' + count +
            " senaryo eşzamanlı üretiliyor, bu 1-3 dakika sürebilir…</div>";

        api("POST", "/api/v1/scenarios/suggest-many", {
            app: parts[0], flow: parts[1], intent: intent,
            cif: cif, userid: userid, count: count
        }).then(function (result) {
            if (!result.ok) {
                var message = (result.body && (result.body.error || result.body.detail)) || ("HTTP " + result.status);
                el.multiStatus.innerHTML = '<div class="s-line">' + esc(message) + "</div>";
                toast("Üretim başarısız: " + message, true);
                return;
            }

            var data = result.body || {};
            var origin = { app: parts[0], flow: parts[1], intent: intent };

            state.drafts = (data.scenarios || []).map(function (s) {
                return {
                    label: s.label || "",
                    feature: s.feature || "",
                    validation: s.validation || { valid: !!s.valid, errors: [], stepCount: 0 },
                    attempts: s.attempts || 1,
                    values: s.values || {},
                    ticked: s.ticked || [],
                    excluded: s.excluded || [],
                    /* Pinned per draft: each one belongs to this screen and this
                       intent, and validating or running it must check it against
                       that catalog rather than whatever the list is showing. */
                    origin: origin
                };
            });
            state.multiNotes = data.notes || [];

            if (!state.drafts.length) {
                el.multiStatus.innerHTML = '<div class="s-line">Model hiç senaryo döndürmedi.</div>';
                toast("Senaryo üretilemedi", true);
                return;
            }

            renderMultiList();
            showListMode();

            var bad = state.drafts.filter(function (d) { return !d.validation.valid; }).length;
            /* Token cost shown, not hidden: it is the only figure that makes a
               batch comparable with an agent walk, and both used to be guesses.
               Omitted when the server reported none rather than printing a
               confident 0. */
            var tokens = data.tokens
                ? '<span class="s-line">· ' + data.tokens.toLocaleString("tr-TR") + " token</span>"
                : "";
            el.multiStatus.innerHTML = '<div class="s-head">' +
                (bad ? '<span class="badge badge-fail">' + bad + " geçersiz</span>"
                     : '<span class="badge badge-ok">hepsi geçerli</span>') +
                '<span class="s-line">' + state.drafts.length + " senaryo</span>" +
                tokens + "</div>";
            toast(bad
                ? state.drafts.length + " senaryo üretildi, " + bad + " tanesi geçersiz"
                : state.drafts.length + " senaryo üretildi ve doğrulandı", bad > 0);
        }).catch(function (e) {
            el.multiStatus.innerHTML = '<div class="s-line">' + esc(String(e)) + "</div>";
            toast("Üretim başarısız", true);
        }).then(function () {
            el.btnMulti.disabled = false;
            el.btnMulti.textContent = "Senaryoları Üret";
        });
    }

    /* The variation, as something a human can read at a glance. Three kinds of
       chip because the vocabulary has three shapes and they fail differently: a
       value is typed, a box is only ticked, and an excluded field must not be
       named at all (a shown_when field that is not on this screen costs a 30 s
       timeout, and a box ticked twice is UNticked). */
    function variationChips(draft) {
        var chips = "";
        Object.keys(draft.values || {}).forEach(function (label) {
            chips += '<span class="multi-chip"><span class="multi-chip-key">' + esc(label) +
                "</span>" + esc(draft.values[label]) + "</span>";
        });
        (draft.ticked || []).forEach(function (label) {
            chips += '<span class="multi-chip is-ticked">✓ ' + esc(label) + "</span>";
        });
        (draft.excluded || []).forEach(function (label) {
            chips += '<span class="multi-chip is-excluded">' + esc(label) + "</span>";
        });
        return chips;
    }

    function draftBadge(validation) {
        return validation.valid
            ? '<span class="badge badge-ok">' + (validation.stepCount || 0) + " adım</span>"
            : '<span class="badge badge-fail">' + ((validation.errors || []).length) + " hata</span>";
    }

    /* First lines of the scenario itself: the title plus the opening steps are
       what tell two drafts apart faster than any summary of them. */
    function previewOf(feature) {
        return String(feature || "").split("\n")
            .filter(function (line) { return line.trim() !== ""; })
            .slice(0, 4).join("\n");
    }

    function renderMultiList() {
        var notes = state.multiNotes.length
            ? '<div class="multi-notes">' + state.multiNotes.map(function (n) {
                return '<div class="multi-note">' + esc(n) + "</div>";
              }).join("") + "</div>"
            : "";

        var cards = state.drafts.map(function (draft, index) {
            return '<button class="multi-card" type="button" data-index="' + index + '">' +
                '<span class="multi-card-head">' +
                '<span class="multi-num">' + (index + 1) + "</span>" +
                '<span class="multi-card-title">' + esc(draft.label) + "</span>" +
                draftBadge(draft.validation) +
                "</span>" +
                '<span class="multi-chips">' + variationChips(draft) + "</span>" +
                '<span class="multi-preview">' + esc(previewOf(draft.feature)) + "</span>" +
                "</button>";
        }).join("");

        el.multiList.innerHTML =
            '<div class="multi-head">' +
            '<span class="multi-head-title">Üretilen senaryolar</span>' +
            '<span class="multi-head-count">' + state.drafts.length + " senaryo</span>" +
            "</div>" + notes +
            '<div class="multi-cards">' + cards + "</div>";
    }

    /* The list IS the view while no draft is open: the editor, its validation
       panel and the position bar all go away, and Doğrula/Çalıştır have nothing
       to act on. Leaving the buttons live would run whatever text the editor
       happened to hold from before the batch. */
    function showListMode() {
        state.activeDraft = null;
        /* A debounced validate from the last keystroke would land after this and
           overwrite the badge below with a verdict on text nobody is looking at. */
        clearTimeout(state.validateTimer);
        el.multiList.hidden = false;
        el.multiBar.hidden = true;
        el.editorWrap.hidden = true;
        el.validation.hidden = true;
        el.btnValidate.disabled = true;
        el.btnRun.disabled = true;
        el.validationBadge.className = "badge badge-idle";
        el.validationBadge.textContent = state.drafts.length + " senaryo";
    }

    /* Back to one editor over one scenario. Called both when a draft is opened
       and when a batch is abandoned (a fresh single generation), so it has to
       undo everything showListMode did. */
    function showEditorMode(inBatch) {
        el.multiList.hidden = true;
        el.multiBar.hidden = !inBatch;
        el.editorWrap.hidden = false;
        el.validation.hidden = false;
        el.btnValidate.disabled = false;
        el.btnRun.disabled = false;
    }

    function openDraft(index) {
        var draft = state.drafts[index];
        if (!draft) { return; }

        state.activeDraft = index;
        showEditorMode(true);

        el.editor.value = draft.feature;
        state.origin = draft.origin;
        el.multiPosition.textContent = "Senaryo " + (index + 1) + " / " + state.drafts.length;
        el.multiChips.innerHTML = variationChips(draft);

        /* The verdict is already known — it came back with the draft — so the
           panel is rendered from it rather than re-validated. A fresh call would
           cost a round trip to show the same thing, and renderValidation also
           drives the red line numbers in the gutter. */
        renderValidation(draft.validation);
    }

    /* Load-bearing: without it every edit the analyst makes is thrown away the
       moment they go back to the list, silently, and the card still shows the
       model's original text. */
    function saveActiveDraft() {
        if (state.activeDraft !== null && state.drafts[state.activeDraft]) {
            state.drafts[state.activeDraft].feature = el.editor.value;
        }
    }

    function backToList() {
        saveActiveDraft();
        renderMultiList();
        showListMode();
    }

    /* A single generation replaces the batch: one editor, one scenario, no list
       to go back to. Called by suggest() so the two panels cannot end up both
       claiming the editor. */
    function clearBatch() {
        state.drafts = [];
        state.multiNotes = [];
        state.activeDraft = null;
        showEditorMode(false);
    }

    /* ============================== views =========================== */

    function showView(name) {
        document.querySelectorAll(".tab").forEach(function (t) {
            t.classList.toggle("is-active", t.dataset.view === name);
        });
        $("view-workspace").classList.toggle("is-active", name === "workspace");
        $("view-history").classList.toggle("is-active", name === "history");
        if (name === "history") { loadHistory(); }
    }

    function showSide(name) {
        document.querySelectorAll(".side-tab").forEach(function (t) {
            t.classList.toggle("is-active", t.dataset.side === name);
        });
        $("side-vocab").classList.toggle("is-active", name === "vocab");
        $("side-timeline").classList.toggle("is-active", name === "timeline");
        $("side-suggest").classList.toggle("is-active", name === "suggest");
        $("side-multi").classList.toggle("is-active", name === "multi");
        $("side-agent").classList.toggle("is-active", name === "agent");
    }

    /* The MCP agent drives a real browser and the model re-reads the page
       between every action, so a run is minutes, not seconds. Both buttons
       share one request path; they differ only in which endpoint answers and
       therefore whether the reply is prose or YAML. */
    function runAgent(endpoint, label) {
        var instruction = el.agentInstruction.value.trim();
        if (!instruction) {
            toast("Ajanın ne yapacağını yazın", true);
            el.agentInstruction.focus();
            return;
        }

        el.btnAgentExplore.disabled = true;
        el.btnAgentCatalog.disabled = true;
        el.agentSaveRow.hidden = true;
        el.agentOutput.value = "";
        el.agentStatus.innerHTML =
            '<div class="s-line">' + esc(label) + ' — tarayıcı sürülüyor, bu birkaç dakika sürebilir…</div>';

        /* Credentials travel as their own fields, not pasted into the
           instruction. The server composes the login preamble from them, so
           the same wording is used every run and nobody retypes a CIF into
           free text. */
        var request = {
            text: instruction,
            url: el.agentUrl.value.trim(),
            cif: el.agentCif.value.trim(),
            userId: el.agentUserid.value.trim(),
            password: el.agentPassword.value,
            smsCode: el.agentSms.value.trim(),
            mainMenu: el.agentMainMenu.value.trim()
        };

        api("POST", endpoint, request).then(function (result) {
            if (!result.ok) {
                var body = result.body || {};
                var message = body.detail || body.error || ("HTTP " + result.status);
                el.agentStatus.innerHTML = '<div class="s-line">' + esc(message) + "</div>";
                toast("Ajan başarısız: " + message, true);
                return;
            }
            // /explore answers with "answer", /explore/catalog with "catalog".
            el.agentOutput.value = result.body.catalog || result.body.answer || "";

            /* The draft is checked mechanically before anyone reads it, so a
               type no step can drive or a missing entry_row is visible here
               rather than minutes later in a browser. */
            /* Only a catalog can be saved. A "Gez ve Anlat" answer is prose,
               and offering to store it as a .yaml would invite exactly that. */
            if (result.body.catalog) {
                el.agentSaveRow.hidden = false;
                suggestCatalogName(result.body.catalog);
            }

            var problems = result.body.problems || [];
            el.agentStatus.innerHTML = problems.length
                ? '<div class="s-line">Taslakta ' + problems.length + ' sorun var:</div>'
                  + problems.map(function (p) { return '<div class="s-line">• ' + esc(p) + "</div>"; }).join("")
                : '<div class="s-line">Tamamlandı — mekanik kontrollerden temiz geçti.</div>';
        }).catch(function (error) {
            el.agentStatus.innerHTML = '<div class="s-line">' + esc(String(error)) + "</div>";
        }).then(function () {
            el.btnAgentExplore.disabled = false;
            el.btnAgentCatalog.disabled = false;
        });
    }

    /* Fills the name boxes from the draft itself, so the usual case is to read
       the YAML and press save. The analyst can still overwrite either box —
       the flow name is a file name, and only they know whether this is a new
       screen or a better walk of one already stored. */
    function suggestCatalogName(yaml) {
        var app = /^app:\s*(\S+)/m.exec(yaml);
        var flow = /^flow:\s*(.+)$/m.exec(yaml);
        if (app && !el.agentSaveApp.value.trim()) {
            el.agentSaveApp.value = app[1].toLowerCase();
        }
        if (flow && !el.agentSaveFlow.value.trim()) {
            el.agentSaveFlow.value = slug(flow[1]);
        }
    }

    /* Turkish letters folded to ASCII on purpose: this becomes a file name, and
       CatalogService only builds a path from [A-Za-z0-9._-]. "Fatura Oluştur"
       would otherwise be refused for a reason nobody could see. */
    function slug(text) {
        var from = "çğıöşüÇĞİÖŞÜ";
        var to = "cgiosucgiosu";
        var out = text.trim().toLowerCase();
        for (var i = 0; i < from.length; i++) {
            out = out.split(from[i]).join(to[i]);
        }
        return out.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64);
    }

    /* Saving is what turns a draft into something the rest of the studio can
       use: the generator reads its list from the same folder, so the catalog
       appears in "AI Üret" as soon as the list is reloaded. */
    function saveCatalog() {
        var app = el.agentSaveApp.value.trim();
        var flow = el.agentSaveFlow.value.trim();
        var yaml = el.agentOutput.value;

        if (!app || !flow) {
            toast("Uygulama ve akış adı gerekli", true);
            return;
        }
        if (!yaml.trim()) {
            toast("Kaydedilecek bir katalog yok", true);
            return;
        }

        el.btnAgentSave.disabled = true;
        api("POST", "/api/v1/scenarios/catalogs", { app: app, flow: flow, yaml: yaml })
            .then(function (result) {
                if (!result.ok) {
                    var body = result.body || {};
                    toast("Kaydedilemedi: " + (body.error || ("HTTP " + result.status)), true);
                    return;
                }
                var name = (result.body && result.body.catalog) || (app + "/" + flow);
                toast(name + " kaydedildi");
                /* Reloaded rather than pushed into the list by hand: the folder
                   is the source of truth, and a name the server normalised
                   differently would otherwise be wrong here. */
                loadCatalogs().then(function () {
                    el.suggestCatalog.value = name;
                });
            })
            .catch(function (error) {
                toast("Kaydedilemedi: " + error, true);
            })
            .then(function () {
                el.btnAgentSave.disabled = false;
            });
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute("data-theme", theme);
        try { localStorage.setItem("uptest.theme", theme); } catch (e) { /* private mode */ }
    }

    /* ============================== init ============================ */

    function init() {
        ["editor", "gutter", "validation", "validationBadge", "btnValidate", "btnRun",
            "vocabList", "vocabSearch", "timeline", "timelineDot", "runHeader", "runFooter",
            "historyBody", "btnRefreshHistory", "executorStatus", "executorLabel",
            "themeToggle", "toast",
            "suggestCatalog", "suggestCif", "suggestUserid", "suggestUseridRow",
            "suggestIntent", "btnSuggest", "suggestStatus",
            "multiCatalog", "multiCif", "multiUserid", "multiUseridRow", "multiCount",
            "multiIntent", "btnMulti", "multiStatus",
            "multiList", "multiBar", "multiPosition", "multiChips", "btnBackToList",
            "agentUrl", "agentCif", "agentUserid", "agentPassword",
            "agentSms", "agentMainMenu",
            "agentInstruction", "btnAgentExplore", "btnAgentCatalog",
            "agentStatus", "agentOutput",
            "agentSaveRow", "agentSaveApp", "agentSaveFlow", "btnAgentSave"
        ].forEach(function (id) { el[id] = $(id); });

        /* The one element here with no id of its own: it is hidden and shown as a
           whole when the draft list takes over the pane, so it needs a handle. */
        el.editorWrap = document.querySelector(".editor-wrap");

        var savedTheme = null;
        try { savedTheme = localStorage.getItem("uptest.theme"); } catch (e) { /* ignore */ }
        if (savedTheme) { document.documentElement.setAttribute("data-theme", savedTheme); }

        el.editor.value = SAMPLE;
        syncGutter();

        /* saveActiveDraft on every keystroke rather than only on the way out: a
           side-panel switch, a regeneration or a closed tab all leave without
           pressing "← Tümü", and an edit lost that way is lost invisibly. */
        el.editor.addEventListener("input", function () {
            saveActiveDraft();
            syncGutter();
            scheduleValidate();
        });
        el.editor.addEventListener("scroll", function () { el.gutter.scrollTop = el.editor.scrollTop; });

        el.btnValidate.addEventListener("click", function () { validate(); });
        el.btnRun.addEventListener("click", run);

        el.vocabSearch.addEventListener("input", function () { renderVocabulary(el.vocabSearch.value); });

        el.vocabList.addEventListener("click", function (event) {
            var item = event.target.closest(".vocab-item");
            if (item) { insertAtCursor(item.dataset.pattern); }
        });

        el.validation.addEventListener("click", function (event) {
            var jump = event.target.closest(".v-line");
            if (jump) { goToLine(Number(jump.dataset.line)); return; }
            var fix = event.target.closest(".sug");
            if (fix) { replaceLine(Number(fix.dataset.line), fix.dataset.fix); }
        });

        document.querySelectorAll(".tab").forEach(function (tab) {
            tab.addEventListener("click", function () { showView(tab.dataset.view); });
        });
        document.querySelectorAll(".side-tab").forEach(function (tab) {
            tab.addEventListener("click", function () { showSide(tab.dataset.side); });
        });

        el.btnAgentExplore.addEventListener("click", function () {
            runAgent("/api/v1/explore", "Geziliyor");
        });
        el.btnAgentCatalog.addEventListener("click", function () {
            runAgent("/api/v1/explore/catalog", "Katalog çıkarılıyor");
        });
        el.btnAgentSave.addEventListener("click", saveCatalog);

        $("historyFilters").addEventListener("click", function (event) {
            var chip = event.target.closest(".chip");
            if (!chip) { return; }
            state.historyFilter = chip.dataset.filter;
            document.querySelectorAll("#historyFilters .chip").forEach(function (c) {
                c.classList.toggle("is-active", c === chip);
            });
            loadHistory();
        });

        el.btnSuggest.addEventListener("click", suggest);
        /* NGI logs in with a customer code only, so its userid box would be a
           field with nothing to put in it. */
        el.suggestCatalog.addEventListener("change", function () {
            var app = (el.suggestCatalog.value.split("/")[0] || "").toUpperCase();
            el.suggestUseridRow.classList.toggle("is-hidden", app === "NGI");
        });
        /* Ctrl+Enter from the intent box: the same shortcut people expect in
           any "type a prompt, press go" field. */
        el.suggestIntent.addEventListener("keydown", function (event) {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") { suggest(); }
        });

        el.btnMulti.addEventListener("click", suggestMany);
        el.multiCatalog.addEventListener("change", function () {
            var app = (el.multiCatalog.value.split("/")[0] || "").toUpperCase();
            el.multiUseridRow.classList.toggle("is-hidden", app === "NGI");
        });
        el.multiIntent.addEventListener("keydown", function (event) {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") { suggestMany(); }
        });
        el.multiList.addEventListener("click", function (event) {
            var card = event.target.closest(".multi-card");
            if (card) { openDraft(Number(card.dataset.index)); }
        });
        el.btnBackToList.addEventListener("click", backToList);

        el.btnRefreshHistory.addEventListener("click", loadHistory);
        el.executorStatus.addEventListener("click", function () {
            loadExecutors();
            loadVocabulary();
            toast("Executor durumu ve sözlük yenilendi");
        });

        el.themeToggle.addEventListener("click", function () {
            var current = document.documentElement.getAttribute("data-theme");
            applyTheme(current === "dark" ? "light" : "dark");
        });

        loadExecutors();
        loadVocabulary().then(validate);
        loadCatalogs();
        loadHistory();
        setInterval(loadExecutors, 15000);
    }

    document.addEventListener("DOMContentLoaded", init);
})();
