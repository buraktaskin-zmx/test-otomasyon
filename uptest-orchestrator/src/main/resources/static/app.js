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
        lastErrors: []
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

        return api("POST", "/api/v1/scenarios/validate", { feature: feature })
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

    function run() {
        el.btnRun.disabled = true;
        api("POST", "/api/v1/runs", { feature: el.editor.value }).then(function (result) {
            el.btnRun.disabled = false;

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
            "themeToggle", "toast"].forEach(function (id) { el[id] = $(id); });

        var savedTheme = null;
        try { savedTheme = localStorage.getItem("uptest.theme"); } catch (e) { /* ignore */ }
        if (savedTheme) { document.documentElement.setAttribute("data-theme", savedTheme); }

        el.editor.value = SAMPLE;
        syncGutter();

        el.editor.addEventListener("input", function () { syncGutter(); scheduleValidate(); });
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

        $("historyFilters").addEventListener("click", function (event) {
            var chip = event.target.closest(".chip");
            if (!chip) { return; }
            state.historyFilter = chip.dataset.filter;
            document.querySelectorAll("#historyFilters .chip").forEach(function (c) {
                c.classList.toggle("is-active", c === chip);
            });
            loadHistory();
        });

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
        loadHistory();
        setInterval(loadExecutors, 15000);
    }

    document.addEventListener("DOMContentLoaded", init);
})();
