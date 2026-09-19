/*
 * Pravaha console -- the workbench's Compare panel: SQL diff and plan diff (design 23.7).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * "Diff view when editing an existing query: SQL diff and plan diff, so the blue/green
 * consequence is visible before cutover." The draft in the editor is compared with a
 * registered query (v1, picked by name) or with another draft. The console's /sql/diff does
 * the judging -- which operators are the same one, which changed, what the engine will do with
 * the new version -- so this island only draws it: Monaco's own diff editor for the SQL, the
 * plan graph twice with each operator marked, and the lists in words.
 *
 * Measured totals are the engine's numbers for a registered query's running plan. They are
 * drawn on that side only: a draft has not run, and nothing here may suggest it has.
 */
import { html, call, announce, errorView, store, t, token } from "pravaha/lib.js";
import { useEffect, useRef, useState, useCallback } from "preact/hooks";
import { renderPlan } from "pravaha/plan-graph.js";

const LAYOUT_KEY = "pravaha.workbench.diffLayout";
const LANG = "pravaha-sql";

function urlParam(name) { return new URLSearchParams(location.search).get(name); }
function keepInUrl(name, value) {
  const params = new URLSearchParams(location.search);
  if (value) params.set(name, value); else params.delete(name);
  history.replaceState(null, "", location.pathname + (params.toString() ? "?" + params : ""));
}

const fmtCount = (v) => (v === null || v === undefined ? "—" : v < 0 ? t("wb.diff.withheld") : Number(v).toLocaleString());
const list = (items) => (items || []).join(", ");

/** A plain line diff (longest common subsequence), for when Monaco did not load. */
export function lineDiff(before, after) {
  const a = String(before || "").split("\n"), b = String(after || "").split("\n");
  const n = a.length, m = b.length;
  const best = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      best[i][j] = a[i] === b[j] ? best[i + 1][j + 1] + 1 : Math.max(best[i + 1][j], best[i][j + 1]);
    }
  }
  const out = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push([" ", a[i]]); i++; j++; }
    else if (best[i + 1][j] >= best[i][j + 1]) { out.push(["-", a[i]]); i++; }
    else { out.push(["+", b[j]]); j++; }
  }
  while (i < n) out.push(["-", a[i++]]);
  while (j < m) out.push(["+", b[j++]]);
  return out;
}

/** One operator of the plan diff, in words: "+ Filter(amount > 100)", "changed: ...". */
export function describeOperator(c) {
  if (c.change === "added") return "+ " + c.label;
  if (c.change === "removed") return "− " + c.label;
  if (c.change === "same") return "= " + c.before;
  const parts = [];
  if (c.keys) parts.push(t("wb.diff.op.keys", { op: c.op, before: list(c.keys.before), after: list(c.keys.after) }));
  else if (c.what.includes("label")) parts.push(t("wb.diff.op.label", { before: c.before, after: c.after }));
  else parts.push(c.op);
  if (c.fields) {
    if (c.fields.added.length) parts.push(t("wb.diff.op.emits", { names: list(c.fields.added) }));
    if (c.fields.removed.length) parts.push(t("wb.diff.op.drops", { names: list(c.fields.removed) }));
    if (!c.fields.added.length && !c.fields.removed.length) parts.push(t("wb.diff.op.reorders"));
  }
  if (c.what.includes("state")) parts.push(t("wb.diff.op.state"));
  return t("wb.diff.op.changed") + " " + parts.join("; ");
}

