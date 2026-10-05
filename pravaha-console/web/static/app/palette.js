/*
 * Pravaha console -- the command palette (Ctrl-K / Cmd-K), on every page.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Design 23.5: a first-class navigation and action surface. Jump to any query, view or
 * stream by name, run a lifecycle action, open the workbench prefilled -- all without the
 * mouse. The item list comes from the server (GET /api/v1/palette), which already knows
 * which actions each query's state allows, so an action that would be refused is absent
 * rather than offered. Drop is never run from here: it goes to the query's page, where
 * the typed-name confirmation lives (design 23.16).
 */
import { html, render, call, announce, t } from "pravaha/lib.js";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";

/* Item kinds, from the UI string catalog; an unknown kind shows as itself. */
function kindLabel(kind) {
  const label = t("palette.kind." + kind);
  return label === "palette.kind." + kind ? kind : label;
}

/* Subsequence match, scored so a match at a word start or a run of letters ranks higher. */
function score(text, needle) {
  if (!needle) return { score: 1, marks: [] };
  const hay = text.toLowerCase();
  const want = needle.toLowerCase();
  let at = 0; let total = 0; let run = 0; const marks = [];
  for (let i = 0; i < hay.length && at < want.length; i++) {
    if (hay[i] === want[at]) {
      marks.push(i);
      run += 1;
      total += 1 + run + (i === 0 || /[\s_\-./]/.test(hay[i - 1]) ? 4 : 0);
      at += 1;
    } else {
      run = 0;
    }
  }
  return at === want.length ? { score: total - hay.length * 0.01, marks } : null;
}

function highlight(text, marks) {
  if (!marks.length) return text;
  const set = new Set(marks); const out = [];
  for (let i = 0; i < text.length; i++) {
    out.push(set.has(i) ? html`<mark>${text[i]}</mark>` : text[i]);
  }
  return out;
}

function submitRole(role) {
  const form = document.createElement("form");
  form.method = "post"; form.action = "/preferences/role";
  const csrf = window.PravahaApi ? window.PravahaApi.csrfToken() : "";
  for (const [name, value] of [["csrf_token", csrf], ["role", role], ["next", "/home"]]) {
    const input = document.createElement("input");
    input.type = "hidden"; input.name = name; input.value = value; form.appendChild(input);
  }
  document.body.appendChild(form); form.submit();
}

function Palette({ onClose, opener }) {
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [needle, setNeedle] = useState("");
  const [active, setActive] = useState(0);
  const [busy, setBusy] = useState("");
  const input = useRef(null);
  const list = useRef(null);

  useEffect(() => {
    input.current && input.current.focus();
    call("/palette").then((payload) => setItems(payload.items || []))
      .catch((err) => setError(err));
    return () => { if (opener && opener.focus) opener.focus(); };
  }, []);

  const matches = useMemo(() => {
    if (!items) return [];
    return items.map((item) => ({ item, m: score(item.title, needle) }))
      .filter((x) => x.m)
      .sort((a, b) => b.m.score - a.m.score)
      .slice(0, 60);
  }, [items, needle]);

  useEffect(() => { setActive(0); }, [needle]);
  useEffect(() => {
    const el = list.current && list.current.querySelector('[aria-selected="true"]');
    if (el) el.scrollIntoView({ block: "nearest" });
  }, [active]);

  async function run(item) {
    if (!item) return;
    if (item.kind === "role") { submitRole(item.role); return; }
    if (item.kind === "lifecycle" && item.action !== "drop") {
      setBusy(item.title);
      try {
        await call(`/queries/${encodeURIComponent(item.query)}/${item.action}`, { method: "POST" });
        announce(t("palette.requested", { query: item.query, action: item.action }));
        onClose();
        if (location.pathname.startsWith("/queries") || location.pathname.startsWith("/operations")) {
          location.reload();
        }
      } catch (err) { setError(err); setBusy(""); }
      return;
    }
    if (item.href) window.location.href = item.href;
  }

  function onKey(event) {
    if (event.key === "Escape") { event.preventDefault(); onClose(); }
    else if (event.key === "ArrowDown") { event.preventDefault(); setActive((a) => Math.min(a + 1, matches.length - 1)); }
    else if (event.key === "ArrowUp") { event.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (event.key === "Enter") { event.preventDefault(); run(matches[active] && matches[active].item); }
    else if (event.key === "Tab") { event.preventDefault(); /* focus stays in the dialog */ }
  }

  return html`<div class="palette-backdrop" onMouseDown=${(e) => { if (e.target === e.currentTarget) onClose(); }}>
    <div class="palette" role="dialog" aria-modal="true" aria-label=${t("palette.label")} onKeyDown=${onKey}>
      <input ref=${input} type="text" role="combobox" aria-expanded="true" aria-controls="palette-list"
        aria-activedescendant=${matches.length ? "palette-item-" + active : ""}
        aria-label=${t("palette.label")} placeholder=${t("palette.placeholder")}
        value=${needle} onInput=${(e) => setNeedle(e.target.value)} autocomplete="off" spellcheck="false" />
      ${error ? html`<div class="px-3 pt-2 small text-danger" role="alert">${error.message}</div>` : null}
      ${busy ? html`<div class="px-3 pt-2 small text-muted" role="status">${busy}…</div>` : null}
      <ul id="palette-list" role="listbox" ref=${list}>
        ${items === null && !error ? html`<li aria-disabled="true"><span class="kind">…</span>${t("palette.loading")}</li>` : null}
        ${items !== null && !matches.length ? html`<li aria-disabled="true">${t("palette.nothing", { needle })}</li>` : null}
        ${matches.map(({ item, m }, i) => html`<li id=${"palette-item-" + i} role="option"
            aria-selected=${i === active ? "true" : "false"}
            onMouseMove=${() => setActive(i)} onClick=${() => run(item)}>
          <span class="kind">${kindLabel(item.kind)}</span>
          <span>${highlight(item.title, m.marks)}</span>
          <span class="hint">${item.hint || ""}</span></li>`)}
      </ul>
      <div class="foot"><span><kbd>↑</kbd> <kbd>↓</kbd> ${t("palette.move")}</span><span><kbd>Enter</kbd> ${t("palette.go")}</span>
        <span><kbd>Esc</kbd> ${t("palette.close")}</span></div>
    </div></div>`;
}

let host = null;
function open() {
  if (host) return;
  const opener = document.activeElement;
  host = document.createElement("div");
  document.body.appendChild(host);
  const close = () => { render(null, host); host.remove(); host = null; };
  render(html`<${Palette} onClose=${close} opener=${opener} />`, host);
}

document.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && (event.key === "k" || event.key === "K")) {
    event.preventDefault();
    event.stopPropagation();
    open();
  }
}, true);  /* capture: the SQL editor uses Ctrl-K as a chord prefix and would swallow it */
const trigger = document.getElementById("palette-trigger");
if (trigger) { trigger.hidden = false; trigger.addEventListener("click", open); }
window.PravahaPalette = { open };
