/*
 * Pravaha console -- the SQL Workbench island (design 23.7).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Mounted over a server-rendered form that already works: run once, register, see the
 * answer. This adds what makes it an IDE rather than a textarea with a Run button --
 * Monaco with Pravaha SQL highlighting, catalog-aware completion, validation as you type
 * with inline squiggles and fixes, the plan as a graph, a virtualised result grid,
 * registration with keys picked by name, several tabs of drafts kept in this browser,
 * and a snippet library.
 *
 * Every engine call goes through the console's own API, which goes through the engine's
 * public API. If the engine's HTTP surface is down the editor still edits, drafts still
 * save, and the status line says exactly which part is unavailable and why.
 */
import { html, render, call, announce, debounce, token, onThemeChange, store, copyText,
         errorView, isNumericType, states, t } from "pravaha/lib.js";
import { useEffect, useRef, useState, useCallback } from "preact/hooks";
import { Grid } from "pravaha/grid.js";
import { renderPlan, legend, exportSvg, loadGlobalScript } from "pravaha/plan-graph.js";
import { DiffPanel } from "pravaha/diff.js";

const mount = document.getElementById("workbench-app");
/* Read before the island replaces the server-rendered form it came in. */
const LINKED_SQL = (document.getElementById("sql") || {}).value || "";
const TABS_KEY = "pravaha.workbench.tabs";
const ACTIVE_KEY = "pravaha.workbench.active";
const SNIPPETS_KEY = "pravaha.workbench.snippets";
const MONACO = "/static/vendor/monaco/vs";
const WORKER = MONACO + "/assets/editor.worker-lj3bdIIn.js";
const CONTRIBUTIONS = "vs/toggleHighContrast-qGX7E9o7";
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;
/* CREATE / DROP / PAUSE / RESUME CONTINUOUS QUERY and SHOW CONTINUOUS QUERIES: the engine runs these
   when they are Run, and its planner does not validate them, so the editor does not ask it to. */
const STATEMENT = /^\s*(?:(?:--[^\n]*\n|\/\*[\s\S]*?\*\/)\s*)*(?:(?:CREATE|DROP|SHOW)\s+CONTINUOUS\b|PAUSE\b|RESUME\b)/i;

function newId() { return Math.random().toString(36).slice(2, 9); }
function tabTitle(sql, fallback) {
  const from = /\bFROM\s+(?:TABLE\s*\(\s*(?:TUMBLE|HOP)\s*\(\s*TABLE\s+)?([A-Za-z_]\w*)/i.exec(sql || "");
  return fallback || (from ? from[1] : t("wb.untitled"));
}
function typedParams(text) {
  return String(text || "").split(",").map((p) => p.trim()).filter(Boolean).map((p) => {
    if (/^-?\d+$/.test(p)) return Number(p);
    if (/^-?\d+\.\d+$/.test(p)) return Number(p);
    return p;
  });
}
function urlParam(name) { return new URLSearchParams(location.search).get(name); }
function setUrlParam(name, value) {
  const params = new URLSearchParams(location.search);
  if (value) params.set(name, value); else params.delete(name);
  ["query", "sql", "template", "stream", "new"].forEach((k) => params.delete(k));
  history.replaceState(null, "", location.pathname + (params.toString() ? "?" + params : ""));
}

/* ------------------------------------------------------------------ Monaco */

let monacoPromise = null;
function loadMonaco() {
  if (monacoPromise) return monacoPromise;
  monacoPromise = (async () => {
    /* ELK first: its UMD wrapper must see no AMD `define`, which Monaco's loader installs. */
    await loadGlobalScript("/static/vendor/elkjs/elk.bundled.js", "ELK").catch(() => null);
    await new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = MONACO + "/loader.js";
      script.onload = resolve; script.onerror = () => reject(new Error(t("wb.editor_failed")));
      document.head.appendChild(script);
    });
    self.MonacoEnvironment = { getWorker: () => new Worker(WORKER) };
    window.require.config({ paths: { vs: MONACO } });
    /* `vs/editor` is the editor core and its API; the Vite-named chunk beside it carries the
       editor contributions -- suggest, hover, find, quick fix, bracket matching. Without it
       the editor edits but completion and code actions never appear. */
    return new Promise((resolve, reject) =>
      window.require(["vs/editor", CONTRIBUTIONS], (editor) => resolve(editor), reject));
  })();
  return monacoPromise;
}

const LANG = "pravaha-sql";
let catalog = { streams: [], functions: [], keywords: [], types: [], views: [] };