/** What the engine will do with this version relative to the other, one finding in words. */
export function describeConsequence(f, v1, v2) {
  switch (f.kind) {
    case "fingerprint_same": return t("wb.diff.cq.fingerprint_same", { v1, v2: f.right_name || v2, fp: f.right });
    case "fingerprint_differs": return t("wb.diff.cq.fingerprint_differs", { v1, v2: f.right_name || v2, left: f.left, right: f.right });
    case "computation_unknown": return t("wb.diff.cq.computation_unknown", { v1 });
    case "separate_computation": return t("wb.diff.cq.separate", { v1 });
    case "may_share": return t("wb.diff.cq.may_share", { v1, keys: list(f.keys) || "—",
      retention: f.retention || t("wb.diff.default_retention"), fp: f.fingerprint || "—" });
    case "may_share_drafts": return t("wb.diff.cq.may_share_drafts");
    case "schema_unknown": return t("wb.diff.cq.schema_unknown");
    case "schema_same": return t("wb.diff.cq.schema_same");
    case "schema_reordered": return t("wb.diff.cq.schema_reordered", { before: list(f.before), after: list(f.after) });
    case "schema_changes": {
      const parts = [];
      if (f.added.length) parts.push(t("wb.diff.cq.cols_added", { names: list(f.added) }));
      if (f.removed.length) parts.push(t("wb.diff.cq.cols_removed", { names: list(f.removed) }));
      if (f.retyped.length) parts.push(t("wb.diff.cq.cols_retyped", {
        names: f.retyped.map((r) => `${r.name} ${r.before} → ${r.after}`).join(", ") }));
      return t("wb.diff.cq.schema_changes", { changes: parts.join("; ") });
    }
    case "keys_available": return t("wb.diff.cq.keys_available", { v1, keys: list(f.keys) });
    case "keys_missing": return t("wb.diff.cq.keys_missing", { v1, missing: list(f.missing) });
    case "stateless": return t("wb.diff.cq.stateless");
    case "state_same": return t("wb.diff.cq.state_same");
    case "state_changes": {
      const parts = [];
      if (f.added.length) parts.push(t("wb.diff.cq.state_added", { ops: list(f.added) }));
      if (f.removed.length) parts.push(t("wb.diff.cq.state_removed", { ops: list(f.removed) }));
      if (f.changed.length) parts.push(t("wb.diff.cq.state_changed", { ops: list(f.changed) }));
      return t("wb.diff.cq.state_changes", { changes: parts.join("; "), v1 });
    }
    case "not_determinable": return t("wb.diff.cq.not_determinable");
    default: return f.kind;
  }
}

/* Tall enough for the text -- the longer side side by side, both sides unified, counting the
   lines a long line wraps into at this width -- and never taller than a screenful: the diff
   editor scrolls beyond that. */
function sqlHeight(answer, host, side) {
  const width = host ? host.clientWidth : 900;
  const columns = Math.max(20, Math.floor(((side ? width / 2 : width) - 70) / 7.8));
  const lines = (text) => String(text || "").split("\n")
    .reduce((n, line) => n + Math.max(1, Math.ceil(line.length / columns)), 0);
  const a = lines(answer.left.sql), b = lines(answer.right.sql);
  return Math.min(320, Math.max(64, ((side ? Math.max(a, b) : a + b) + 1) * 19 + 8));
}

/* Side by side where there is room for two columns of SQL, unified otherwise or when chosen.
   Decided here rather than by Monaco's own useInlineViewWhenSpaceIsLimited, which in 0.56
   strips the inner editors' accessible names whenever it re-decides (axe: aria-input-field-name). */
function sideBySide(layout, host) {
  return layout === "side" && Boolean(host) && host.clientWidth >= 640;
}

function Skeleton() {
  return html`<div aria-hidden="true" data-state="loading">
    <div class="skeleton mb-2" style="height:1.1rem;width:40%"></div>
    <div class="skeleton mb-3" style="height:4.5rem"></div>
    <div class="skeleton mb-3" style="height:180px"></div>
    <div class="row g-3"><div class="col-12"><div class="skeleton" style="height:150px"></div></div>
      <div class="col-12"><div class="skeleton" style="height:150px"></div></div></div></div>`;
}

