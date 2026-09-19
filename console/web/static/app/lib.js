/*
 * Pravaha console -- what every island shares.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Plain ES modules, resolved by the import map in base.html; no bundler, no build. The
 * request helper is the classic api.js one (window.PravahaApi), so a refusal renders the
 * same way on every screen and its correlation id is the one in the console's log.
 */
import { h, render } from "preact";
import htm from "htm";

export const html = htm.bind(h);
export { h, render };

const legacy = () => window.PravahaApi;

/** A JSON call to the console's own API. Throws an Error carrying status, code, correlation. */
export async function call(path, options = {}) {
  const init = Object.assign({}, options);
  if (init.json !== undefined) {
    init.body = JSON.stringify(init.json);
    init.method = init.method || "POST";
    delete init.json;
  }
  return legacy().call(path, init);
}

export const esc = (value) => legacy().escapeHtml(value);

/* The `js.*` keys of the UI string catalog (web/i18n/<language>.json), embedded by the shell
   as JSON so an island needs no request to speak the page's language. */
let messages = null;

/** A UI string by key, with {named} parameters; the key itself when the catalog lacks it. */
export function t(key, params = {}) {
  if (messages === null) {
    try { messages = JSON.parse(document.getElementById("i18n-messages").textContent || "{}"); }
    catch (e) { messages = {}; }
  }
  const template = messages[key];
  if (template === undefined) return key;
  return template.replace(/\{(\w+)\}/g, (whole, name) => (name in params ? String(params[name]) : whole));
}

/** Spoken to a screen reader through the shell's one live region, never stealing focus. */
export function announce(message) {
  const region = document.getElementById("live-region");
  if (region) { region.textContent = ""; setTimeout(() => { region.textContent = message; }, 30); }
}

export function debounce(fn, ms) {
  let timer;
  const wrapped = (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
  wrapped.cancel = () => clearTimeout(timer);
  return wrapped;
}

/** A design token's current value, so a canvas chart can wear the active theme. */
export function token(name, fallback = "") {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

/** Calls back when the theme changes -- the picker writes data-theme, the OS flips its scheme. */
export function onThemeChange(callback) {
  const observer = new MutationObserver(() => callback());
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  media.addEventListener("change", callback);
  return () => { observer.disconnect(); media.removeEventListener("change", callback); };
}

export async function copyText(text, button) {
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    /* Clipboard API needs a secure context; a plain-http console behind a proxy is common, so
       fall back to the selection the user can copy themselves. */
    const area = document.createElement("textarea");
    area.value = text; document.body.appendChild(area); area.select();
    try { document.execCommand("copy"); } catch (ignored) { /* selection remains */ }
    area.remove();
  }
  if (button) {
    const before = button.textContent;
    button.textContent = "Copied";
    setTimeout(() => { button.textContent = before; }, 1400);
  }
  announce("Copied to the clipboard");
}

/** Wires every [data-copy-target] button on the page to copy its target's text. */
export function wireCopyButtons(root = document) {
  root.querySelectorAll("[data-copy-target]").forEach((button) => {
    if (button.dataset.wired) return;
    button.dataset.wired = "1";
    button.hidden = false;
    button.addEventListener("click", () => {
      const target = document.getElementById(button.dataset.copyTarget);
      if (target) copyText(target.textContent, button);
    });
  });
}

/** Local storage that never throws: a private window or a blocked origin just forgets. */
export const store = {
  get(key, fallback) {
    try { const raw = localStorage.getItem(key); return raw === null ? fallback : JSON.parse(raw); }
    catch (e) { return fallback; }
  },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* forget */ } },
};

/** Whether a column's Arrow or SQL type is numeric, for alignment and chart candidates. */
export function isNumericType(type) {
  return /int|float|double|decimal|numeric|bigint|real|uint/i.test(String(type || ""));
}

export function formatValue(value) {
  if (value === null || value === undefined) return "null";
  if (typeof value === "number") return Number.isInteger(value) ? value.toLocaleString() : String(value);
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function errorView(err) {
  const code = err && err.code
    ? html`<a class="ms-1" href=${"/help/codes/" + encodeURIComponent(err.code)}>${err.code}</a>` : null;
  return html`<div class="alert alert-danger py-2 mb-2" role="alert">
    <div class="fw-semibold small">${err.message || String(err)}${code}</div>
    <div class="small mono text-muted">correlation ${err.correlation || "n/a"}${
      err.retryable ? " · retryable" : ""}</div></div>`;
}
