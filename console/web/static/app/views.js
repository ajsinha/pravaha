/*
 * Pravaha console -- the served-view browser, made live.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The page works as it is: a GET form for the point query, links for the client tabs. This
 * answers the form without a reload, regenerates the snippets for the key just asked
 * about, switches client tabs in place, and keeps all of it in the URL so the exact
 * screen can be pasted to a colleague.
 */
import { call, esc, wireCopyButtons, announce } from "pravaha/lib.js";
import { Grid } from "pravaha/grid.js";

const form = document.getElementById("lookup-form");
const view = form ? decodeURIComponent(form.getAttribute("action").split("/").pop()) : null;
const params = new URLSearchParams(location.search);
let client = params.get("client") || "java";

wireCopyButtons();

function selectClient(id) {
  client = id;
  document.querySelectorAll("#client-tabs [data-client]").forEach((a) =>
    a.setAttribute("aria-selected", a.dataset.client === id ? "true" : "false"));
  document.querySelectorAll(".client-panel").forEach((p) => { p.hidden = p.dataset.client !== id; });
  writeUrl();
}

function writeUrl() {
  const key = document.getElementById("lookup-key").value;
  const value = document.getElementById("lookup-value").value;
  const next = new URLSearchParams();
  if (key) { next.set("key", key); next.set("value", value); }
  if (client !== "java") next.set("client", client);
  history.replaceState(null, "", location.pathname + (next.toString() ? "?" + next : "") + location.hash);
}

document.querySelectorAll("#client-tabs [data-client]").forEach((a) => {
  a.addEventListener("click", (event) => { event.preventDefault(); selectClient(a.dataset.client); });
  a.addEventListener("keydown", (event) => {
    const tabs = [...document.querySelectorAll("#client-tabs [data-client]")];
    const at = tabs.indexOf(a);
    if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
      event.preventDefault();
      const next = tabs[(at + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
      next.focus(); selectClient(next.dataset.client);
    }
  });
});

if (form) {
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const key = document.getElementById("lookup-key").value;
    const value = document.getElementById("lookup-value").value;
    const target = document.getElementById("lookup-result");
    target.innerHTML = '<div class="skeleton" style="height:90px"></div>';
    writeUrl();
    try {
      const answer = await call(`/views/${encodeURIComponent(view)}/lookup`,
        { json: { filters: key ? { [key]: value } : {} } });
      document.getElementById("lookup-sql").textContent = answer.sql;
      if (!answer.rows.length) {
        target.innerHTML = '<div class="state"><h3>No row has that key</h3><p>The view is correct as of its last commit; ' +
          "nothing in it matches. A row appears when the data that makes it arrives.</p></div>";
      } else {
        target.innerHTML = "";
        const host = document.createElement("div");
        target.appendChild(host);
        new Grid(host, { columns: answer.columns, types: answer.types, rows: answer.rows, caption: "Point query answer" });
        target.insertAdjacentHTML("beforeend", `<p class="small text-muted mt-2 mb-0">${answer.returned} row${answer.returned === 1 ? "" : "s"} in ${answer.took_ms} ms${answer.truncated ? " — the first " + answer.returned + " only" : ""}</p>`);
      }
      announce(`${answer.returned} rows`);
    } catch (err) {
      target.innerHTML = `<div class="alert alert-danger py-2 small" role="alert">${esc(err.message)}${err.code ? ` <a href="/help/codes/${esc(err.code)}">${esc(err.code)}</a>` : ""}<div class="mono text-muted">correlation ${esc(err.correlation || "n/a")}</div></div>`;
    }
    try {
      const code = await call(`/views/${encodeURIComponent(view)}/snippets?` + new URLSearchParams(key ? { key, value } : {}));
      Object.entries(code.snippets).forEach(([id, s]) => {
        const read = document.getElementById(`snippet-${id}-read`);
        const sub = document.getElementById(`snippet-${id}-subscribe`);
        if (read) read.textContent = s.read;
        if (sub) sub.textContent = s.subscribe;
      });
    } catch (ignored) { /* the snippets on screen stay as they were: still correct for the earlier key */ }
  });
}