function Totals({ metrics, id }) {
  return html`<div class="small mt-2" id=${id}><span class="text-muted">${t("wb.diff.measured")}</span>${" "}
    ${t("wb.diff.totals", { rows: fmtCount(metrics.rowsIn), held: fmtCount(metrics.stateHeld),
       ceiling: fmtCount(metrics.stateCeiling), view: fmtCount(metrics.viewSize), subs: metrics.subscribers,
       watermark: metrics.watermark || t("wb.diff.no_watermark") })}</div>`;
}

/** One side's plan: the graph, or what stood in its way. */
function PlanSide({ side, which, marks, hostRef, title }) {
  if (side.refused) {
    return html`<div class="alert alert-info py-2 small mb-0" role="note" id=${"diff-" + which + "-not-permitted"}>
      <div class="fw-semibold">${t("wb.diff.not_permitted")}</div>
      ${t("wb.diff.not_permitted_body", { name: side.label, reason: side.refused.message })}
      ${" "}<a href="/admin/access">${t("wb.diff.what_may")}</a></div>`;
  }
  if (!side.graph) {
    return html`<div class="alert alert-warning py-2 small mb-0" role="status" id=${"diff-" + which + "-no-plan"}>
      <div class="fw-semibold">${t("wb.diff.no_plan", { name: side.label })}</div>
      ${side.plan_error ? side.plan_error.message : ""}
      ${side.plan_error && side.plan_error.code ? html` <a href=${"/help/codes/" + side.plan_error.code}>${side.plan_error.code}</a>` : null}</div>`;
  }
  return html`<div class="plan-wrap diff-plan" ref=${hostRef} data-marks=${marks ? "yes" : "no"} aria-label=${title}></div>`;
}