function monarch() {
  return {
    ignoreCase: true,
    keywords: catalog.keywords.map((k) => k.toLowerCase()),
    builtins: catalog.functions.map((f) => f.name.toLowerCase()),
    typeKeywords: catalog.types.map((type) => type.toLowerCase()),
    streams: catalog.streams.map((s) => String(s.name).toLowerCase()),
    views: catalog.views.map((v) => String(v).toLowerCase()),
    tokenizer: {
      root: [
        [/--.*$/, "comment"],
        [/\/\*/, "comment", "@comment"],
        [/'([^']|'')*'/, "string"],
        [/"([^"]|"")*"/, "identifier.quoted"],
        [/\?/, "variable.parameter"],
        [/\d+(\.\d+)?/, "number"],
        [/[A-Za-z_]\w*/, { cases: {
          "@keywords": "keyword", "@builtins": "predefined", "@typeKeywords": "type",
          "@streams": "type.identifier", "@views": "type.identifier", "@default": "identifier" } }],
        [/[<>=!|%*/+-]+/, "operator"],
        [/[;,.()]/, "delimiter"],
      ],
      comment: [[/[^/*]+/, "comment"], [/\*\//, "comment", "@pop"], [/[/*]/, "comment"]],
    },
  };
}

function defineThemes(monaco) {
  const dark = document.documentElement.getAttribute("data-theme") === "dark"
    || document.documentElement.getAttribute("data-theme") === "terminal"
    || (!document.documentElement.getAttribute("data-theme")
        && window.matchMedia("(prefers-color-scheme: dark)").matches);
  const hex = (name, fallback) => {
    const v = token(name, fallback);
    return /^#[0-9a-f]{6}$/i.test(v) ? v.slice(1) : fallback.replace("#", "");
  };
  monaco.editor.defineTheme("pravaha", {
    base: dark ? "vs-dark" : "vs",
    inherit: true,
    /* Syntax colours are TEXT, so they come from the text tokens that the contrast test holds
       at 4.5:1 on --surface in every theme -- never from the data palette (--series-*), which is
       for marks on a chart and measured 2.7:1 as text. */
    rules: [
      { token: "keyword", foreground: hex("--flow", "#1B5FA8"), fontStyle: "bold" },
      { token: "predefined", foreground: hex("--info", "#274B6D") },
      { token: "type", foreground: hex("--slate", "#464C57") },
      { token: "type.identifier", foreground: hex("--ink", "#15181D"), fontStyle: "bold underline" },
      { token: "string", foreground: hex("--ok", "#1B6B3A") },
      { token: "number", foreground: hex("--warn", "#8A5A12") },
      { token: "comment", foreground: hex("--muted", "#646B78"), fontStyle: "italic" },
      { token: "variable.parameter", foreground: hex("--bad", "#A82121"), fontStyle: "bold" },
    ],
    colors: {
      "editor.background": "#" + hex("--surface", "#ffffff"),
      "editor.foreground": "#" + hex("--ink", "#15181D"),
      "editorLineNumber.foreground": "#" + hex("--muted", "#646B78"),
      "editorCursor.foreground": "#" + hex("--flow", "#1B5FA8"),
      /* Monaco's default occurrence highlight is a 25% grey that took the keyword colour
         under 4.5:1; the info tint keeps every syntax colour readable on it. */
      "editor.wordHighlightBackground": "#" + hex("--info-soft", "#E8EDF3"),
      "editor.wordHighlightStrongBackground": "#" + hex("--info-soft", "#E8EDF3"),
      "editor.selectionHighlightBackground": "#" + hex("--info-soft", "#E8EDF3"),
      /* The Compare panel's SQL diff. A changed line is tinted from the semantic soft tokens,
         which every syntax colour above is held readable on, and marked + or − in the gutter.
         The changed words are not tinted again: a second wash over the first took the number
         colour to 4.2:1 (found by axe), and the line, its sign and the plan diff say enough. */
      "diffEditor.insertedLineBackground": "#" + hex("--ok-soft", "#E6F2EA"),
      "diffEditor.removedLineBackground": "#" + hex("--bad-soft", "#F9E8E8"),
      "diffEditor.insertedTextBackground": "#00000000",
      "diffEditor.removedTextBackground": "#00000000",
      "diffEditorGutter.insertedLineBackground": "#" + hex("--ok-soft", "#E6F2EA"),
      "diffEditorGutter.removedLineBackground": "#" + hex("--bad-soft", "#F9E8E8"),
      "diffEditor.diagonalFill": "#" + hex("--rule", "#D9DEE6"),
    },
  });
  monaco.editor.setTheme("pravaha");
}

/* Which streams a statement reads, and under which aliases -- so completion after `t.`
   offers t's columns, and bare completion offers only columns that are in scope. */
function scope(sql) {
  const aliases = {};
  const inScope = new Set();
  const names = new Set(catalog.streams.map((s) => String(s.name).toLowerCase()));
  const re = /\b(?:FROM|JOIN|TABLE)\s+([A-Za-z_]\w*)(?:\s+(?:AS\s+)?([A-Za-z_]\w*))?/gi;
  let m;
  while ((m = re.exec(sql))) {
    const stream = m[1].toLowerCase();
    if (!names.has(stream)) continue;
    inScope.add(stream);
    const alias = m[2] && !/^(WHERE|GROUP|JOIN|ON|LEFT|INNER|AS|ORDER|HAVING|FOR)$/i.test(m[2]) ? m[2] : null;
    if (alias) aliases[alias.toLowerCase()] = stream;
    aliases[stream] = stream;
  }
  return { aliases, inScope };
}

function registerLanguage(monaco) {
  monaco.languages.register({ id: LANG });
  monaco.languages.setMonarchTokensProvider(LANG, monarch());
  monaco.languages.setLanguageConfiguration(LANG, {
    comments: { lineComment: "--", blockComment: ["/*", "*/"] },
    brackets: [["(", ")"]],
    autoClosingPairs: [{ open: "(", close: ")" }, { open: "'", close: "'", notIn: ["string"] }],
  });
  const Kind = monaco.languages.CompletionItemKind;
  monaco.languages.registerCompletionItemProvider(LANG, {
    triggerCharacters: [".", " "],
    provideCompletionItems(model, position) {
      const word = model.getWordUntilPosition(position);
      const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber,
                      startColumn: word.startColumn, endColumn: word.endColumn };
      const before = model.getValueInRange({ startLineNumber: 1, startColumn: 1,
                                             endLineNumber: position.lineNumber, endColumn: word.startColumn });
      const sql = model.getValue();
      const streamsByName = Object.fromEntries(catalog.streams.map((s) => [String(s.name).toLowerCase(), s]));
      const fieldItems = (stream, sortPrefix) => (stream.fields || []).map((f) => ({
        label: { label: f.name, description: `${f.type} · ${stream.name}` },
        kind: Kind.Field, insertText: f.name, range, detail: f.type, sortText: sortPrefix + f.name,
        documentation: `${stream.name}.${f.name} ${f.type}${f.nullable ? "" : " NOT NULL"}`,
      }));

      const dotted = /([A-Za-z_]\w*)\.$/.exec(before);
      if (dotted) {
        const { aliases } = scope(sql);
        const stream = streamsByName[aliases[dotted[1].toLowerCase()] || dotted[1].toLowerCase()];
        return { suggestions: stream ? fieldItems(stream, "0") : [] };
      }
      if (/\b(FROM|JOIN|TABLE)\s+$/i.test(before)) {
        return { suggestions: catalog.streams.map((s) => ({
          label: { label: s.name, description: t("wb.complete.stream_columns", { n: (s.fields || []).length }) },
          kind: Kind.Struct, insertText: s.name, range, sortText: "0" + s.name,
          documentation: (s.fields || []).map((f) => `${f.name} ${f.type}`).join("\n"),
        })).concat(catalog.views.map((v) => ({
          label: { label: v, description: t("wb.complete.view") }, kind: Kind.Interface, insertText: v, range,
          sortText: "1" + v, documentation: t("wb.complete.view_doc"),
        }))) };
      }
      if (/\bDESCRIPTOR\s*\(\s*$/i.test(before)) {
        const { inScope } = scope(sql);
        const out = [];
        inScope.forEach((name) => (streamsByName[name].fields || [])
          .filter((f) => /TIMESTAMP/i.test(f.type))
          .forEach((f) => out.push({ label: f.name, kind: Kind.Field, insertText: f.name, range,
                                     detail: t("wb.complete.event_time") })));
        return { suggestions: out };
      }
      const { inScope } = scope(sql);
      let suggestions = [];
      inScope.forEach((name) => { suggestions = suggestions.concat(fieldItems(streamsByName[name], "0")); });
      suggestions = suggestions.concat(catalog.functions.map((f) => ({
        label: { label: f.name, description: t("wb.complete.function") }, kind: Kind.Function,
        insertText: f.insert || f.name + "($0)",
        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
        range, detail: f.signature, documentation: f.doc, sortText: "2" + f.name,
      })));
      suggestions = suggestions.concat(catalog.keywords.map((k) => ({
        label: k, kind: Kind.Keyword, insertText: k, range, sortText: "3" + k,
      })));
      if (!inScope.size) {
        suggestions = suggestions.concat(catalog.streams.map((s) => ({
          label: { label: s.name, description: t("wb.complete.stream") }, kind: Kind.Struct, insertText: s.name,
          range, sortText: "1" + s.name })));
      }
      return { suggestions };
    },
  });
  monaco.languages.registerHoverProvider(LANG, {
    provideHover(model, position) {
      const word = model.getWordAtPosition(position);
      if (!word) return null;
      const w = word.word.toLowerCase();
      const fn = catalog.functions.find((f) => f.name.toLowerCase() === w);
      if (fn) return { contents: [{ value: "**" + fn.signature + "**" }, { value: fn.doc }] };
      const stream = catalog.streams.find((s) => String(s.name).toLowerCase() === w);
      if (stream) {
        return { contents: [{ value: `**${t("wb.complete.stream_named", { name: stream.name })}**` },
          { value: (stream.fields || []).map((f) => `\`${f.name}\` ${f.type}`).join("  \n") }] };
      }
      const { inScope } = scope(model.getValue());
      for (const name of inScope) {
        const s = catalog.streams.find((x) => String(x.name).toLowerCase() === name);
        const f = (s.fields || []).find((x) => String(x.name).toLowerCase() === w);
        if (f) return { contents: [{ value: `\`${s.name}.${f.name}\` **${f.type}**${f.nullable ? "" : " " + t("wb.not_null")}` }] };
      }
      return null;
    },
  });
}

/* ------------------------------------------------------------------ the island */

function initialTabs() {
  const saved = store.get(TABS_KEY, null);
  let tabs = Array.isArray(saved) && saved.length ? saved : [];
  let active = store.get(ACTIVE_KEY, tabs[0] && tabs[0].id);
  const linked = LINKED_SQL;
  const origin = mount.dataset.origin || "";
  if (urlParam("new")) {
    const tab = { id: newId(), title: t("wb.untitled"), sql: "", params: "" };
    tabs.push(tab); active = tab.id;
  } else if (linked.trim()) {
    /* A deep link (?query=, ?template=, a posted form) wins over the saved drafts, in a tab
       of its own, so opening a link never overwrites something unsaved. */
    const existing = tabs.find((d) => d.sql === linked);
    if (existing) { active = existing.id; }
    else {
      const tab = { id: newId(), title: origin || tabTitle(linked), sql: linked, params: "", origin };
      tabs.push(tab); active = tab.id;
    }
  }
  if (!tabs.length) {
    const tab = { id: newId(), title: t("wb.untitled"), sql: "", params: "" };
    tabs = [tab]; active = tab.id;
  }
  if (!tabs.some((d) => d.id === active)) active = tabs[0].id;
  return { tabs: tabs.slice(-12), active };
}

function Diagnostics({ validation, onFix, onRetry }) {
  if (validation.status === "idle") {
    return html`<div class="state"><h2>${t("wb.diag.idle_title")}</h2><p>${t("wb.diag.idle_body")}</p></div>`;
  }
  if (validation.status === "unavailable") {
    /* The error state (23.12): what failed, a retry when the engine may answer next time, the
       correlation id -- and what still works meanwhile. */
    const err = validation.err || {};
    return html`<div id="diag-unavailable">${states.error({ title: t("wb.diag.unavailable_title"),
        message: String(validation.error || "").replace(/^./, (c) => c.toUpperCase()), code: err.code,
        correlation: err.correlation, retryable: err.retryable }, onRetry)}
      <p class="small text-muted mb-0">${t("wb.diag.unavailable_body")}</p></div>`;
  }
  if (validation.status === "valid") {
    return html`<div class="d-flex flex-column gap-2">
      <div class="validity ok"><i class="bi bi-check-circle-fill"></i> ${t("wb.diag.valid")}
        ${validation.elapsed_us != null ? html`<span class="text-muted fw-normal">${t("wb.diag.took", { ms: (validation.elapsed_us / 1000).toFixed(1) })}</span>` : null}</div>
      ${validation.output_fields.length ? html`<div><div class="section-label mt-1">${t("wb.diag.output_schema")}</div>
        <ul class="field-list" style="max-width:32rem">${validation.output_fields.map((f) => html`<li>
          <span class="mono">${f.ordinal}. ${f.name}</span><span class="field-type">${f.type}${f.nullable ? "" : " " + t("wb.not_null")}</span></li>`)}</ul></div>` : null}
    </div>`;
  }
  return html`<div>${validation.diagnostics.map((d) => html`<div class=${"diag" + (d.severity === "warning" ? " warn" : "")}>
    <div><a class="fw-semibold mono" href=${d.help} target="_blank" rel="noopener">${d.code || t("wb.diag.refused")}</a>
      <span class="where ms-2">${t("wb.diag.where", { line: d.range.startLine, column: d.range.startColumn })}</span></div>
    <div style="white-space:pre-wrap">${d.message}</div>
    <div class="actions">
      ${d.fixes.map((f) => html`<button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onFix(f)}>${f.title}</button>`)}
      <a class="btn btn-sm btn-link" href=${d.help} target="_blank" rel="noopener">${t("wb.diag.means", { code: d.code || t("wb.diag.this") })}</a>
    </div></div>`)}</div>`;
}

/* One engine call's answer through the states of design 23.12: the first load is a skeleton; a
   refresh keeps the answer on screen and moves an indicator (never blank-then-refill); a failure
   is the error state with its retry, and a failed refresh keeps the last answer beside it; and an
   answer to SQL that has since changed is marked out of date rather than shown as current. */
function useAnswer() {
  const [state, setState] = useState({ status: "idle" });
  const [busy, setBusy] = useState(false);
  const latest = useRef(state);
  latest.current = state;
  const ask = useCallback(async (fetch, forSql) => {
    const had = latest.current.status === "ok";
    if (had) setBusy(true); else setState({ status: "loading" });
    try {
      const answer = await fetch();
      setState({ status: "ok", answer, forSql });
      return answer;
    } catch (err) {
      setState(had ? { ...latest.current, failed: err } : { status: "error", err });
      return null;
    } finally { setBusy(false); }
  }, []);
  return [state, busy, ask];
}

function refreshing(busy, label) {
  return busy ? html`<span class="freshness" data-state="refreshing" role="status"><span class="dot"></span><span>${label}</span></span>` : null;
}

function outOfDate(state, sql, body, again, label, id) {
  if (state.status !== "ok" || state.forSql === undefined || state.forSql === sql) return null;
  return html`<div class="alert alert-warning py-2 small d-flex flex-wrap gap-2 align-items-center" role="status" id=${id}>
    <span><span class="fw-semibold">${t("wb.stale.title")}</span> ${body}</span>
    <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${again} disabled=${!sql.trim()}>${label}</button></div>`;
}

function ExplainPanel({ sql, valid, origin }) {
  const [level, setLevel] = useState("physical");
  const [state, busy, ask] = useAnswer();
  const [selected, setSelected] = useState(null);
  const graphRef = useRef(null);
  const svgRef = useRef(null);

  const run = useCallback(async (lvl) => {
    const chosen = lvl || level;
    setSelected(null);
    /* `query` names the registered query this tab was opened from: the console attaches
       the engine's measured totals only while the SQL is still that query's own. */
    const answer = await ask(() => call("/sql/explain", { json: { sql, level: chosen, query: origin || null } }), sql);
    if (answer) announce(t("wb.explain.ready"));
  }, [sql, level, origin, ask]);
  const stale = state.status === "ok" && state.forSql !== sql;

  useEffect(() => { window.__wbExplain = run; }, [run]);
  useEffect(() => {
    if (state.status === "ok" && state.answer.level !== "codegen" && graphRef.current) {
      renderPlan(graphRef.current, state.answer.graph, {
        onSelect: setSelected,
        /* B6. The engine's per-operator numbers, keyed by this graph's own node ids, and the
           node it measured most of the query's time into. Null when nothing measured them,
           and the panel below says which of the three reasons that is. */
        metrics: state.answer.operator_metrics || null,
        bottleneck: state.answer.bottleneck || null,
      })
        .then((svg) => { svgRef.current = svg; })
        .catch((err) => { graphRef.current.textContent = t("wb.explain.not_drawn", { error: err.message }); });
    }
  }, [state]);

  return html`<div>
    <div class="d-flex flex-wrap gap-2 align-items-center mb-2">
      <label class="small text-muted" for="explain-level">${t("wb.explain.level")}</label>
      <select id="explain-level" class="form-select form-select-sm" style="width:auto" value=${level}
        onChange=${(e) => { setLevel(e.target.value); run(e.target.value); }}>
        <option value="physical">${t("wb.explain.physical")}</option>
        <option value="logical">${t("wb.explain.logical")}</option>
        <option value="codegen">${t("wb.explain.codegen")}</option>
      </select>
      <button type="button" class="btn btn-sm btn-primary" onClick=${() => run()} disabled=${!sql.trim()}>
        ${t("wb.explain.button")}</button>
      ${state.status === "ok" && svgRef.current && state.answer.level !== "codegen" ? html`
        <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => exportSvg(svgRef.current)}>
          ${t("wb.explain.export")}</button>` : null}
      ${!valid ? html`<span class="small text-muted">${t("wb.explain.not_valid")}</span>` : null}
      ${refreshing(busy, t("wb.explain.refreshing"))}
    </div>
    ${state.status === "idle" ? html`<div class="state"><h2>${t("wb.explain.idle_title")}</h2><p>${t("wb.explain.idle_body")}</p></div>` : null}
    ${state.status === "loading" ? states.loading(180, { id: "explain-loading" }) : null}
    ${state.status === "error" ? errorView(state.err, () => run()) : null}
    ${state.status === "ok" && state.failed ? errorView(state.failed, () => run()) : null}
    ${outOfDate(state, sql, t("wb.stale.plan"), () => run(), t("wb.stale.explain_again"), "explain-stale")}
    <div class=${stale ? "stale" : ""}>
    ${state.status === "ok" && state.answer.level === "codegen" ? html`<pre class="small" style="max-height:26rem">${state.answer.plan}</pre>` : null}
    ${state.status === "ok" && state.answer.level !== "codegen" ? html`<div>
      <div class="plan-legend" dangerouslySetInnerHTML=${{ __html: legend(state.answer.graph.nodes.map((n) => n.family)) }}></div>
      <div class="row g-3">
        <div class="col-xl-9"><div class="plan-wrap" ref=${graphRef}></div></div>
        <div class="col-xl-3">
          ${selected ? html`<div class="card"><div class="card-body small" id="operator-detail">
              <div class="fw-semibold">${selected.op}</div>
              <pre class="small mt-1" style="white-space:pre-wrap">${selected.label}</pre>
              ${selected.fields && selected.fields.length ? html`<div class="small text-muted">${t("wb.explain.emits")} <span class="mono">${selected.fields.join(", ")}</span></div>` : null}
              ${selected.stateful ? html`<div class="chip warn mt-1">${t("wb.explain.keeps_state")}</div>` : null}
              <${OperatorMetrics} telemetry=${(state.answer.operator_metrics || {})[selected.id]}
                                  bottleneck=${state.answer.bottleneck === selected.id} />
            </div></div>`
            : html`<p class="small text-muted">${t("wb.explain.select")}</p>`}
        </div>
      </div>
      <${MetricsNote} state=${state.answer.metrics_state} note=${state.answer.metrics_note}
                      bottleneck=${state.answer.bottleneck} graph=${state.answer.graph} />
      ${state.answer.query_metrics ? html`<div class="small mt-2" id="query-metrics"><span class="text-muted">${t("wb.explain.measured")}</span>
        ${" "}${totals(state.answer.query_metrics)}
        <${Backpressure} metrics=${state.answer.query_metrics} /></div>` : null}
      <details class="mt-2"><summary class="small">${t("wb.explain.as_text")}</summary><pre class="small mt-1">${state.answer.plan}</pre></details>
    </div>` : null}
    </div>
  </div>`;
}

