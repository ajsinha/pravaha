/*
 * Pravaha console, browser side.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential.
 *
 * Plain ES modules against the JSON services (ADR-033). No build step, which is what lets
 * the console ship as one artefact that an operator runs without installing a toolchain.
 *
 * Everything here is progressive enhancement: the server renders working HTML first, and
 * this makes it live. A console that is blank until its JavaScript loads is a console that
 * is blank exactly when someone is debugging why nothing loads.
 */

const API = "/api/v1";

/* ---- theme and density, remembered ---------------------------------------------------- */

export function applyPreferences() {
  const theme = localStorage.getItem("pravaha.theme");
  const density = localStorage.getItem("pravaha.density");
  if (theme) document.documentElement.dataset.theme = theme;
  if (density) document.documentElement.dataset.density = density;
}

export function toggleTheme() {
  const current =
    document.documentElement.dataset.theme ||
    (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  const next = current === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  localStorage.setItem("pravaha.theme", next);
  announce(`${next} theme`);
}

export function toggleDensity() {
  const next = document.documentElement.dataset.density === "compact" ? "comfortable" : "compact";
  document.documentElement.dataset.density = next;
  localStorage.setItem("pravaha.density", next);
  announce(`${next} density`);
}

/** Speaks a change to a screen reader without stealing focus. */
export function announce(message) {
  let region = document.getElementById("live-region");
  if (!region) {
    region = document.createElement("div");
    region.id = "live-region";
    region.className = "visually-hidden";
    region.setAttribute("aria-live", "polite");
    document.body.appendChild(region);
  }
  region.textContent = message;
}

/* ---- fetching ------------------------------------------------------------------------- */

/**
 * One place where an error becomes something a reader can act on.
 *
 * Design 23.20 asks for the cause, the fix and a correlation id. The cause is the engine's
 * message, the fix is usually implied by its PRV code, and the correlation id is generated
 * here so that what the user sees on screen is the same string that is in the console's own
 * log -- which is the whole point of having one.
 */
export async function call(path, options = {}) {
  const correlation = crypto.randomUUID().slice(0, 8);
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", "X-Correlation-Id": correlation, ...(options.headers || {}) },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(payload.error || `request failed with ${response.status}`);
    error.status = response.status;
    error.code = payload.code;
    error.correlation = correlation;
    // 5xx and 503 are worth retrying; a 400 means the request itself was wrong and
    // retrying it unchanged will fail again, so offering a retry button would be a lie.
    error.retryable = response.status >= 500;
    throw error;
  }
  return payload;
}

/* ---- the eight states (design 23.12) --------------------------------------------------- */

export const States = {
  /** Loading, first time: a skeleton the shape of the answer. */
  loadingFirst(columns = 4, rows = 5) {
    const cells = Array.from({ length: columns }, () => `<td><div class="skeleton"></div></td>`).join("");
    return `<tbody aria-busy="true">${Array.from({ length: rows }, () => `<tr>${cells}</tr>`).join("")}</tbody>`;
  },

  /** Never had any. Explains what this is and offers the action that makes the first one. */
  emptyNever(what, action) {
    return `<div class="state"><h3>No ${what} yet</h3>
      <p>A continuous query is registered once and maintained for as long as it is
      registered. Nothing has been registered on this engine.</p>${action || ""}</div>`;
  },

  /** Filtered to nothing, which is a different thing and offers a different way out. */
  emptyFiltered(onClear) {
    return `<div class="state"><h3>Nothing matches this filter</h3>
      <p>There are registered queries, but none match what you have typed. The filter is in
      the URL, so this view is shareable either way.</p>
      <button type="button" onclick="${onClear}">Clear the filter</button></div>`;
  },

  /** What failed, whether retrying is worth it, and an id to paste into a ticket. */
  error(err, retry) {
    const code = err.code ? ` <a href="/help/troubleshooting#${err.code.toLowerCase()}">${err.code}</a>` : "";
    const button = err.retryable && retry
      ? `<button type="button" class="primary" onclick="${retry}">Try again</button>`
      : `<span class="pill mute">Retrying will not help — the request itself was rejected</span>`;
    return `<div class="banner error" role="alert"><span aria-hidden="true">!</span><div class="body">
      <div class="title">${escapeHtml(err.message)}${code}</div>
      <div class="correlation">correlation ${err.correlation || "n/a"}</div>
      <div style="margin-top:8px">${button}</div></div></div>`;
  },

  /** Some of it answered. Says what is missing instead of under-reporting silently. */
  partial(missing) {
    return `<div class="banner warn" role="status"><span aria-hidden="true">~</span><div class="body">
      <div class="title">Showing partial results</div>
      <div>${escapeHtml(missing)} did not answer. The numbers below are lower than the truth,
      not a complete picture of a smaller one.</div></div></div>`;
  },

  /** Disconnected. The data dims and says how old it is; it is never shown as live. */
  stale(ageSeconds, reconnecting) {
    return `<div class="banner warn" role="status"><span aria-hidden="true">~</span><div class="body">
      <div class="title">Live updates disconnected</div>
      <div>Showing data from ${ageSeconds}s ago.
      ${reconnecting ? "Reconnecting…" : "Not reconnecting."}</div></div></div>`;
  },

  /** No permission. The affordance is absent or disabled with the reason, never a trap. */
  unauthorized(action) {
    return `<button type="button" disabled title="You do not have permission to ${escapeHtml(action)}">
      ${escapeHtml(action)}</button>`;
  },
};