export function DiffPanel({ sql, title, origin, drafts, active, getMonaco, onNewDraft, editorReady }) {
  const [queries, setQueries] = useState({ status: "idle", names: [] });
  const [against, setAgainst] = useState(() => {
    const linked = urlParam("against");
    if (linked) return "q:" + linked;
    return origin ? "q:" + origin : "";
  });
  const [result, setResult] = useState({ status: "idle" });
  const [refreshing, setRefreshing] = useState(false);
  const [layout, setLayout] = useState(store.get(LAYOUT_KEY, "side"));
  const [showAll, setShowAll] = useState(false);
  const sqlHost = useRef(null);
  const leftHost = useRef(null);
  const rightHost = useRef(null);
  const diffEditor = useRef(null);
  const layoutRef = useRef(layout);
  const answerRef = useRef(null);
  const autoCompare = useRef(urlParam("panel") === "diff");

  /* The registered queries to pick from, asked for when the panel is first opened. */
  useEffect(() => {
    if (!active || queries.status !== "idle") return;
    setQueries({ status: "loading", names: [] });
    call("/queries?limit=500")
      .then((a) => setQueries({ status: "ok", names: (a.items || []).map((q) => q.name) }))
      .catch((err) => setQueries({ status: "error", names: [], error: err.message || String(err) }));
  }, [active]);

  /* Nothing chosen yet: the query this draft came from, else the first registered one, else another draft. */
  useEffect(() => {
    if (against) return;
    if (queries.names.length) setAgainst("q:" + queries.names[0]);
    else if (drafts.length) setAgainst("d:" + drafts[0].id);
  }, [queries.names.join(","), drafts.length]);

  const other = against.startsWith("d:") ? drafts.find((d) => d.id === against.slice(2)) : null;
  const againstName = against.startsWith("q:") ? against.slice(2) : other ? other.title : "";
  const otherSql = other ? other.sql : null;

  const compare = useCallback(async () => {
    if (!against || !sql.trim()) return;
    const left = against.startsWith("q:") ? { query: against.slice(2) }
      : { sql: otherSql || "", label: t("wb.diff.draft_named", { title: other ? other.title || "untitled" : "" }) };
    const had = result.status === "ok";
    if (had) setRefreshing(true); else setResult({ status: "loading" });
    try {
      const answer = await call("/sql/diff", { json: { left, right: { sql, label: title } } });
      setResult({ status: "ok", answer, forSql: sql, forAgainst: against, forOther: otherSql });
      if (against.startsWith("q:")) keepInUrl("against", against.slice(2));
      const c = answer.plan ? answer.plan.counts : null;
      announce(c ? t("wb.diff.announce", { added: c.added, removed: c.removed, changed: c.changed })
                 : t("wb.diff.announce_partial"));
    } catch (err) {
      setResult({ status: "error", err, previous: had ? result : null });
    } finally {
      setRefreshing(false);
    }
  }, [against, sql, title, otherSql, result]);

  useEffect(() => { window.__wbCompare = compare; }, [compare]);

  /* A deep link to the panel (?panel=diff, optionally &against=name) compares once the editor is up. */
  useEffect(() => {
    if (autoCompare.current && editorReady && against && sql.trim()) {
      autoCompare.current = false;
      compare();
    }
  }, [editorReady, against, sql]);

  const answer = result.status === "ok" ? result.answer
    : result.status === "error" && result.previous ? result.previous.answer : null;
  layoutRef.current = layout;
  answerRef.current = answer;
  const stale = result.status === "ok"
    && (result.forSql !== sql || result.forAgainst !== against || (other && result.forOther !== otherSql));

  /* The SQL diff: Monaco's own diff editor, side by side or unified. */
  useEffect(() => {
    const monaco = getMonaco();
    if (!active || !answer || !monaco || !sqlHost.current) return;
    sqlHost.current.style.height = sqlHeight(answer, sqlHost.current, sideBySide(layout, sqlHost.current)) + "px";
    if (!diffEditor.current || diffEditor.current.host !== sqlHost.current) {
      if (diffEditor.current) diffEditor.current.editor.dispose();
      const editor = monaco.editor.createDiffEditor(sqlHost.current, {
        readOnly: true, originalEditable: false, automaticLayout: true, minimap: { enabled: false },
        renderSideBySide: sideBySide(layout, sqlHost.current), useInlineViewWhenSpaceIsLimited: false,
        renderOverviewRuler: false, scrollBeyondLastLine: false, enableSplitViewResizing: false,
        fontSize: 13, fontFamily: token("--code", "monospace"), wordWrap: "on", diffWordWrap: "inherit",
        ariaLabel: t("wb.diff.sql_label"),
        originalAriaLabel: t("wb.diff.sql_original", { name: answer.left.label }),
        modifiedAriaLabel: t("wb.diff.sql_modified", { name: answer.right.label }),
      });
      diffEditor.current = { editor, host: sqlHost.current, models: [] };
      /* Side by side or unified follows the room there is: re-decided when the width changes
         (and so once the host has one -- it may have none yet when the editor is made). */
      let lastSide = null;
      const holderAtCreation = diffEditor.current;
      new ResizeObserver(() => {
        const holder = diffEditor.current;
        if (holder !== holderAtCreation) return;
        const side = sideBySide(layoutRef.current, holder.host);
        if (side === lastSide) return;
        lastSide = side;
        if (answerRef.current) holder.host.style.height = sqlHeight(answerRef.current, holder.host, side) + "px";
        editor.updateOptions({ renderSideBySide: side, ...(holder.labels || {}) });
      }).observe(sqlHost.current);
      /* Said on the element once the diff is computed and drawn, so a test (or anything else)
         can tell a finished comparison from one Monaco is still laying out. */
      editor.onDidUpdateDiff(() => requestAnimationFrame(() => requestAnimationFrame(() => {
        if (diffEditor.current && diffEditor.current.editor === editor) diffEditor.current.host.dataset.diffReady = "yes";
      })));
    }
    const holder = diffEditor.current;
    const original = monaco.editor.createModel(answer.left.sql, LANG);
    const modified = monaco.editor.createModel(answer.right.sql, LANG);
    holder.host.dataset.diffReady = "";
    holder.editor.setModel({ original, modified });
    holder.models.forEach((m) => m.dispose());
    holder.models = [original, modified];
    holder.labels = {
      originalAriaLabel: t("wb.diff.sql_original", { name: answer.left.label }),
      modifiedAriaLabel: t("wb.diff.sql_modified", { name: answer.right.label }),
    };
    /* The inner editors' names follow the comparison: each names the version it shows. */
    holder.editor.updateOptions(holder.labels);
  }, [answer, active]);

  useEffect(() => {
    const holder = diffEditor.current;
    if (holder) {
      const side = sideBySide(layout, holder.host);
      if (answer) holder.host.style.height = sqlHeight(answer, holder.host, side) + "px";
      holder.editor.updateOptions({ renderSideBySide: side, ...(holder.labels || {}) });
    }
    store.set(LAYOUT_KEY, layout);
  }, [layout]);

  useEffect(() => () => {
    if (diffEditor.current) { diffEditor.current.editor.dispose(); diffEditor.current.models.forEach((m) => m.dispose()); }
  }, []);

  /* The two plans, each operator marked. */
  useEffect(() => {
    if (!active || !answer) return;
    const draw = (host, side, marks, label) => {
      if (host && side.graph) {
        renderPlan(host, side.graph, { marks, label })
          .catch((err) => { host.textContent = t("wb.diff.not_drawn", { error: err.message }); });
      }
    };
    const plan = answer.plan;
    draw(leftHost.current, answer.left, plan ? plan.left : null, t("wb.diff.plan_of", { name: answer.left.label }));
    draw(rightHost.current, answer.right, plan ? plan.right : null, t("wb.diff.plan_of", { name: answer.right.label }));
  }, [answer, active]);

  const options = html`
    ${queries.names.length ? html`<optgroup label=${t("wb.diff.registered")}>
      ${queries.names.map((n) => html`<option value=${"q:" + n}>${n}${n === origin ? " " + t("wb.diff.this_came_from") : ""}</option>`)}</optgroup>` : null}
    ${against.startsWith("q:") && !queries.names.includes(against.slice(2)) ? html`<option value=${against}>${against.slice(2)}</option>` : null}
    ${drafts.length ? html`<optgroup label=${t("wb.diff.drafts")}>
      ${drafts.map((d) => html`<option value=${"d:" + d.id}>${d.title || "untitled"}</option>`)}</optgroup>` : null}`;
  const nothingToCompare = queries.status !== "loading" && queries.status !== "idle" && !queries.names.length && !drafts.length && !against;

  const controls = html`<div class="d-flex flex-wrap gap-2 align-items-end mb-2">
    <div>
      <label class="form-label small text-muted mb-1" for="diff-against">${t("wb.diff.against")}</label>
      <select id="diff-against" class="form-select form-select-sm" style="width:16rem;max-width:100%" value=${against}
        onChange=${(e) => setAgainst(e.target.value)} disabled=${nothingToCompare}>
        ${!against ? html`<option value="">${queries.status === "loading" ? t("wb.diff.loading_queries") : t("wb.diff.nothing")}</option>` : null}
        ${options}
      </select>
    </div>
    <button type="button" class="btn btn-sm btn-primary" id="diff-compare" onClick=${compare}
      disabled=${!against || !sql.trim() || result.status === "loading" || refreshing}>${t("wb.diff.compare")}</button>
    <button type="button" class="btn btn-sm btn-outline-secondary" id="diff-layout" aria-pressed=${layout === "inline" ? "true" : "false"}
      onClick=${() => setLayout(layout === "side" ? "inline" : "side")}>${t("wb.diff.unified")}</button>
    ${refreshing ? html`<span class="freshness" data-state="refreshing" role="status"><span class="dot"></span><span>${t("wb.diff.comparing")}</span></span>` : null}
    ${queries.status === "error" ? html`<span class="small text-muted">${t("wb.diff.queries_failed", { error: queries.error })}</span>` : null}
  </div>`;

  let body;
  if (result.status === "loading") {
    body = html`<${Skeleton} />`;
  } else if (result.status === "error" && !answer) {
    body = html`<div id="diff-error">${errorView(result.err)}
      ${result.err.retryable ? html`<button type="button" class="btn btn-sm btn-primary" onClick=${compare}>${t("wb.diff.retry")}</button>`
        : html`<span class="chip mute">${t("wb.diff.no_retry")}</span>`}</div>`;
  } else if (!answer) {
    body = nothingToCompare
      ? html`<div class="state" id="diff-empty"><h2>${t("wb.diff.none_title")}</h2><p>${t("wb.diff.none_body")}</p>
          <div class="d-flex gap-2 justify-content-center flex-wrap"><button type="button" class="btn btn-sm btn-outline-secondary" onClick=${onNewDraft}>${t("wb.diff.new_draft")}</button>
          <a class="btn btn-sm btn-link" href="/help/topics/compare-versions">${t("wb.diff.how")}</a></div></div>`
      : html`<div class="state" id="diff-empty"><h2>${t("wb.diff.never_title")}</h2><p>${t("wb.diff.never_body")}</p>
          ${against ? html`<button type="button" class="btn btn-sm btn-primary" onClick=${compare} disabled=${!sql.trim()}>${t("wb.diff.compare_with", { name: againstName })}</button>` : null}</div>`;
  } else {
    body = html`<${Result} answer=${answer} stale=${stale} showAll=${showAll} setShowAll=${setShowAll} compare=${compare}
      hasMonaco=${Boolean(getMonaco())} sqlHost=${sqlHost} leftHost=${leftHost} rightHost=${rightHost}
      failed=${result.status === "error" ? result.err : null} />`;
  }
  return html`<div id="diff-panel">${controls}${body}</div>`;
}