/* The registration a CREATE CONTINUOUS QUERY answered with -- one row of name, state, fingerprint, sink. */
function registered(answer) {
  const c = (answer && answer.columns) || [];
  if (c.join(",") !== "name,state,fingerprint,sink" || !answer.rows || answer.rows.length !== 1) return null;
  const [name, state, fingerprint, sink] = answer.rows[0];
  return { name, state, fingerprint, sink };
}
/* What a registration answered, and where to go next -- from Run's CREATE and from Register. */
function registeredView(made) {
  const name = encodeURIComponent(made.name);
  return html`<div class="alert alert-success py-2" role="status">
    <strong>${made.name}</strong> ${t("wb.made.is", { state: String(made.state).toLowerCase() })}${" "}
    <span class="mono">${made.fingerprint}</span>${made.keys ? t("wb.made.keys", { keys: made.keys.join(", ") }) : ""}${
      made.sink ? t("wb.made.writing", { sink: made.sink }) : ""}${
      made.retention ? t("wb.made.keeping", { retention: made.retention }) : ""}.
    <div class="mt-1 d-flex gap-2 flex-wrap">
      <a class="btn btn-sm btn-primary" href=${"/views/" + name + "/live"}>${t("wb.made.watch")}</a>
      <a class="btn btn-sm btn-outline-secondary" href=${"/views/" + name}>${t("wb.made.browse")}</a>
      <a class="btn btn-sm btn-outline-secondary" href=${"/queries/" + name}>${t("wb.made.manage")}</a>
    </div></div>`;
}