export function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/* ---- freshness ------------------------------------------------------------------------ */

/**
 * Tracks how old what is on screen is.
 *
 * Separate from the data itself because "loading for the first time" and "refreshing
 * something already shown" must look different: the second keeps the old numbers visible
 * and moves an indicator, and the first does not have any numbers to keep.
 */
export class Freshness {
  constructor(element) {
    this.element = element;
    this.updatedAt = null;
    this.state = "fresh";
    setInterval(() => this.render(), 1000);
  }
  refreshing() { this.state = "refreshing"; this.render(); }
  updated() { this.updatedAt = Date.now(); this.state = "fresh"; this.render(); }
  stale() { this.state = "stale"; this.render(); }
  age() { return this.updatedAt ? Math.round((Date.now() - this.updatedAt) / 1000) : null; }
  render() {
    if (!this.element) return;
    const age = this.age();
    const label = this.state === "refreshing" ? "refreshing…"
      : age === null ? "never loaded"
      : age < 2 ? "just now" : `${age}s ago`;
    this.element.dataset.state = this.state;
    this.element.innerHTML = `<span class="dot"></span><span>${label}</span>`;
  }
}

/* ---- live tail ------------------------------------------------------------------------ */

/**
 * A live tail over server-sent events.
 *
 * The connection is to the console's service layer, not to the engine: many browsers
 * watching one view share a single engine subscription behind it, so opening the page in
 * ten tabs costs the engine one subscriber rather than ten.
 */
export class LiveTail {
  constructor(view, target, { filters = {}, max = 500, onState } = {}) {
    this.view = view;
    this.target = target;
    this.filters = filters;
    this.max = max;
    this.onState = onState || (() => {});
    this.received = 0;
    this.source = null;
  }

  start() {
    const params = new URLSearchParams(this.filters).toString();
    this.source = new EventSource(`${API}/views/${encodeURIComponent(this.view)}/stream${params ? "?" + params : ""}`);

    this.source.addEventListener("open", () => this.onState("live"));
    this.source.addEventListener("row", (event) => this.append(JSON.parse(event.data)));
    this.source.addEventListener("lag", (event) => {
      const { dropped } = JSON.parse(event.data);
      // Said out loud rather than hidden. A tail that silently drops rows shows a reader
      // a sample and lets them believe it is everything.
      this.onState("lagging", `${dropped} rows dropped — this browser is behind the stream`);
    });
    this.source.addEventListener("error", () => this.onState("stale"));
    this.source.onerror = () => this.onState("stale");
  }

  append(row) {
    this.received += 1;
    const line = document.createElement("div");
    line.className = "new";
    line.textContent = JSON.stringify(row);
    this.target.appendChild(line);
    while (this.target.childElementCount > this.max) this.target.removeChild(this.target.firstChild);
    this.target.scrollTop = this.target.scrollHeight;
  }

  stop() {
    if (this.source) this.source.close();
    this.source = null;
  }
}

/* ---- URL state ------------------------------------------------------------------------- */

/**
 * Every filter lives in the URL (design 23.20: every view deep-linkable).
 *
 * Which means the back button works, a filtered view can be pasted into a ticket, and a
 * reload does not throw away what someone had narrowed down to.
 */
export const Url = {
  read() { return Object.fromEntries(new URLSearchParams(location.search)); },
  write(params, { replace = true } = {}) {
    const search = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== "" && v != null));
    const url = `${location.pathname}${search.toString() ? "?" + search : ""}`;
    if (replace) history.replaceState(null, "", url); else history.pushState(null, "", url);
  },
};

/* ---- keyboard --------------------------------------------------------------------------- */

export function installShortcuts(map) {
  document.addEventListener("keydown", (event) => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName);
    if (typing && event.key !== "Escape") return;
    const handler = map[event.key];
    if (handler) { event.preventDefault(); handler(event); }
  });
}

applyPreferences();
