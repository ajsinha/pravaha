/*
 * Pravaha console -- the operations dashboard, kept current at 1 Hz.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The server rendered the first answer; this keeps it current over one SSE stream whose
 * snapshot the console scrapes once a second however many people are watching (design
 * 23.11 and 23.17). A hidden tab closes the stream. A dropped stream dims the numbers and
 * says how old they are -- never stale numbers presented as live (design 23.12).
 *
 * Charts: one y-axis each, a fixed 0-100 % scale for state so two queries compare, at most
 * eight series with colours assigned to a query when it first appears and never cycled.
 */
import { esc, announce } from "pravaha/lib.js";
import { themedChart, timeSeriesBase, lineSeries } from "pravaha/charts.js";

const root = document.getElementById("ops-app");
if (root) start();

function start() {
  const fresh = new window.PravahaFreshness(document.getElementById("ops-freshness"));
  const slots = new Map();              // query -> colour slot, first come, never reassigned
  const history = { rate: {}, state: {} };
  let latest = null;
  let lastVerdict = null;
  let source = null;

  function slotOf(name) {
    if (!slots.has(name) && slots.size < 8) slots.set(name, slots.size);
    return slots.has(name) ? slots.get(name) : -1;
  }

  function push(snapshot) {
    const at = Math.round(snapshot.at * 1000);
    (snapshot.queries || []).forEach((q) => {
      if (slotOf(q.name) < 0) return;
      (history.rate[q.name] = history.rate[q.name] || []).push([at, q.rows_in_rate ?? null]);
      (history.state[q.name] = history.state[q.name] || []).push([at, q.state_fraction == null ? null : q.state_fraction * 100]);
    });
    for (const table of [history.rate, history.state]) {
      Object.values(table).forEach((points) => { if (points.length > 300) points.splice(0, points.length - 300); });
    }
  }

  const chip = (state) => state ? `<span class="chip ${state === "RUNNING" ? "ok" : state === "FAILED" ? "bad" : "mute"}"><span class="dot"></span>${esc(state)}</span>` : '<span class="chip mute">unlisted</span>';
  const fmt = (v, digits = 0) => (v === null || v === undefined ? "—" : Number(v).toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits }));
  function lag(v) {
    if (v === null || v === undefined) return '<span class="text-muted" title="No row with an event time has arrived">no watermark</span>';
    if (v < 90) return `${v.toFixed(0)} s`;
    if (v < 5400) return `${(v / 60).toFixed(0)} min`;
    return `${(v / 3600).toFixed(1)} h`;
  }

  /* Checkpoint health as the engine publishes it: the age of the last stored one, how long
     it took, and failures. "not checkpointing" when there is no last success to age. */
  function checkpoint(q) {
    let out = q.checkpoint_age_seconds === null || q.checkpoint_age_seconds === undefined
      ? '<span class="text-muted">not checkpointing</span>'
      : `${lag(q.checkpoint_age_seconds)} ago${q.checkpoint_duration_seconds != null ? ` · took ${q.checkpoint_duration_seconds.toFixed(2)} s` : ""}`;
    if (q.checkpoint_failures) out += ` · <span class="chip warn">${fmt(q.checkpoint_failures)} failed</span>`;
    return out;
  }

  function render(s) {
    const v = s.verdict;
    const verdict = document.getElementById("verdict");
    verdict.className = `verdict ${v.status} mb-3`;
    verdict.innerHTML = `<i class="icon bi ${v.status === "ok" ? "bi-check-circle-fill" : v.status === "warn" ? "bi-exclamation-triangle-fill" : "bi-x-octagon-fill"}" aria-hidden="true"></i>
      <div><div class="headline">${esc(v.headline)}</div>${v.where ? `<div class="small text-muted">Where: <span class="mono">${esc(v.where)}</span></div>` : ""}</div>`;
    /* Rate-limited speech: only a change of verdict is announced, never every second. */
    if (lastVerdict !== null && lastVerdict !== v.headline) announce(v.headline);
    lastVerdict = v.headline;

    document.getElementById("findings").innerHTML = s.findings.length ? s.findings.map((f) => `<div class="finding">
      <span class="sev chip ${f.severity === "critical" ? "bad" : f.severity === "warn" ? "warn" : "info"}">${esc(f.severity)}</span>
      <div><div class="fw-semibold">${f.query ? `<a class="mono" href="/queries/${encodeURIComponent(f.query)}">${esc(f.query)}</a> — ` : ""}${esc(f.title)}</div>
      <div class="small text-muted">${esc(f.detail)}</div></div></div>`).join("")
      : '<div class="state py-4"><h3>Nothing</h3><p>No query is near its state ceiling, stopped, or behind in event time.</p></div>';

    const body = document.querySelector("#ops-queries tbody");
    body.innerHTML = s.queries.length ? s.queries.map((q) => {
      const f = q.state_fraction;
      const gauge = f === null || f === undefined
        ? `<span class="text-muted small">${q.metrics_published ? "—" : "not published yet"}</span>`
        : `<div class="d-flex align-items-center gap-2"><div class="gauge ${f >= 0.9 ? "critical" : f >= 0.75 ? "warn" : ""}" style="width:7rem" role="meter" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(f * 100)}" aria-label="state held as a share of its ceiling"><span style="width:${Math.min(100, f * 100)}%"></span></div><span class="small">${Math.round(f * 100)}%</span></div>`;
      return `<tr><td><a class="mono" href="/queries/${encodeURIComponent(q.name)}">${esc(q.name)}</a>${q.shared ? ' <span class="chip warn">shared</span>' : ""}</td>
        <td>${chip(q.state)}</td><td class="num">${fmt(q.rows_in)}</td><td class="num">${fmt(q.rows_in_rate, 1)}</td>
        <td>${gauge}</td><td class="num">${fmt(q.view_size)}</td><td class="num">${q.metrics_published ? lag(q.watermark_lag_seconds) : "—"}</td>
        <td class="num">${fmt(q.subscribers)}</td>
        <td class="num">${q.commit_latency_mean_seconds === null || q.commit_latency_mean_seconds === undefined ? "—" : (q.commit_latency_mean_seconds * 1000).toFixed(1) + " ms"}</td>
        <td class="small">${checkpoint(q)}</td></tr>`;
    }).join("") : '<tr><td colspan="10"><div class="state"><h3>No queries registered</h3><p>Once one is, its throughput, state and lag are here.</p><a class="btn btn-sm btn-primary" href="/start">Get started</a></div></td></tr>';
  }

  function build(table, { yName, fixed100 }) {
    return () => {
      const base = timeSeriesBase({ yName, yFormatter: fixed100 ? (v) => (v === null || v === undefined ? "no data" : `${Math.round(v)}%`) : null });
      if (fixed100) { base.yAxis.min = 0; base.yAxis.max = 100; }
      const names = [...slots.keys()];
      base.legend.show = names.length >= 2;
      base.series = names.map((name) => lineSeries(name, table[name] || [], slots.get(name)));
      if (!names.length) {
        base.graphic = { type: "text", left: "center", top: "middle", style: { text: "No query to chart yet", fill: getComputedStyle(document.documentElement).getPropertyValue("--muted"), fontSize: 13 } };
      }
      return base;
    };
  }

  let rateChart = null; let stateChart = null;
  const initial = JSON.parse(document.getElementById("ops-initial").textContent || "null");
  if (initial) { latest = initial; push(initial); fresh.updated(); }
  themedChart(document.getElementById("chart-rate"), build(history.rate, { yName: "rows/s" })).then((c) => { rateChart = c; });
  themedChart(document.getElementById("chart-state"), build(history.state, { yName: "% of ceiling", fixed100: true })).then((c) => { stateChart = c; });

  function connect() {
    if (source) source.close();
    source = new EventSource("/api/v1/ops/stream");
    source.addEventListener("snapshot", (event) => {
      latest = JSON.parse(event.data);
      push(latest);
      render(latest);
      root.classList.remove("stale");
      fresh.updated();
      if (rateChart) rateChart.redraw();
      if (stateChart) stateChart.redraw();
    });
    source.onerror = () => { fresh.stale(); root.classList.add("stale"); };
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { if (source) { source.close(); source = null; } }
    else connect();
  });
  window.addEventListener("beforeunload", () => { if (source) source.close(); });
  connect();
}