function RunPanel({ sql, params, setParams, paramsRef }) {
  const [state, busy, ask] = useAnswer();
  const gridRef = useRef(null);
  const grid = useRef(null);

  const run = useCallback(async () => {
    if (!sql.trim()) return;
    const answer = await ask(() => call("/query", { json: { sql, parameters: typedParams(params) } }), sql);
    if (!answer) return;
    const made = registered(answer);
    announce(made ? t("wb.run.registered", { name: made.name })
      : t("wb.run.announce", { n: answer.returned, ms: answer.took_ms }));
  }, [sql, params, ask]);
  useEffect(() => { window.__wbRun = run; }, [run]);
  useEffect(() => {
    if (state.status !== "ok" || !gridRef.current || !state.answer.rows.length) return;
    if (!grid.current || grid.current.container !== gridRef.current) {
      grid.current = new Grid(gridRef.current, { caption: t("wb.run.caption") });
    }
    grid.current.set(state.answer.columns, state.answer.types || [], state.answer.rows);
  }, [state]);

  function downloadCsv() {
    const blob = new Blob([grid.current.csv()], { type: "text/csv" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob); link.download = "pravaha-result.csv"; link.click();
  }

  return html`<div>
    <div class="d-flex flex-wrap gap-2 align-items-end mb-2">
      <div style="min-width:16rem;flex:1">
        <label class="form-label small text-muted mb-1" for="wb-params">${t("wb.run.params")} <code>?</code></label>
        <input id="wb-params" ref=${paramsRef} class="form-control form-control-sm" value=${params}
          onInput=${(e) => setParams(e.target.value)} placeholder=${t("wb.run.params_placeholder")} />
      </div>
      <button type="button" class="btn btn-sm btn-primary" onClick=${run} disabled=${!sql.trim()}>${t("wb.run.once")}</button>
      ${state.status === "ok" && state.answer.rows.length ? html`<button type="button" class="btn btn-sm btn-outline-secondary" onClick=${downloadCsv}>${t("wb.run.csv")}</button>` : null}
      ${refreshing(busy, t("wb.run.refreshing"))}
    </div>
    ${STATEMENT.test(sql) ? html`<p class="small text-muted">${t("wb.run.statement_before")}
      <code>pravaha query --sql</code> ${t("wb.run.statement_middle")} <code>CREATE CONTINUOUS QUERY</code> ${t("wb.run.statement_after")}</p>`
      : html`<p class="small text-muted">${t("wb.run.read_before")} <code>40</code> ${t("wb.run.read_after")}</p>`}
    ${state.status === "idle" ? html`<div class="state"><h2>${t("wb.run.idle_title")}</h2><p>${t("wb.run.idle_body")}</p></div>` : null}
    ${state.status === "loading" ? states.loading(120, { id: "run-loading" }) : null}
    ${state.status === "error" ? errorView(state.err, run) : null}
    ${state.status === "ok" && state.failed ? errorView(state.failed, run) : null}
    ${outOfDate(state, sql, t("wb.stale.run"), run, t("wb.stale.run_again"), "run-stale")}
    ${state.status === "ok" && registered(state.answer) ? registeredView(registered(state.answer)) : null}
    ${state.status === "ok" ? html`<div class=${state.forSql !== sql ? "stale" : ""}>
      ${state.answer.truncated ? html`<div class="alert alert-warning py-2 small">${t("wb.run.truncated", { n: state.answer.returned })}</div>` : null}
      ${state.answer.rows.length ? html`<div ref=${gridRef}></div>` : html`<div class="state"><h2>${t("wb.run.none_title")}</h2><p>${
        t("wb.run.none_body", { ms: state.answer.took_ms })}</p></div>`}
      <p class="small text-muted mt-2 mb-0">${t("wb.run.summary", { n: state.answer.returned, ms: state.answer.took_ms })}</p></div>` : null}
  </div>`;
}

/* A count the engine may withhold (-1: row-filtered access) or not know. */
const fmtCount = (v) => (v === null || v === undefined ? "—" : v < 0 ? t("wb.withheld") : Number(v).toLocaleString());

/* The registered query's measured totals, in one sentence. */
function totals(m) {
  return t("wb.explain.totals", {
    rows: fmtCount(m.rowsIn), held: fmtCount(m.stateHeld), ceiling: fmtCount(m.stateCeiling),
    view: fmtCount(m.viewSize),
    subscribers: t(m.subscribers === 1 ? "wb.explain.subscriber" : "wb.explain.subscribers", { n: m.subscribers }),
    watermark: m.watermark || t("wb.explain.no_watermark"),
  });
}

/* B6. What the engine measured for one operator. Every field it did not publish is said as
   such and never as a zero: an operator that keeps no state off the heap has no byte count,
   and printing 0 B would claim it keeps nothing. The self time is sampled, so the number of
   samples it came from is shown beside it -- a 90 % share off four samples is a different
   claim from the same share off four thousand, and the reader is the one who can tell. */
function OperatorMetrics({ telemetry, bottleneck }) {
  if (!telemetry) {
    return html`<p class="small text-muted mt-2 mb-0" id="operator-not-measured">${t("wb.explain.operator_none")}</p>`;
  }
  const share = telemetry.selfTimeShare === null || telemetry.selfTimeShare === undefined
    ? null : Math.round(telemetry.selfTimeShare * 100);
  return html`<dl class="row small mt-2 mb-0" id="operator-metrics">
    <dt class="col-7">${t("wb.explain.op.rows_in")}</dt><dd class="col-5 num mono">${fmtCount(telemetry.rowsIn)}</dd>
    <dt class="col-7">${t("wb.explain.op.rows_out")}</dt><dd class="col-5 num mono">${fmtCount(telemetry.rowsOut)}</dd>
    <dt class="col-7">${t("wb.explain.op.state_bytes")}</dt>
    <dd class="col-5 num mono">${telemetry.stateBytes === null || telemetry.stateBytes === undefined
      ? html`<span class="text-muted">${t("wb.explain.op.no_state_bytes")}</span>`
      : fmtCount(telemetry.stateBytes)}</dd>
    <dt class="col-7">${t("wb.explain.op.watermark")}</dt>
    <dd class="col-5 mono">${telemetry.watermark || html`<span class="text-muted">${t("wb.explain.no_watermark")}</span>`}</dd>
    <dt class="col-7">${t("wb.explain.op.self_time")}</dt>
    <dd class="col-5 num mono">${share === null ? "?" : t("wb.explain.op.share", { n: share })}</dd>
    <dd class="col-12 text-muted">${t("wb.explain.op.sampled", { n: fmtCount(telemetry.sampledRows) })}</dd>
    ${bottleneck ? html`<dd class="col-12 mb-0"><span class="chip warn">${t("wb.explain.op.bottleneck")}</span></dd>` : null}
  </dl>`;
}

/* The three answers the engine gives about per-operator numbers, and they are three: SQL that
   is not registered has nothing running to measure; a node with pravaha.metrics.operators off
   has counters that were never built; and measured is measured. A panel that showed an empty
   graph for the middle one would be hiding a setting behind a blank. */
function MetricsNote({ state, note, bottleneck, graph }) {
  if (state === "measured") {
    const node = bottleneck && (graph.nodes || []).find((n) => n.id === bottleneck);
    return html`<p class="small text-muted mt-2 mb-0" id="metrics-note" data-metrics-state="measured">
      ${node ? html`<strong>${t("wb.explain.bottleneck_is", { op: node.op, id: node.id })}</strong> ` : null}
      ${bottleneck ? null : html`${t("wb.explain.no_bottleneck")} `}
      ${note || ""} <a href="/help/topics/reading-a-plan">${t("wb.explain.how_to_read")}</a></p>`;
  }
  const key = state === "operators_off" ? "wb.explain.operators_off" : "wb.explain.not_running";
  return html`<div class="alert alert-info py-2 small mt-2 mb-0" id="metrics-note"
                   role="note" data-metrics-state=${state || "not_running"}>
    <div class="fw-semibold">${t(key)}</div>
    <div>${note || ""}</div>
    ${state === "operators_off"
      ? html`<div class="mt-1">${t("wb.explain.operators_off_how")} <code>pravaha.metrics.operators</code>.
        ${" "}<a href="/help/topics/reading-a-plan">${t("wb.explain.operators_off_cost")}</a></div>`
      : null}
  </div>`;
}

/* B6. What the query as a whole waited on. blocked_fraction is the lane's view and counts
   every writer into it, which on a shared lane includes the neighbours' -- so the two are
   shown side by side rather than one summarised into a verdict the console cannot justify. */
function Backpressure({ metrics }) {
  if (metrics.blockedFraction === null || metrics.blockedFraction === undefined) return null;
  const blocked = Math.round(metrics.blockedFraction * 100);
  return html`<span id="query-backpressure"> · <span class="text-muted">${t("wb.explain.backpressure")}</span>
    ${" "}${t("wb.explain.blocked", { n: blocked })}
    ${" · "}${t("wb.explain.waits", { n: fmtCount(metrics.backpressureWaits),
                                      seconds: Number(metrics.backpressureWaitSeconds || 0).toFixed(1) })}
    ${" · "}${t("wb.explain.inbox", { depth: fmtCount(metrics.inboxDepth), cells: fmtCount(metrics.inboxCells) })}</span>`;
}

function RegisterPanel({ sql, validation, sinkRef, sink, setSink }) {
  const [name, setName] = useState("");
  const [retention, setRetention] = useState("");
  const [sinks, setSinks] = useState({ status: "loading", items: [] });
  useEffect(() => {
    call("/catalog/sinks").then((a) => setSinks({ status: "ok", items: a.items || [] }))
      .catch((err) => setSinks({ status: "error", items: [], error: err.message || String(err) }));
  }, []);
  const chosenSink = sinks.items.find((k) => k.name === sink);
  const [keys, setKeys] = useState([]);
  const [state, setState] = useState({ status: "idle" });
  const fields = validation.status === "valid" ? validation.output_fields : [];
  useEffect(() => { setKeys((k) => k.filter((x) => fields.some((f) => f.name === x))); }, [fields.map((f) => f.name).join(",")]);

  const nameOk = IDENT.test(name);
  /* The engine's policy, read by the server when the page was rendered (design 23.16): a
     registration it refuses is disabled here with its reason, never a button that fails. */
  const refused = (mount && mount.dataset.registerRefused) || "";
  const ready = !refused && nameOk && keys.length && validation.status === "valid";
  async function submit(event) {
    event.preventDefault();
    if (!ready) return;
    setState({ status: "loading" });
    try {
      const answer = await call("/queries", { json: { name, sql, key_names: keys, sink: sink || null, retention: retention || null } });
      setState({ status: "ok", answer });
      announce(t("wb.run.registered", { name: answer.name }));
    } catch (err) { setState({ status: "error", err }); }
  }
  const toggle = (field) => setKeys((k) => (k.includes(field) ? k.filter((x) => x !== field) : k.concat(field)));

  return html`<form onSubmit=${submit} class="row g-3">
    <div class="col-lg-4">
      <label class="form-label small text-muted mb-1" for="reg-name">${t("wb.reg.name")} <code>FROM</code></label>
      <input id="reg-name" class=${"form-control form-control-sm" + (name && !nameOk ? " is-invalid" : "")}
        value=${name} onInput=${(e) => setName(e.target.value.trim())} placeholder="hourly_spend" autocomplete="off" />
      ${name && !nameOk ? html`<div class="invalid-feedback">${t("wb.reg.name_invalid")}</div>` : null}
      <label class="form-label small text-muted mb-1 mt-3" for="reg-sink">${t("wb.reg.sink")}</label>
      <select id="reg-sink" ref=${sinkRef} class="form-select form-select-sm" value=${sink}
        onChange=${(e) => setSink(e.target.value)}>
        <option value="">${t("wb.reg.sink_none")}</option>
        ${sinks.items.map((k) => html`<option value=${k.name} disabled=${!!k.problem}>${k.name} · ${k.plugin} · ${
          k.acceptsRetractions ? t("wb.reg.accepts_revisions") : t("wb.reg.append_only")}</option>`)}
        ${sink && !chosenSink ? html`<option value=${sink}>${sink}</option>` : null}
      </select>
      ${sinks.status === "error" ? html`<div class="small text-muted mt-1">${t("wb.reg.sinks_failed", { error: sinks.error })}</div>` : null}
      ${chosenSink ? html`<div class="small mt-1">${chosenSink.fields && chosenSink.fields.length
          ? html`${t("wb.reg.row_shape")} <span class="mono">${chosenSink.fields.map((f) => f.name).join(", ")}</span>` : t("wb.reg.any_shape")}${chosenSink.keyColumns && chosenSink.keyColumns.length
          ? html` · ${t("wb.reg.keyed_by")} <span class="mono">${chosenSink.keyColumns.join(", ")}</span>` : ""}.
        ${chosenSink.acceptsRetractions ? "" : html` <span class="chip warn">${t("wb.reg.append_only")}</span> ${t("wb.reg.append_only_refused")}`}</div>` : null}
      <div class="form-text small">${t("wb.reg.sink_help")} (<a href="/help/codes/PRV-2041">PRV-2041</a>).</div>
    </div>
    <div class="col-lg-4">
      <fieldset>
        <legend class="form-label small text-muted mb-1 fs-6">${t("wb.reg.keys")}</legend>
        ${validation.status !== "valid" ? html`<p class="small text-muted">${t("wb.reg.keys_wait")}</p>` : null}
        ${fields.map((f) => html`<div class="form-check">
          <input class="form-check-input" type="checkbox" id=${"key-" + f.name} checked=${keys.includes(f.name)}
            onChange=${() => toggle(f.name)} />
          <label class="form-check-label small" for=${"key-" + f.name}><span class="mono">${f.name}</span>
            <span class="field-type ms-1">${f.type}</span>${keys.includes(f.name) ? html` <span class="chip info">${t("wb.reg.key_n", { n: keys.indexOf(f.name) + 1 })}</span>` : null}</label></div>`)}
      </fieldset>
      <div class="form-text small">${t("wb.reg.keys_help")}</div>
    </div>
    <div class="col-lg-4">
      <label class="form-label small text-muted mb-1" for="reg-retention">${t("wb.reg.retention")}</label>
      <input id="reg-retention" class="form-control form-control-sm" value=${retention}
        onInput=${(e) => setRetention(e.target.value.trim())} placeholder=${t("wb.reg.retention_placeholder")} autocomplete="off" />
      <div class="form-text small">${t("wb.reg.retention_how")} <em>${t("wb.reg.retention_event_time")}</em> ${t("wb.reg.retention_keeps")}
        <code>PT24H</code> ${t("wb.reg.or")} <code>P7D</code>${t("wb.reg.or_comma")} <code>forever</code>${t("wb.reg.retention_after")}</div>
      <button type="submit" class="btn btn-primary btn-sm mt-3" disabled=${!ready || state.status === "loading"}>
        ${state.status === "loading" ? t("wb.reg.registering") : t("wb.reg.submit")}</button>
      ${refused ? html`<div class="alert alert-info py-2 small mt-2 mb-0" id="register-refused" role="note">
        <div class="fw-semibold">${t("wb.reg.not_permitted")}</div>${t("wb.reg.refused", { reason: refused })} <a href="/admin/access">${t("wb.reg.what_may")}</a></div>`
      : !ready ? html`<div class="small text-muted mt-1">${[validation.status !== "valid" ? t("wb.reg.needs_valid") : "",
          !nameOk ? t("wb.reg.needs_name") : "", !keys.length ? t("wb.reg.needs_key") : ""].filter(Boolean).join(" ")}</div>` : null}
    </div>
    <div class="col-12">
      ${state.status === "error" ? errorView(state.err, () => submit({ preventDefault() {} })) : null}
      ${state.status === "ok" ? registeredView(state.answer) : null}
    </div>
  </form>`;
}

function Library({ onOpen, onInsert, currentSql, selection }) {
  const [saved, setSaved] = useState(store.get(SNIPPETS_KEY, []));
  let builtin = [];
  try { builtin = JSON.parse(document.getElementById("wb-library").textContent || "[]"); } catch (e) { builtin = []; }
  function save() {
    const text = (selection() || currentSql).trim();
    if (!text) return;
    const title = window.prompt(t("wb.lib.name_prompt"), tabTitle(text));
    if (!title) return;
    const next = saved.concat({ id: newId(), title, sql: text });
    setSaved(next); store.set(SNIPPETS_KEY, next); announce(t("wb.lib.saved"));
  }
  function remove(id) { const next = saved.filter((s) => s.id !== id); setSaved(next); store.set(SNIPPETS_KEY, next); }
  const row = (item, removable) => html`<li class="d-flex align-items-start gap-2 py-2 border-bottom" style="border-color:var(--hairline)!important">
    <div class="flex-grow-1"><div class="fw-semibold small">${item.title}</div>
      ${item.summary ? html`<div class="small text-muted">${item.summary}</div>` : null}
      <pre class="small mt-1 mb-0" style="max-height:6rem">${item.sql}</pre></div>
    <div class="d-flex flex-column gap-1">
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onOpen(item)}>${t("wb.lib.open")}</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onInsert(item.sql)}>${t("wb.lib.insert")}</button>
      ${removable ? html`<button type="button" class="btn btn-sm btn-link text-danger" onClick=${() => remove(item.id)}>${t("wb.lib.remove")}</button>` : null}
    </div></li>`;
  return html`<div class="row g-4">
    <div class="col-lg-6"><div class="section-label mt-0">${t("wb.lib.templates")}</div>
      <ul class="list-unstyled mb-0">${builtin.map((item) => row(item, false))}</ul></div>
    <div class="col-lg-6"><div class="d-flex justify-content-between align-items-center">
        <div class="section-label mt-0">${t("wb.lib.snippets")}</div>
        <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${save}>${t("wb.lib.save")}</button></div>
      ${saved.length ? html`<ul class="list-unstyled mb-0">${saved.map((s) => row(s, true))}</ul>`
        : html`<p class="small text-muted">${t("wb.lib.none")}</p>`}</div>
  </div>`;
}

const PANELS = [["diagnostics", t("wb.panel.diagnostics")], ["explain", t("wb.panel.explain")],
                ["run", t("wb.panel.run")], ["register", t("wb.panel.register")], ["diff", t("wb.diff.tab")],
                ["library", t("wb.panel.library")]];

function Workbench() {
  const init = useRef(initialTabs()).current;
  const [tabs, setTabs] = useState(init.tabs);
  const [active, setActive] = useState(init.active);
  const keyboardTabs = useRef(false);
  const [panel, setPanel] = useState(PANELS.some(([k]) => k === urlParam("panel")) ? urlParam("panel") : "diagnostics");
  const [validation, setValidation] = useState({ status: "idle", diagnostics: [], output_fields: [] });
  const [editorState, setEditorState] = useState("loading");
  const [sink, setSink] = useState("");
  const editorHost = useRef(null);
  const editor = useRef(null);
  const monacoRef = useRef(null);
  const models = useRef({});
  const paramsRef = useRef(null);
  const sinkRef = useRef(null);
  const tab = tabs.find((d) => d.id === active) || tabs[0];

  const persist = useRef(debounce((next, act) => { store.set(TABS_KEY, next); store.set(ACTIVE_KEY, act); }, 400)).current;
  useEffect(() => { persist(tabs, active); }, [tabs, active]);
  useEffect(() => { setUrlParam("panel", panel === "diagnostics" ? "" : panel); }, [panel]);

  const update = (id, patch) => setTabs((all) => all.map((d) => (d.id === id ? { ...d, ...patch } : d)));

  const validate = useRef(debounce(async (sql) => {
    if (!sql.trim()) { setValidation({ status: "idle", diagnostics: [], output_fields: [] }); return; }
    if (STATEMENT.test(sql)) { setValidation({ status: "statement", diagnostics: [], output_fields: [] }); return; }
    setValidation((v) => ({ ...v, checking: true }));
    try {
      const answer = await call("/sql/validate", { json: { sql } });
      setValidation({ status: answer.valid ? "valid" : "invalid", ...answer });
    } catch (err) {
      setValidation({ status: "unavailable", diagnostics: [], output_fields: [], error: err.message, err });
    }
  }, 300)).current;

  /* Markers follow the diagnostics, onto whichever model is showing. */
  useEffect(() => {
    const monaco = monacoRef.current; const ed = editor.current;
    if (!monaco || !ed) return;
    const markers = (validation.diagnostics || []).map((d) => ({
      severity: d.severity === "warning" ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error,
      message: `${d.code}  ${d.message}`, code: d.code,
      startLineNumber: d.range.startLine, startColumn: d.range.startColumn,
      endLineNumber: d.range.endLine, endColumn: d.range.endColumn,
    }));
    monaco.editor.setModelMarkers(ed.getModel(), "pravaha", markers);
  }, [validation, editorState]);

  /* Monaco, or the textarea it falls back to. */
  useEffect(() => {
    let disposed = false;
    Promise.all([loadMonaco(), call("/catalog/completions").catch(() => null), call("/queries?limit=500").catch(() => null)])
      .then(([monaco, completions, queries]) => {
        if (disposed) return;
        if (completions) catalog = { ...completions, views: [] };
        if (queries) catalog.views = (queries.items || []).map((q) => q.name);
        monacoRef.current = monaco;
        registerLanguage(monaco);
        defineThemes(monaco);
        onThemeChange(() => defineThemes(monaco));
        const ed = monaco.editor.create(editorHost.current, {
          model: null, automaticLayout: true, minimap: { enabled: false }, fontSize: 13,
          fontFamily: token("--code", "monospace"), scrollBeyondLastLine: false, tabSize: 2,
          renderLineHighlight: "line", wordBasedSuggestions: "off", fixedOverflowWidgets: true,
          ariaLabel: t("wb.editor_label"),
        });
        editor.current = ed;
        ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => { setPanel("run"); setTimeout(() => window.__wbRun && window.__wbRun(), 0); });
        ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => { setPanel("explain"); setTimeout(() => window.__wbExplain && window.__wbExplain(), 0); });
        monaco.languages.registerCodeActionProvider(LANG, {
          provideCodeActions(model, _range, context) {
            const actions = [];
            (window.__wbDiagnostics || []).forEach((d) => d.fixes.filter((f) => f.edits).forEach((f) => {
              if (!context.markers.some((m) => m.code === d.code)) return;
              actions.push({ title: f.title, kind: "quickfix", diagnostics: context.markers, isPreferred: true,
                edit: { edits: f.edits.map((e) => ({ resource: model.uri, versionId: model.getVersionId(), textEdit: {
                  range: { startLineNumber: e.range.startLine, startColumn: e.range.startColumn,
                           endLineNumber: e.range.endLine, endColumn: e.range.endColumn }, text: e.text } })) } });
            }));
            return { actions, dispose() {} };
          },
        });
        setEditorState("monaco");
      })
      .catch(() => { if (!disposed) setEditorState("textarea"); });
    return () => { disposed = true; };
  }, []);

  useEffect(() => { window.__wbDiagnostics = validation.diagnostics || []; }, [validation]);

  /* A deep link to the plan (?panel=explain, from a query's page) draws it once the editor is up. */
  const autoExplain = useRef(urlParam("panel") === "explain");
  useEffect(() => {
    if (autoExplain.current && editorState !== "loading" && tab && tab.sql.trim()) {
      autoExplain.current = false;
      setTimeout(() => window.__wbExplain && window.__wbExplain(), 0);
    }
  }, [editorState]);

  /* One model per tab, so undo history and cursor belong to the tab. */
  useEffect(() => {
    const monaco = monacoRef.current; const ed = editor.current;
    if (!monaco || !ed || !tab) return;
    let model = models.current[tab.id];
    if (!model) {
      model = monaco.editor.createModel(tab.sql, LANG);
      model.onDidChangeContent(() => {
        const value = model.getValue();
        update(tab.id, { sql: value, dirty: true, title: tab.origin || tabTitle(value, tab.named ? tab.title : "") });
        validate(value);
      });
      models.current[tab.id] = model;
    }
    ed.setModel(model);
    /* Into the editor after a click or a new draft, but not out of the tab strip while somebody
       is moving along it with the arrow keys: stealing focus there left a keyboard user unable
       to reach the second draft. */
    if (keyboardTabs.current) keyboardTabs.current = false; else ed.focus();
    validate(model.getValue());
  }, [active, editorState]);

  useEffect(() => { if (editorState === "textarea" && tab) validate(tab.sql); }, [active, editorState]);

  function addTab(sql = "", title = "") {
    const draft = { id: newId(), title: title || tabTitle(sql), sql, params: "", named: Boolean(title) };
    setTabs((all) => all.concat(draft)); setActive(draft.id);
  }
  /* The ARIA tabs pattern: arrows move between drafts, Home and End jump, Enter or Space
     selects, Delete closes. Focus follows the selection, so the roving tabindex stays true. */
  function onTabKey(event, tab, index) {
    const focusTab = (id) => setTimeout(() => {
      const el = document.getElementById("wb-tab-" + id);
      if (el) el.focus();
    }, 0);
    let next = null;
    if (event.key === "ArrowRight") next = tabs[(index + 1) % tabs.length];
    else if (event.key === "ArrowLeft") next = tabs[(index - 1 + tabs.length) % tabs.length];
    else if (event.key === "Home") next = tabs[0];
    else if (event.key === "End") next = tabs[tabs.length - 1];
    if (next) { event.preventDefault(); keyboardTabs.current = true; setActive(next.id); focusTab(next.id); return; }
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setActive(tab.id); return; }
    if (event.key === "Delete") {
      event.preventDefault();
      const neighbour = tabs[index + 1] || tabs[index - 1];
      keyboardTabs.current = active === tab.id;  /* the effect runs only if the selection moves */
      closeTab(tab.id);
      if (neighbour) focusTab(neighbour.id);
    }
  }

  function closeTab(id) {
    if (tabs.length === 1) { update(id, { sql: "", title: t("wb.untitled") }); if (models.current[id]) models.current[id].setValue(""); return; }
    const closing = tabs.find((x) => x.id === id);
    if (closing && closing.sql.trim() && !window.confirm(t("wb.close_confirm", { title: closing.title }))) return;
    if (models.current[id]) { models.current[id].dispose(); delete models.current[id]; }
    const rest = tabs.filter((x) => x.id !== id);
    setTabs(rest);
    if (active === id) setActive(rest[rest.length - 1].id);
  }
  function insert(text) {
    const ed = editor.current;
    if (ed) {
      ed.executeEdits("insert", [{ range: ed.getSelection(), text, forceMoveMarkers: true }]);
      ed.focus();
    } else {
      update(tab.id, { sql: (tab.sql ? tab.sql + " " : "") + text });
    }
  }
  function applyFix(fix) {
    if (fix.edits && editor.current) {
      editor.current.executeEdits("fix", fix.edits.map((e) => ({
        range: { startLineNumber: e.range.startLine, startColumn: e.range.startColumn,
                 endLineNumber: e.range.endLine, endColumn: e.range.endColumn }, text: e.text })));
      announce(t("wb.fix_applied", { title: fix.title }));
      return;
    }
    if (fix.action === "open-catalog") { window.open("/catalog", "_blank", "noopener"); }
    else if (fix.action === "clear-sink") { setSink(""); setPanel("register"); setTimeout(() => sinkRef.current && sinkRef.current.focus(), 0); }
    else if (fix.action === "focus-params") { setPanel("run"); setTimeout(() => paramsRef.current && paramsRef.current.focus(), 0); }
    else if (fix.action && fix.action.startsWith("template:")) { setPanel("library"); }
  }

  /* Sidebar columns and library links insert or open here instead of navigating. */
  useEffect(() => {
    const side = document.querySelector(".wb-side");
    if (!side) return undefined;
    const handler = (event) => {
      const ins = event.target.closest("[data-insert]");
      if (ins) { event.preventDefault(); insert(ins.getAttribute("data-insert")); return; }
      const tpl = event.target.closest("[data-template]");
      if (tpl) {
        event.preventDefault();
        let lib = [];
        try { lib = JSON.parse(document.getElementById("wb-library").textContent); } catch (e) { lib = []; }
        const found = lib.find((item) => item.id === tpl.getAttribute("data-template"));
        if (found) addTab(found.sql, found.title);
      }
    };
    side.addEventListener("click", handler);
    return () => side.removeEventListener("click", handler);
  });

  const count = validation.diagnostics ? validation.diagnostics.length : 0;
  const status = validation.status === "valid" ? html`<span class="validity ok"><i class="bi bi-check-circle-fill"></i> ${t("wb.status.valid")}</span>`
    : validation.status === "invalid" ? html`<span class="validity bad"><i class="bi bi-x-circle-fill"></i> ${
      t(count === 1 ? "wb.status.problem" : "wb.status.problems", { n: count })}</span>`
    : validation.status === "unavailable" ? html`<span class="validity idle" title=${validation.error}><i class="bi bi-plug"></i> ${t("wb.status.unavailable")}</span>`
    : validation.status === "statement" ? html`<span class="validity idle"><i class="bi bi-broadcast"></i> ${t("wb.status.statement")}</span>`
    : html`<span class="validity idle"><i class="bi bi-circle"></i> ${t("wb.status.unchecked")}</span>`;

  /* The tablist holds tabs and nothing else: ARIA lets a tablist own only tabs, and a tab
     may not contain another control. So "new draft" sits beside the list, and closing is
     Delete on the focused tab; the × does the same for a pointer and is hidden from
     assistive technology, which has the key (aria-keyshortcuts). */
  return html`<div>
    <div class="wb-tabbar">
      <div class="wb-tabs" role="tablist" aria-label=${t("wb.drafts")}>
        ${tabs.map((d, i) => html`<div class="wb-tab" role="tab" id=${"wb-tab-" + d.id}
            aria-selected=${d.id === active ? "true" : "false"} aria-keyshortcuts="Delete"
            title=${t("wb.tab_title", { title: d.title || t("wb.untitled") })}
            tabindex=${d.id === active ? 0 : -1} onClick=${() => setActive(d.id)}
            onKeyDown=${(e) => onTabKey(e, d, i)}>
          <span class="mono">${d.title || t("wb.untitled")}</span>
          <span class="close" aria-hidden="true" onClick=${(e) => { e.stopPropagation(); closeTab(d.id); }}>×</span></div>`)}
      </div>
      <button type="button" class="wb-tab wb-new" onClick=${() => addTab()} aria-label=${t("wb.new_draft")} title=${t("wb.new_draft")}>+</button>
    </div>
    <div class="wb-editor" ref=${editorHost} hidden=${editorState === "textarea"}></div>
    ${editorState === "textarea" && tab ? html`<div class="wb-editor"><textarea aria-label="SQL" spellcheck="false"
        value=${tab.sql} onInput=${(e) => { update(tab.id, { sql: e.target.value, title: tab.origin || tabTitle(e.target.value) }); validate(e.target.value); }}></textarea></div>` : null}
    <div class="wb-toolbar">
      <button type="button" class="btn btn-sm btn-primary" onClick=${() => { setPanel("run"); setTimeout(() => window.__wbRun && window.__wbRun(), 0); }}>
        <i class="bi bi-play-fill"></i> ${t("wb.toolbar.run")}</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => { setPanel("explain"); setTimeout(() => window.__wbExplain && window.__wbExplain(), 0); }}>
        <i class="bi bi-diagram-3"></i> ${t("wb.toolbar.explain")}</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => setPanel("register")}>
        <i class="bi bi-broadcast"></i> ${t("wb.toolbar.register")}</button>
      <button type="button" class="btn btn-sm btn-link" onClick=${() => copyText(tab.sql)}>${t("wb.toolbar.copy")}</button>
      <span class="status" aria-live="polite">${validation.checking ? html`<span class="text-muted">${t("wb.status.checking")}</span>` : null} ${status}
        ${editorState === "loading" ? html`<span class="text-muted">${t("wb.status.loading_editor")}</span>` : null}
        ${editorState === "textarea" ? html`<span class="text-muted" title=${t("wb.status.plain_editor_why")}>${t("wb.status.plain_editor")}</span>` : null}
        <span class="text-muted">· ${t("wb.status.drafts_here")}</span></span>
    </div>
    <div class="card"><div class="card-body pt-1">
      <div class="panel-tabs" role="tablist" aria-label=${t("wb.panels")}>
        ${PANELS.map(([key, label]) => html`<button type="button" role="tab" aria-selected=${panel === key ? "true" : "false"}
          onClick=${() => setPanel(key)}>${label}${key === "diagnostics" && count ? html` <span class="chip bad">${count}</span>` : null}</button>`)}
      </div>
      <div class="panel-body" role="tabpanel">
        <div hidden=${panel !== "diagnostics"}><${Diagnostics} validation=${validation} onFix=${applyFix} onRetry=${() => tab && validate(tab.sql)} /></div>
        <div hidden=${panel !== "explain"}><${ExplainPanel} sql=${tab ? tab.sql : ""} valid=${validation.status === "valid"} origin=${tab ? tab.origin || "" : ""} /></div>
        <div hidden=${panel !== "run"}><${RunPanel} sql=${tab ? tab.sql : ""} params=${tab ? tab.params || "" : ""}
          setParams=${(p) => update(tab.id, { params: p })} paramsRef=${paramsRef} /></div>
        <div hidden=${panel !== "register"}><${RegisterPanel} sql=${tab ? tab.sql : ""} validation=${validation}
          sinkRef=${sinkRef} sink=${sink} setSink=${setSink} /></div>
        <div hidden=${panel !== "diff"}><${DiffPanel} sql=${tab ? tab.sql : ""}
          title=${t("wb.diff.this_draft", { title: (tab && tab.title) || t("wb.untitled") })}
          origin=${tab ? tab.origin || "" : ""} drafts=${tabs.filter((d) => d.id !== active && d.sql.trim())}
          active=${panel === "diff"} getMonaco=${() => (editorState === "monaco" ? monacoRef.current : null)}
          onNewDraft=${() => addTab()} editorReady=${editorState !== "loading"} /></div>
        <div hidden=${panel !== "library"}><${Library} currentSql=${tab ? tab.sql : ""} onOpen=${(item) => addTab(item.sql, item.title)}
          onInsert=${insert} selection=${() => (editor.current ? editor.current.getModel().getValueInRange(editor.current.getSelection()) : "")} /></div>
      </div>
    </div></div>
  </div>`;
}

if (mount) {
  mount.replaceChildren();
  render(html`<${Workbench} />`, mount);
}