function Result({ answer, stale, showAll, setShowAll, compare, hasMonaco, sqlHost, leftHost, rightHost, failed }) {
  const v1 = answer.left.query || answer.left.label;
  const v2 = answer.right.label;
  const plan = answer.plan;
  const changed = plan ? plan.operators.filter((c) => c.change !== "same") : [];
  const shown = plan ? (showAll ? plan.operators : changed) : [];
  const missing = [];
  [answer.left, answer.right].forEach((s) => { if (!s.graph && !s.refused) missing.push(t("wb.diff.missing_plan", { name: s.label })); });
  if (answer.left.output_fields === null || answer.right.output_fields === null) missing.push(t("wb.diff.missing_schema"));
  const refused = [answer.left, answer.right].find((s) => s.refused);

  return html`<div id="diff-result">
    ${failed ? html`<div class="mb-2">${errorView(failed)}</div>` : null}
    ${stale ? html`<div class="alert alert-warning py-2 small d-flex flex-wrap gap-2 align-items-center" role="status" id="diff-stale">
      <span><span class="fw-semibold">${t("wb.diff.stale_title")}</span> ${t("wb.diff.stale_body")}</span>
      <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${compare}>${t("wb.diff.compare_again")}</button></div>` : null}
    ${missing.length ? html`<div class="alert alert-warning py-2 small" role="status" id="diff-partial">
      <div class="fw-semibold">${t("wb.diff.partial_title")}</div>${t("wb.diff.partial_body", { missing: missing.join("; ") })}</div>` : null}
    ${refused ? html`<div class="alert alert-info py-2 small" role="note" id="diff-not-permitted">
      <div class="fw-semibold">${t("wb.diff.not_permitted")}</div>${t("wb.diff.not_permitted_body", { name: refused.label, reason: refused.refused.message })}
      ${" "}<a href="/admin/access">${t("wb.diff.what_may")}</a></div>` : null}
    <div class=${stale ? "stale" : ""}>
      <h2 class="section-label mt-1">${t("wb.diff.consequences")}</h2>
      <ul class="diff-consequences small" id="diff-consequences">
        ${answer.consequences.map((f) => html`<li data-kind=${f.kind}>${describeConsequence(f, v1, v2)}</li>`)}
      </ul>

      <h2 class="section-label">${t("wb.diff.sql")}</h2>
      ${answer.same_sql ? html`<p class="small text-muted mb-1" id="diff-same-sql">${t("wb.diff.same_sql")}</p>` : null}
      ${hasMonaco ? html`<div class="diff-sql" ref=${sqlHost}></div>`
        : html`<pre class="small diff-text" tabindex="0" aria-label=${t("wb.diff.sql_label")}>${lineDiff(answer.left.sql, answer.right.sql)
            .map(([sign, line]) => html`<span class=${sign === "+" ? "add" : sign === "-" ? "del" : ""}>${sign} ${line}\n</span>`)}</pre>`}

      <h2 class="section-label">${t("wb.diff.plan")}</h2>
      ${plan ? html`<div class="plan-legend">
          <span><i class="mark-key added">+</i>${t("wb.diff.legend.added", { n: plan.counts.added })}</span>
          <span><i class="mark-key removed">−</i>${t("wb.diff.legend.removed", { n: plan.counts.removed })}</span>
          <span><i class="mark-key changed">~</i>${t("wb.diff.legend.changed", { n: plan.counts.changed })}</span>
          <span>${t("wb.diff.legend.same", { n: plan.counts.same })}</span></div>` : null}
      <div class="row g-3">
        <div class="col-12">
          <div class="small fw-semibold mb-1">${answer.left.query ? t("wb.diff.side_registered", { name: answer.left.label }) : t("wb.diff.side_draft", { name: answer.left.label })}</div>
          <${PlanSide} side=${answer.left} which="left" marks=${plan && plan.left} hostRef=${leftHost} title=${t("wb.diff.plan_of", { name: answer.left.label })} />
          ${answer.left.query_metrics ? html`<${Totals} metrics=${answer.left.query_metrics} id="diff-left-metrics" />`
            : html`<div class="small text-muted mt-2">${answer.left.query ? t("wb.diff.no_totals_registered") : t("wb.diff.no_totals")}</div>`}
        </div>
        <div class="col-12">
          <div class="small fw-semibold mb-1">${answer.right.registered_as ? t("wb.diff.side_draft_registered", { name: answer.right.label, as: answer.right.registered_as }) : t("wb.diff.side_draft", { name: answer.right.label })}</div>
          <${PlanSide} side=${answer.right} which="right" marks=${plan && plan.right} hostRef=${rightHost} title=${t("wb.diff.plan_of", { name: answer.right.label })} />
          <div class="small text-muted mt-2" id="diff-right-no-metrics">${t("wb.diff.no_totals")}</div>
        </div>
      </div>

      ${plan ? html`<div class="d-flex flex-wrap justify-content-between align-items-center mt-3">
          <h2 class="section-label m-0">${t("wb.diff.operators")}</h2>
          <div class="form-check form-switch small m-0">
            <input class="form-check-input" type="checkbox" role="switch" id="diff-show-all" checked=${showAll}
              onChange=${(e) => setShowAll(e.target.checked)} />
            <label class="form-check-label" for="diff-show-all">${t("wb.diff.show_all")}</label></div></div>
        ${shown.length ? html`<ul class="diff-list mono small" id="diff-changes">
            ${shown.map((c) => html`<li class=${"diff-" + c.change}>${describeOperator(c)}</li>`)}</ul>`
          : html`<div class="state" id="diff-no-changes"><h2>${t("wb.diff.identical_title")}</h2><p>${t("wb.diff.identical_body")}</p>
              <button type="button" class="btn btn-sm btn-outline-secondary" onClick=${() => setShowAll(true)}>${t("wb.diff.show_all_button")}</button></div>`}
        <p class="small text-muted mt-2 mb-0">${t("wb.diff.matching_note")}</p>` : null}
    </div>
  </div>`;
}
