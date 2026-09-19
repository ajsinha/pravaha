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
         errorView, isNumericType } from "pravaha/lib.js";
import { useEffect, useRef, useState, useCallback } from "preact/hooks";
import { Grid } from "pravaha/grid.js";
import { renderPlan, legend, exportSvg, loadGlobalScript } from "pravaha/plan-graph.js";

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

function newId() { return Math.random().toString(36).slice(2, 9); }
function tabTitle(sql, fallback) {
  const from = /\bFROM\s+(?:TABLE\s*\(\s*(?:TUMBLE|HOP)\s*\(\s*TABLE\s+)?([A-Za-z_]\w*)/i.exec(sql || "");
  return fallback || (from ? from[1] : "untitled");
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
      script.onload = resolve; script.onerror = () => reject(new Error("the editor did not load"));
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
    typeKeywords: catalog.types.map((t) => t.toLowerCase()),
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
          label: { label: s.name, description: `stream · ${(s.fields || []).length} columns` },
          kind: Kind.Struct, insertText: s.name, range, sortText: "0" + s.name,
          documentation: (s.fields || []).map((f) => `${f.name} ${f.type}`).join("\n"),
        })).concat(catalog.views.map((v) => ({
          label: { label: v, description: "view" }, kind: Kind.Interface, insertText: v, range,
          sortText: "1" + v, documentation: "A registered query's view: read it with SELECT.",
        }))) };
      }
      if (/\bDESCRIPTOR\s*\(\s*$/i.test(before)) {
        const { inScope } = scope(sql);
        const out = [];
        inScope.forEach((name) => (streamsByName[name].fields || [])
          .filter((f) => /TIMESTAMP/i.test(f.type))
          .forEach((f) => out.push({ label: f.name, kind: Kind.Field, insertText: f.name, range,
                                     detail: "event time must be the stream's declared event-time column" })));
        return { suggestions: out };
      }
      const { inScope } = scope(sql);
      let suggestions = [];
      inScope.forEach((name) => { suggestions = suggestions.concat(fieldItems(streamsByName[name], "0")); });
      suggestions = suggestions.concat(catalog.functions.map((f) => ({
        label: { label: f.name, description: "function" }, kind: Kind.Function,
        insertText: f.insert || f.name + "($0)",
        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
        range, detail: f.signature, documentation: f.doc, sortText: "2" + f.name,
      })));
      suggestions = suggestions.concat(catalog.keywords.map((k) => ({
        label: k, kind: Kind.Keyword, insertText: k, range, sortText: "3" + k,
      })));
      if (!inScope.size) {
        suggestions = suggestions.concat(catalog.streams.map((s) => ({
          label: { label: s.name, description: "stream" }, kind: Kind.Struct, insertText: s.name,
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
        return { contents: [{ value: `**stream ${stream.name}**` },
          { value: (stream.fields || []).map((f) => `\`${f.name}\` ${f.type}`).join("  \n") }] };
      }
      const { inScope } = scope(model.getValue());
      for (const name of inScope) {
        const s = catalog.streams.find((x) => String(x.name).toLowerCase() === name);
        const f = (s.fields || []).find((x) => String(x.name).toLowerCase() === w);
        if (f) return { contents: [{ value: `\`${s.name}.${f.name}\` **${f.type}**${f.nullable ? "" : " not null"}` }] };
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
    const tab = { id: newId(), title: "untitled", sql: "", params: "" };
    tabs.push(tab); active = tab.id;
  } else if (linked.trim()) {
    /* A deep link (?query=, ?template=, a posted form) wins over the saved drafts, in a tab
       of its own, so opening a link never overwrites something unsaved. */
    const existing = tabs.find((t) => t.sql === linked);
    if (existing) { active = existing.id; }
    else {
      const tab = { id: newId(), title: origin || tabTitle(linked), sql: linked, params: "", origin };
      tabs.push(tab); active = tab.id;
    }
  }
  if (!tabs.length) {
    const tab = { id: newId(), title: "untitled", sql: "", params: "" };
    tabs = [tab]; active = tab.id;
  }
  if (!tabs.some((t) => t.id === active)) active = tabs[0].id;
  return { tabs: tabs.slice(-12), active };
}

function Diagnostics({ validation, onFix }) {
  if (validation.status === "idle") {
    return html`<div class="state"><h2>Nothing to check yet</h2><p>Validation runs as you type, against the
      engine's own planner, and every refusal lands here with its code and, where one is known, a fix.</p></div>`;
  }
  if (validation.status === "unavailable") {
    return html`<div class="alert alert-warning small" role="status"><div class="fw-semibold"><i class="bi bi-wifi-off"></i>
      Validation is unavailable</div><p class="mb-0 mt-1">${String(validation.error || "").replace(/^./, (c) => c.toUpperCase())}. The editor still edits and
      your drafts still save; validation resumes when the engine's HTTP API answers.</p></div>`;
  }
  if (validation.status === "valid") {
    return html`<div class="d-flex flex-column gap-2">
      <div class="validity ok"><i class="bi bi-check-circle-fill"></i> Valid — the engine's planner accepts it
        ${validation.elapsed_us != null ? html`<span class="text-muted fw-normal">(${(validation.elapsed_us / 1000).toFixed(1)} ms)</span>` : null}</div>
      ${validation.output_fields.length ? html`<div><div class="section-label mt-1">Output schema</div>
        <ul class="field-list" style="max-width:32rem">${validation.output_fields.map((f) => html`<li>
          <span class="mono">${f.ordinal}. ${f.name}</span><span class="field-type">${f.type}${f.nullable ? "" : " not null"}</span></li>`)}</ul></div>` : null}
    </div>`;
  }
  return html`<div>${validation.diagnostics.map((d) => html`<div class=${"diag" + (d.severity === "warning" ? " warn" : "")}>
    <div><a class="fw-semibold mono" href=${d.help} target="_blank" rel="noopener">${d.code || "refused"}</a>
      <span class="where ms-2">line ${d.range.startLine}, column ${d.range.startColumn}</span></div>
    <div style="white-space:pre-wrap">${d.message}</div>
    <div class="actions">
      ${d.fixes.map((f) => html`<button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onFix(f)}>${f.title}</button>`)}
      <a class="btn btn-sm btn-link" href=${d.help} target="_blank" rel="noopener">What ${d.code || "this"} means</a>
    </div></div>`)}</div>`;
}

function ExplainPanel({ sql, valid, origin }) {
  const [level, setLevel] = useState("physical");
  const [state, setState] = useState({ status: "idle" });
  const [selected, setSelected] = useState(null);
  const graphRef = useRef(null);
  const svgRef = useRef(null);

  const run = useCallback(async (lvl) => {
    const chosen = lvl || level;
    setState({ status: "loading" }); setSelected(null);
    try {
      /* `query` names the registered query this tab was opened from: the console attaches
         the engine's measured totals only while the SQL is still that query's own. */
      const answer = await call("/sql/explain", { json: { sql, level: chosen, query: origin || null } });
      setState({ status: "ok", answer });
      announce("Plan ready");
    } catch (err) { setState({ status: "error", err }); }
  }, [sql, level, origin]);

  useEffect(() => { window.__wbExplain = run; }, [run]);
  useEffect(() => {
    if (state.status === "ok" && state.answer.level !== "codegen" && graphRef.current) {
      renderPlan(graphRef.current, state.answer.graph, { onSelect: setSelected })
        .then((svg) => { svgRef.current = svg; })
        .catch((err) => { graphRef.current.textContent = "The plan could not be drawn: " + err.message; });
    }
  }, [state]);

  return html`<div>
    <div class="d-flex flex-wrap gap-2 align-items-center mb-2">
      <label class="small text-muted" for="explain-level">Level</label>
      <select id="explain-level" class="form-select form-select-sm" style="width:auto" value=${level}
        onChange=${(e) => { setLevel(e.target.value); run(e.target.value); }}>
        <option value="physical">Physical — what runs</option>
        <option value="logical">Logical — what was asked</option>
        <option value="codegen">Generated Java</option>
      </select>
      <button type="button" class="btn btn-sm btn-primary" onClick=${() => run()} disabled=${!sql.trim()}>
        Explain</button>
      ${state.status === "ok" && svgRef.current && state.answer.level !== "codegen" ? html`
        <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => exportSvg(svgRef.current)}>
          Export SVG</button>` : null}
      ${!valid ? html`<span class="small text-muted">The query does not validate yet; the plan may be refused.</span>` : null}
    </div>
    ${state.status === "idle" ? html`<div class="state"><h2>No plan yet</h2><p>Explain draws the engine's own plan
      for this query as a graph — operators as nodes, rows flowing left to right.</p></div>` : null}
    ${state.status === "loading" ? html`<div class="skeleton" style="height:180px"></div>` : null}
    ${state.status === "error" ? errorView(state.err) : null}
    ${state.status === "ok" && state.answer.level === "codegen" ? html`<pre class="small" style="max-height:26rem">${state.answer.plan}</pre>` : null}
    ${state.status === "ok" && state.answer.level !== "codegen" ? html`<div>
      <div class="plan-legend" dangerouslySetInnerHTML=${{ __html: legend(state.answer.graph.nodes.map((n) => n.family)) }}></div>
      <div class="row g-3">
        <div class="col-xl-9"><div class="plan-wrap" ref=${graphRef}></div></div>
        <div class="col-xl-3">
          ${selected ? html`<div class="card"><div class="card-body small">
              <div class="fw-semibold">${selected.op}</div>
              <pre class="small mt-1" style="white-space:pre-wrap">${selected.label}</pre>
              ${selected.fields && selected.fields.length ? html`<div class="small text-muted">Emits: <span class="mono">${selected.fields.join(", ")}</span></div>` : null}
              ${selected.stateful ? html`<div class="chip warn mt-1">keeps state</div>` : null}
              <div class="small text-muted mt-2">Per-operator numbers are not shown: ${state.answer.metrics_note || "the engine does not count rows or state per operator."}</div></div></div>`
            : html`<p class="small text-muted">Select an operator (click, or Tab to it and press Enter) for its detail.
                 Arrow keys move between operators.</p>`}
        </div>
      </div>
      ${state.answer.query_metrics ? html`<div class="small mt-2" id="query-metrics"><span class="text-muted">Measured for the registered query as a whole:</span>
        ${" "}${fmtCount(state.answer.query_metrics.rowsIn)} rows in · state ${fmtCount(state.answer.query_metrics.stateHeld)} of ${fmtCount(state.answer.query_metrics.stateCeiling)}
        · view ${fmtCount(state.answer.query_metrics.viewSize)} rows · ${state.answer.query_metrics.subscribers} subscriber${state.answer.query_metrics.subscribers === 1 ? "" : "s"}
        · watermark ${state.answer.query_metrics.watermark || "none yet"}</div>` : null}
      <details class="mt-2"><summary class="small">The plan as text</summary><pre class="small mt-1">${state.answer.plan}</pre></details>
    </div>` : null}
  </div>`;
}

function RunPanel({ sql, params, setParams, paramsRef }) {
  const [state, setState] = useState({ status: "idle" });
  const gridRef = useRef(null);
  const grid = useRef(null);

  const run = useCallback(async () => {
    if (!sql.trim()) return;
    setState({ status: "loading" });
    try {
      const answer = await call("/query", { json: { sql, parameters: typedParams(params) } });
      setState({ status: "ok", answer });
      announce(`${answer.returned} rows in ${answer.took_ms} milliseconds`);
    } catch (err) { setState({ status: "error", err }); }
  }, [sql, params]);
  useEffect(() => { window.__wbRun = run; }, [run]);
  useEffect(() => {
    if (state.status !== "ok" || !gridRef.current || !state.answer.rows.length) return;
    if (!grid.current || grid.current.container !== gridRef.current) {
      grid.current = new Grid(gridRef.current, { caption: "Query result" });
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
        <label class="form-label small text-muted mb-1" for="wb-params">Parameters — comma-separated, one per <code>?</code></label>
        <input id="wb-params" ref=${paramsRef} class="form-control form-control-sm" value=${params}
          onInput=${(e) => setParams(e.target.value)} placeholder="e.g. 40, ACME" />
      </div>
      <button type="button" class="btn btn-sm btn-primary" onClick=${run} disabled=${!sql.trim()}>Run once</button>
      ${state.status === "ok" && state.answer.rows.length ? html`<button type="button" class="btn btn-sm btn-outline-secondary" onClick=${downloadCsv}>Download CSV</button>` : null}
    </div>
    <p class="small text-muted">A one-off read, answered from the views the engine maintains. Nothing is registered.
      Numbers stay numbers: <code>40</code> is an integer, not the string “40”.</p>
    ${state.status === "idle" ? html`<div class="state"><h2>Nothing run yet</h2><p>Run reads a view once. To keep an
      answer current, register the query instead.</p></div>` : null}
    ${state.status === "loading" ? html`<div class="skeleton" style="height:120px"></div>` : null}
    ${state.status === "error" ? errorView(state.err) : null}
    ${state.status === "ok" ? html`<div>
      ${state.answer.truncated ? html`<div class="alert alert-warning py-2 small">Showing the first ${state.answer.returned} rows;
        the answer was larger. These are the first rows, not a sample.</div>` : null}
      ${state.answer.rows.length ? html`<div ref=${gridRef}></div>` : html`<div class="state"><h2>No rows</h2><p>It ran in
        ${state.answer.took_ms} ms and matched nothing. On a stream this often means a window has not closed:
        a window closes when data says it is over, not when the clock does.</p></div>`}
      <p class="small text-muted mt-2 mb-0">${state.answer.returned} rows in ${state.answer.took_ms} ms</p></div>` : null}
  </div>`;
}

/* A count the engine may withhold (-1: row-filtered access) or not know. */
const fmtCount = (v) => (v === null || v === undefined ? "—" : v < 0 ? "withheld" : Number(v).toLocaleString());

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
  const ready = nameOk && keys.length && validation.status === "valid";
  async function submit(event) {
    event.preventDefault();
    if (!ready) return;
    setState({ status: "loading" });
    try {
      const answer = await call("/queries", { json: { name, sql, key_names: keys, sink: sink || null, retention: retention || null } });
      setState({ status: "ok", answer });
      announce(`${answer.name} registered`);
    } catch (err) { setState({ status: "error", err }); }
  }
  const toggle = (field) => setKeys((k) => (k.includes(field) ? k.filter((x) => x !== field) : k.concat(field)));

  return html`<form onSubmit=${submit} class="row g-3">
    <div class="col-lg-4">
      <label class="form-label small text-muted mb-1" for="reg-name">Name — what clients put in <code>FROM</code></label>
      <input id="reg-name" class=${"form-control form-control-sm" + (name && !nameOk ? " is-invalid" : "")}
        value=${name} onInput=${(e) => setName(e.target.value.trim())} placeholder="hourly_spend" autocomplete="off" />
      ${name && !nameOk ? html`<div class="invalid-feedback">Letters, digits and underscores, starting with a letter.</div>` : null}
      <label class="form-label small text-muted mb-1 mt-3" for="reg-sink">Sink — optional</label>
      <select id="reg-sink" ref=${sinkRef} class="form-select form-select-sm" value=${sink}
        onChange=${(e) => setSink(e.target.value)}>
        <option value="">none — the view only</option>
        ${sinks.items.map((k) => html`<option value=${k.name} disabled=${!!k.problem}>${k.name} · ${k.plugin}${k.acceptsRetractions ? " · accepts revisions" : " · append only"}</option>`)}
        ${sink && !chosenSink ? html`<option value=${sink}>${sink}</option>` : null}
      </select>
      ${sinks.status === "error" ? html`<div class="small text-muted mt-1">The sink list did not load (${sinks.error}).</div>` : null}
      ${chosenSink ? html`<div class="small mt-1">${chosenSink.fields && chosenSink.fields.length
          ? html`Row shape: <span class="mono">${chosenSink.fields.map((f) => f.name).join(", ")}</span>` : "Takes any row shape"}${chosenSink.keyColumns && chosenSink.keyColumns.length
          ? html` · keyed by <span class="mono">${chosenSink.keyColumns.join(", ")}</span>` : ""}.
        ${chosenSink.acceptsRetractions ? "" : html` <span class="chip warn">append only</span> a query that revises its answer is refused here.`}</div>` : null}
      <div class="form-text small">Its changes are also written there, retractions included, at least once. A query that
        revises its answer needs a sink that accepts updates (<a href="/help/codes/PRV-2041">PRV-2041</a>).</div>
    </div>
    <div class="col-lg-4">
      <fieldset>
        <legend class="form-label small text-muted mb-1 fs-6">Key columns — what a row replaces</legend>
        ${validation.status !== "valid" ? html`<p class="small text-muted">Keys are chosen by name from the output schema the
          engine validated. Make the query valid and its columns appear here.</p>` : null}
        ${fields.map((f) => html`<div class="form-check">
          <input class="form-check-input" type="checkbox" id=${"key-" + f.name} checked=${keys.includes(f.name)}
            onChange=${() => toggle(f.name)} />
          <label class="form-check-label small" for=${"key-" + f.name}><span class="mono">${f.name}</span>
            <span class="field-type ms-1">${f.type}</span>${keys.includes(f.name) ? html` <span class="chip info">key ${keys.indexOf(f.name) + 1}</span>` : null}</label></div>`)}
      </fieldset>
      <div class="form-text small">A second row with the same key supersedes the first. Two registrations differing only
        in keys are two computations.</div>
    </div>
    <div class="col-lg-4">
      <label class="form-label small text-muted mb-1" for="reg-retention">Retention</label>
      <input id="reg-retention" class="form-control form-control-sm" value=${retention}
        onInput=${(e) => setRetention(e.target.value.trim())} placeholder="the engine's default" autocomplete="off" />
      <div class="form-text small">How much <em>event time</em> the view keeps: an ISO-8601 duration such as
        <code>PT24H</code> or <code>P7D</code>, or <code>forever</code>. Empty takes the engine's default. The engine refuses
        what it cannot read rather than keeping something else.</div>
      <button type="submit" class="btn btn-primary btn-sm mt-3" disabled=${!ready || state.status === "loading"}>
        ${state.status === "loading" ? "Registering…" : "Register continuous query"}</button>
      ${!ready ? html`<div class="small text-muted mt-1">${validation.status !== "valid" ? "Needs a valid query. " : ""}${!nameOk ? "Needs a name. " : ""}${!keys.length ? "Needs at least one key." : ""}</div>` : null}
    </div>
    <div class="col-12">
      ${state.status === "error" ? errorView(state.err) : null}
      ${state.status === "ok" ? html`<div class="alert alert-success py-2" role="status">
        <strong>${state.answer.name}</strong> is ${state.answer.state.toLowerCase()} — fingerprint${" "}
        <span class="mono">${state.answer.fingerprint}</span>, keys [${state.answer.keys.join(", ")}]${state.answer.sink ? ", writing to " + state.answer.sink : ""}${state.answer.retention ? ", keeping " + state.answer.retention : ""}.
        <div class="mt-1 d-flex gap-2 flex-wrap">
          <a class="btn btn-sm btn-primary" href=${"/views/" + encodeURIComponent(state.answer.name) + "/live"}>Watch it change</a>
          <a class="btn btn-sm btn-outline-secondary" href=${"/views/" + encodeURIComponent(state.answer.name)}>Browse the view</a>
          <a class="btn btn-sm btn-outline-secondary" href=${"/queries/" + encodeURIComponent(state.answer.name)}>Manage</a>
        </div></div>` : null}
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
    const title = window.prompt("Name this snippet", tabTitle(text));
    if (!title) return;
    const next = saved.concat({ id: newId(), title, sql: text });
    setSaved(next); store.set(SNIPPETS_KEY, next); announce("Snippet saved");
  }
  function remove(id) { const next = saved.filter((s) => s.id !== id); setSaved(next); store.set(SNIPPETS_KEY, next); }
  const row = (item, removable) => html`<li class="d-flex align-items-start gap-2 py-2 border-bottom" style="border-color:var(--hairline)!important">
    <div class="flex-grow-1"><div class="fw-semibold small">${item.title}</div>
      ${item.summary ? html`<div class="small text-muted">${item.summary}</div>` : null}
      <pre class="small mt-1 mb-0" style="max-height:6rem">${item.sql}</pre></div>
    <div class="d-flex flex-column gap-1">
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onOpen(item)}>Open in a tab</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => onInsert(item.sql)}>Insert</button>
      ${removable ? html`<button type="button" class="btn btn-sm btn-link text-danger" onClick=${() => remove(item.id)}>Remove</button>` : null}
    </div></li>`;
  return html`<div class="row g-4">
    <div class="col-lg-6"><div class="section-label mt-0">Templates — written against your first stream</div>
      <ul class="list-unstyled mb-0">${builtin.map((t) => row(t, false))}</ul></div>
    <div class="col-lg-6"><div class="d-flex justify-content-between align-items-center">
        <div class="section-label mt-0">Your snippets — kept in this browser</div>
        <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${save}>Save selection</button></div>
      ${saved.length ? html`<ul class="list-unstyled mb-0">${saved.map((s) => row(s, true))}</ul>`
        : html`<p class="small text-muted">None yet. Select some SQL and save it here; it stays in this browser only.</p>`}</div>
  </div>`;
}

const PANELS = [["diagnostics", "Diagnostics"], ["explain", "Explain"], ["run", "Run"],
                ["register", "Register"], ["library", "Library"]];

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
  const tab = tabs.find((t) => t.id === active) || tabs[0];

  const persist = useRef(debounce((next, act) => { store.set(TABS_KEY, next); store.set(ACTIVE_KEY, act); }, 400)).current;
  useEffect(() => { persist(tabs, active); }, [tabs, active]);
  useEffect(() => { setUrlParam("panel", panel === "diagnostics" ? "" : panel); }, [panel]);

  const update = (id, patch) => setTabs((all) => all.map((t) => (t.id === id ? { ...t, ...patch } : t)));

  const validate = useRef(debounce(async (sql) => {
    if (!sql.trim()) { setValidation({ status: "idle", diagnostics: [], output_fields: [] }); return; }
    setValidation((v) => ({ ...v, checking: true }));
    try {
      const answer = await call("/sql/validate", { json: { sql } });
      setValidation({ status: answer.valid ? "valid" : "invalid", ...answer });
    } catch (err) {
      setValidation({ status: "unavailable", diagnostics: [], output_fields: [], error: err.message });
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
          ariaLabel: "SQL editor. Ctrl+Enter runs, Ctrl+Shift+Enter explains.",
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
    const t = { id: newId(), title: title || tabTitle(sql), sql, params: "", named: Boolean(title) };
    setTabs((all) => all.concat(t)); setActive(t.id);
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
    if (tabs.length === 1) { update(id, { sql: "", title: "untitled" }); if (models.current[id]) models.current[id].setValue(""); return; }
    const t = tabs.find((x) => x.id === id);
    if (t && t.sql.trim() && !window.confirm(`Close “${t.title}”? Its SQL is not saved anywhere else.`)) return;
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
      announce("Fix applied: " + fix.title);
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
        const found = lib.find((t) => t.id === tpl.getAttribute("data-template"));
        if (found) addTab(found.sql, found.title);
      }
    };
    side.addEventListener("click", handler);
    return () => side.removeEventListener("click", handler);
  });

  const count = validation.diagnostics ? validation.diagnostics.length : 0;
  const status = validation.status === "valid" ? html`<span class="validity ok"><i class="bi bi-check-circle-fill"></i> valid</span>`
    : validation.status === "invalid" ? html`<span class="validity bad"><i class="bi bi-x-circle-fill"></i> ${count} problem${count === 1 ? "" : "s"}</span>`
    : validation.status === "unavailable" ? html`<span class="validity idle" title=${validation.error}><i class="bi bi-plug"></i> validation unavailable</span>`
    : html`<span class="validity idle"><i class="bi bi-circle"></i> not checked</span>`;

  /* The tablist holds tabs and nothing else: ARIA lets a tablist own only tabs, and a tab
     may not contain another control. So "new draft" sits beside the list, and closing is
     Delete on the focused tab; the × does the same for a pointer and is hidden from
     assistive technology, which has the key (aria-keyshortcuts). */
  return html`<div>
    <div class="wb-tabbar">
      <div class="wb-tabs" role="tablist" aria-label="Drafts">
        ${tabs.map((t, i) => html`<div class="wb-tab" role="tab" id=${"wb-tab-" + t.id}
            aria-selected=${t.id === active ? "true" : "false"} aria-keyshortcuts="Delete"
            title=${(t.title || "untitled") + " — Delete closes this draft"}
            tabindex=${t.id === active ? 0 : -1} onClick=${() => setActive(t.id)}
            onKeyDown=${(e) => onTabKey(e, t, i)}>
          <span class="mono">${t.title || "untitled"}</span>
          <span class="close" aria-hidden="true" onClick=${(e) => { e.stopPropagation(); closeTab(t.id); }}>×</span></div>`)}
      </div>
      <button type="button" class="wb-tab wb-new" onClick=${() => addTab()} aria-label="New draft" title="New draft">+</button>
    </div>
    <div class="wb-editor" ref=${editorHost} hidden=${editorState === "textarea"}></div>
    ${editorState === "textarea" && tab ? html`<div class="wb-editor"><textarea aria-label="SQL" spellcheck="false"
        value=${tab.sql} onInput=${(e) => { update(tab.id, { sql: e.target.value, title: tab.origin || tabTitle(e.target.value) }); validate(e.target.value); }}></textarea></div>` : null}
    <div class="wb-toolbar">
      <button type="button" class="btn btn-sm btn-primary" onClick=${() => { setPanel("run"); setTimeout(() => window.__wbRun && window.__wbRun(), 0); }}>
        <i class="bi bi-play-fill"></i> Run</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => { setPanel("explain"); setTimeout(() => window.__wbExplain && window.__wbExplain(), 0); }}>
        <i class="bi bi-diagram-3"></i> Explain</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => setPanel("register")}>
        <i class="bi bi-broadcast"></i> Register…</button>
      <button type="button" class="btn btn-sm btn-link" onClick=${() => copyText(tab.sql)}>Copy SQL</button>
      <span class="status" aria-live="polite">${validation.checking ? html`<span class="text-muted">checking…</span>` : null} ${status}
        ${editorState === "loading" ? html`<span class="text-muted">loading editor…</span>` : null}
        ${editorState === "textarea" ? html`<span class="text-muted" title="The Monaco editor did not load">plain editor</span>` : null}
        <span class="text-muted">· drafts saved in this browser</span></span>
    </div>
    <div class="card"><div class="card-body pt-1">
      <div class="panel-tabs" role="tablist" aria-label="Workbench panels">
        ${PANELS.map(([key, label]) => html`<button type="button" role="tab" aria-selected=${panel === key ? "true" : "false"}
          onClick=${() => setPanel(key)}>${label}${key === "diagnostics" && count ? html` <span class="chip bad">${count}</span>` : null}</button>`)}
      </div>
      <div class="panel-body" role="tabpanel">
        <div hidden=${panel !== "diagnostics"}><${Diagnostics} validation=${validation} onFix=${applyFix} /></div>
        <div hidden=${panel !== "explain"}><${ExplainPanel} sql=${tab ? tab.sql : ""} valid=${validation.status === "valid"} origin=${tab ? tab.origin || "" : ""} /></div>
        <div hidden=${panel !== "run"}><${RunPanel} sql=${tab ? tab.sql : ""} params=${tab ? tab.params || "" : ""}
          setParams=${(p) => update(tab.id, { params: p })} paramsRef=${paramsRef} /></div>
        <div hidden=${panel !== "register"}><${RegisterPanel} sql=${tab ? tab.sql : ""} validation=${validation}
          sinkRef=${sinkRef} sink=${sink} setSink=${setSink} /></div>
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
