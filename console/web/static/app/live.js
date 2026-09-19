/*
 * Pravaha console -- live results: a view's committed changes, as they happen.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The stream is the console's own SSE endpoint, behind which one engine subscription is
 * shared by every browser watching the view (services.Broadcaster) -- so this tab costs
 * the engine nothing extra. Rows arrive with their weight. The "current rows" table is the
 * running Z-set sum of the view as the stream's first event gives it plus every change
 * since: a row is present while its weight is positive, which is exactly how a correction
 * (-1 old, +1 new) should look. No key is assumed -- the engine does not publish a view's
 * key yet -- so a row's identity is its whole value, which is also what a weight is
 * attached to. The starting view comes down the same stream as the changes, so none falls
 * between the two (SUB-1).
 *
 * Hidden tab: the stream is closed and reopened on return, and the new stream starts from
 * the view again (design 23.11: document.hidden suspends every subscription).
 */
import { esc, announce, formatValue, isNumericType, t } from "pravaha/lib.js";
import { themedChart, timeSeriesBase, lineSeries } from "pravaha/charts.js";

const app = document.getElementById("live-app");
if (app) start(app);

function start(root) {
  const view = root.dataset.view;
  const buffer = Number(root.dataset.buffer) || 256;
  let columns = JSON.parse(root.dataset.columns || "[]");
  let types = JSON.parse(root.dataset.types || "[]");
  const stateEl = document.getElementById("live-state");
  const pauseBtn = document.getElementById("live-pause");
  const banner = document.getElementById("live-banner");
  const rowsBody = document.querySelector("#current-rows tbody");
  const logBody = document.querySelector("#change-log tbody");
  const chartColumn = document.getElementById("chart-column");
  const chartMode = document.getElementById("chart-mode");

  const params = new URLSearchParams(location.search);
  let filter = params.get("filter") || "";          // "column=value"
  let source = null;
  let paused = false;
  let counts = { changes: 0, plus: 0, minus: 0, dropped: 0 };
  let current = new Map();                           // identity -> {values, weight, at}
  let log = [];
  let samples = [];                                  // [ms, value]
  let arrivals = [];
  let dirty = false;
  let lastEvent = 0;

  /* ------------------------------------------------------------- helpers */
  const identity = (values) => JSON.stringify(values);
  const valuesOf = (row) => columns.map((c) => (c in row ? row[c] : null));
  const States = window.PravahaStates;
  let lastGood = null;         /* when the stream last said anything: what "stale" is measured from */
  let ended = null;            /* the engine's reason, when it ended the stream rather than dropped it */
  function setState(state, text) {
    stateEl.dataset.state = state;
    stateEl.innerHTML = `<span class="dot"></span><span>${esc(text)}</span>`;
    if (state === "fresh") lastGood = Date.now();
    /* Disconnected (23.12): what is on screen dims and a banner says how old it is and whether
       it is coming back -- never stale rows presented as live. */
    root.querySelectorAll("[data-live-data]").forEach((el) => el.classList.toggle("stale", state === "stale"));
    renderBanner();
  }
  function renderBanner() {
    if (ended) {
      banner.innerHTML = States.error({ message: t("live.ended", { error: ended }), retryable: true }, true);
    } else if (stateEl.dataset.state === "stale" && source !== null) {
      banner.innerHTML = States.stale(lastGood ? Math.round((Date.now() - lastGood) / 1000) : null, true);
    } else if (stateEl.dataset.state === "stale") {
      banner.innerHTML = States.stale(lastGood ? Math.round((Date.now() - lastGood) / 1000) : null, false);
    } else {
      banner.innerHTML = "";
    }
  }
  banner.addEventListener("click", (event) => {
    if (event.target.closest("[data-state-action=retry]")) { ended = null; connect(); }
  });

  function numericColumns() {
    return columns.filter((c, i) => isNumericType(types[i]));
  }
  function fillChartColumns() {
    const numeric = numericColumns();
    /* A measure rather than an identifier: the total of txn_id is a number nobody wants. */
    const measure = numeric.find((c) => !/(^id$|_id$|^id_)/i.test(c));
    const chosen = params.get("chart") || measure || numeric[0] || "";
    chartColumn.innerHTML = numeric.length
      ? numeric.map((c) => `<option value="${esc(c)}" ${c === chosen ? "selected" : ""}>${esc(c)}</option>`).join("")
      : `<option value="">${esc(t("live.no_numeric"))}</option>`;
    chartColumn.disabled = !numeric.length;
    if (!numeric.length) chartMode.value = "count";
  }

  /* ------------------------------------------------------------- tables */
  let headed = "";
  function renderHead() {
    const key = JSON.stringify([columns, types]);
    if (key === headed) return;
    headed = key;
    document.querySelector("#current-rows thead").innerHTML = "<tr>" + columns.map((c, i) =>
      `<th scope="col" class="${isNumericType(types[i]) ? "num" : ""}">${esc(c)}</th>`).join("") + "</tr>";
  }
  function renderRows(changed) {
    renderHead();
    const live = [...current.values()].filter((r) => r.weight > 0);
    document.getElementById("c-rows").textContent = live.length.toLocaleString();
    if (!live.length) {
      /* Filtered to nothing is not an empty view, and says how to stop filtering (23.12). */
      rowsBody.innerHTML = `<tr><td colspan="${Math.max(1, columns.length)}">${filter
        ? States.emptyFiltered(true, t("live.empty_body_filtered"))
        : `<div class="state"><h2>${esc(t("live.empty_title"))}</h2><p>${esc(t("live.empty_body"))}</p></div>`}</td></tr>`;
      return;
    }
    const shown = live.sort((a, b) => b.at - a.at).slice(0, 500);
    rowsBody.innerHTML = shown.map((r) => `<tr class="${changed && changed.has(identity(r.values)) ? "changed" : ""}">` +
      r.values.map((v, i) => `<td class="${isNumericType(types[i]) ? "num" : ""}">${esc(formatValue(v))}</td>`).join("") +
      (r.weight > 1 ? `<td><span class="chip info" title="${esc(t("live.duplicate_title", { n: r.weight }))}">×${r.weight}</span></td>` : "") +
      "</tr>").join("");
  }
  function renderLog() {
    if (!log.length) return;
    logBody.innerHTML = log.slice(0, 200).map((e) => `<tr class="${e.weight < 0 ? "retracted" : ""}">
      <td class="text-nowrap small text-muted">${new Date(e.at).toLocaleTimeString()}</td>
      <td><span class="weight ${e.weight < 0 ? "minus" : "plus"}">${e.weight > 0 ? "+" : "−"}${Math.abs(e.weight)}</span></td>
      <td class="mono small">${esc(columns.map((c, i) => `${c}=${formatValue(e.values[i])}`).join("  "))}</td></tr>`).join("");
  }
  function renderCounters() {
    document.getElementById("c-changes").textContent = counts.changes.toLocaleString();
    document.getElementById("c-plus").textContent = counts.plus.toLocaleString();
    document.getElementById("c-minus").textContent = counts.minus.toLocaleString();
    document.getElementById("c-dropped").textContent = counts.dropped.toLocaleString();
  }

  /* ------------------------------------------------------------- the stream */
  function loaded() {
    const skeleton = document.getElementById("rows-loading");
    if (skeleton) { skeleton.removeAttribute("id"); skeleton.removeAttribute("aria-busy"); }
    const note = document.getElementById("rows-loading-note");
    if (note) note.remove();
  }
  function rebase(snapshot) {
    loaded();
    /* The view the changes apply to, sent by the stream itself as its first event (SUB-1).
       It used to be read here before the stream opened, and a commit landing between the
       read and the stream reached this page by neither: the table was quietly wrong. */
    const rows = snapshot.rows || [];
    if (!columns.length && rows.length) columns = Object.keys(rows[0]).filter((k) => k !== "_weight");
    current = new Map();
    rows.forEach((row) => {
      const values = valuesOf(row);
      const id = identity(values);
      const existing = current.get(id);
      current.set(id, { values, weight: (existing ? existing.weight : 0) + (Number(row._weight ?? 1) || 1), at: 0 });
    });
    document.getElementById("rows-note").textContent = snapshot.truncated
      ? t("live.rows_note_truncated", { n: snapshot.returned }) : t("live.rows_note");
    renderRows();
  }

  function apply(row) {
    const weight = Number(row._weight ?? 1) || 1;
    if (!columns.length) columns = Object.keys(row).filter((k) => k !== "_weight");
    const values = valuesOf(row);
    const id = identity(values);
    const at = Date.now();
    const existing = current.get(id) || { values, weight: 0, at };
    existing.weight += weight; existing.at = at;
    if (existing.weight === 0) current.delete(id); else current.set(id, existing);
    counts.changes += 1;
    if (weight > 0) counts.plus += weight; else counts.minus += -weight;
    log.unshift({ at, weight, values });
    if (log.length > buffer) log.length = buffer;
    const ci = columns.indexOf(chartColumn.value);
    if (ci >= 0 && weight > 0 && typeof values[ci] === "number") arrivals.push([at, values[ci]]);
    dirty = true;
    lastEvent = at;
  }

  function connect() {
    if (source) source.close();
    const query = filter.includes("=") ? "?" + new URLSearchParams([filter.split(/=(.*)/s).slice(0, 2)]) : "";
    setState("refreshing", t("live.state.connecting"));
    source = new EventSource(`/api/v1/views/${encodeURIComponent(view)}/stream${query}`);
    source.addEventListener("open", () => { ended = null; setState("fresh", filter ? t("live.state.live_filtered", { filter }) : t("live.state.live")); });
    source.addEventListener("snapshot", (event) => { rebase(JSON.parse(event.data)); });
    source.addEventListener("row", (event) => { if (!paused) apply(JSON.parse(event.data)); });
    source.addEventListener("lag", (event) => {
      counts.dropped = JSON.parse(event.data).dropped;
      /* Said out loud: a tail that silently drops shows a sample and lets it pass as everything. */
      setState("refreshing", t("live.state.sampled", { n: counts.dropped }));
      renderCounters();
    });
    source.addEventListener("error", (event) => {
      let message = "";
      try { message = JSON.parse(event.data).message; } catch (e) { message = ""; }
      if (message) {
        ended = message;
        source.close();
        source = null;
      }
      setState("stale", message ? t("live.state.stopped") : t("live.state.reconnecting"));
    });
  }
  function disconnect() { if (source) { source.close(); source = null; } setState("stale", t("live.state.hidden")); }
  function clearFilter() {
    document.getElementById("tap-column").value = "";
    document.getElementById("tap-value").value = "";
    document.getElementById("tap-form").requestSubmit();
  }
  rowsBody.closest("table").addEventListener("click", (event) => {
    if (event.target.closest("[data-state-action=clear]")) clearFilter();
  });

  /* ------------------------------------------------------------- chart */
  let chart = null;
  function series() {
    const mode = chartMode.value;
    if (mode === "arrivals") return arrivals.slice(-600);
    return samples.slice(-600);
  }
  function sample() {
    const mode = chartMode.value;
    const ci = columns.indexOf(chartColumn.value);
    const live = [...current.values()].filter((r) => r.weight > 0);
    let value = null;
    if (mode === "count") value = live.reduce((n, r) => n + r.weight, 0);
    else if (mode === "sum" && ci >= 0) value = live.reduce((s, r) => s + (typeof r.values[ci] === "number" ? r.values[ci] * r.weight : 0), 0);
    /* A disconnected second is a gap, never an interpolated line (design 23.13). */
    if (!source || stateEl.dataset.state === "stale") value = null;
    samples.push([Date.now(), value]);
    if (samples.length > 1200) samples = samples.slice(-900);
  }
  function chartOptions() {
    const mode = chartMode.value;
    const label = mode === "count" ? t("live.chart.count_label")
      : mode === "sum" ? t("live.chart.sum_label", { column: chartColumn.value })
        : t("live.chart.arrivals_label", { column: chartColumn.value });
    const base = timeSeriesBase({ yName: label });
    base.legend.show = false;   /* one series: the axis name says what it is */
    const s = lineSeries(label, series(), 0);
    if (mode === "arrivals") { s.type = "scatter"; s.symbolSize = 8; delete s.showSymbol; }
    base.series = [s];
    return base;
  }

  /* ------------------------------------------------------------- wiring */
  document.getElementById("tap-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const col = document.getElementById("tap-column").value;
    const val = document.getElementById("tap-value").value;
    filter = col ? `${col}=${val}` : "";
    const next = new URLSearchParams(location.search);
    if (filter) next.set("filter", filter); else next.delete("filter");
    history.replaceState(null, "", location.pathname + (next.toString() ? "?" + next : ""));
    log = []; counts = { changes: 0, plus: 0, minus: 0, dropped: 0 }; samples = []; arrivals = [];
    renderCounters();
    connect();
    announce(filter ? t("live.announce_filter", { filter }) : t("live.announce_all"));
  });
  if (filter.includes("=")) {
    const [c, v] = filter.split(/=(.*)/s);
    document.getElementById("tap-column").value = c;
    document.getElementById("tap-value").value = v || "";
  }
  pauseBtn.hidden = false;
  pauseBtn.addEventListener("click", () => {
    paused = !paused;
    pauseBtn.textContent = paused ? t("live.resume") : t("live.pause");
    pauseBtn.setAttribute("aria-pressed", paused ? "true" : "false");
    if (paused) setState("refreshing", t("live.state.paused"));
    else connect();
  });
  chartColumn.addEventListener("change", () => { samples = []; arrivals = []; if (chart) chart.redraw(); });
  chartMode.addEventListener("change", () => { samples = []; if (chart) chart.redraw(); });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) disconnect();
    else if (!paused) connect();
  });
  window.addEventListener("beforeunload", () => { if (source) source.close(); });

  fillChartColumns();
  themedChart(document.getElementById("live-chart"), chartOptions).then((c) => { chart = c; })
    .catch(() => { document.getElementById("chart-caption").textContent = t("live.chart.failed"); });

  /* Painting is conflated to at most five frames a second however fast rows arrive: the
     tables show state, and state read at 5 Hz is not state missed. */
  let changedIds = new Set();
  setInterval(() => {
    if (!dirty) return;
    dirty = false;
    changedIds = new Set(log.slice(0, 20).filter((e) => Date.now() - e.at < 1000).map((e) => identity(e.values)));
    renderRows(changedIds); renderLog(); renderCounters();
  }, 200);
  setInterval(() => {
    if (document.hidden || paused) return;
    sample();
    if (chart) {
      const data = series();
      chart.chart.setOption({ series: [{ data, showSymbol: chartMode.value === "arrivals" || data.filter((p) => p[1] !== null).length < 3 }] });
    }
    if (stateEl.dataset.state === "stale" && !ended) renderBanner();   /* the age moves on */
    if (source && stateEl.dataset.state === "fresh" && lastEvent) {
      const quiet = Math.round((Date.now() - lastEvent) / 1000);
      if (quiet >= 5) setState("fresh", t("live.state.quiet", { seconds: quiet }));
    }
  }, 1000);

  connect();
}
