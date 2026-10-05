/*
 * Pravaha console -- the assistant's surfaces, made live (ADR-058 phase 3).
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Every assist surface is a server-rendered form that works with scripting off: "Describe it",
 * a draft's questions and its Register, "Explain this query", and "Explain" beside a refusal
 * code. This module sends the same fields to the console's /api/v1/assist/* instead -- through
 * the one request helper, which carries the session's CSRF token -- and puts the fragment the
 * server rendered into the form's result region, a live region, so the answer is read out. A
 * result is drawn in one place, on the server, whether or not this runs.
 *
 * It also answers two things the workbench raises: "Copy to editor" opens the draft's SQL as a
 * new editor tab (the island listens for `pravaha:open-draft`; without the island, the link's own
 * /workbench?sql= does it), and "Explain" beside a diagnostic's code in the island (a button
 * carrying data-assist-explain) asks the same question as the form does.
 */
import { t, announce, call } from "pravaha/lib.js";

if (!window.__pravahaAssist) {
  window.__pravahaAssist = true;

  /* A refusal still carries the server's own fragment (err.payload): the budget, the model's
     normalised error, the empty state. */
  async function post(path, fields) {
    try {
      return { payload: await call(path, { json: fields }), status: 200 };
    } catch (err) {
      return { payload: err.payload || {}, status: err.status || 0 };
    }
  }

  function busy(button, on) {
    if (!button) return;
    if (on) {
      button.dataset.idleLabel = button.innerHTML;
      button.disabled = true;
      button.textContent = button.dataset.busyLabel || t("assist.working");
    } else if (button.dataset.idleLabel !== undefined) {
      button.innerHTML = button.dataset.idleLabel;
      delete button.dataset.idleLabel;
      button.disabled = false;
    }
  }

  function place(target, markup) {
    target.innerHTML = markup;
    target.setAttribute("aria-busy", "false");
    const failed = target.querySelector("[data-assist-failure], [data-assist-empty]");
    announce(failed ? t("assist.failed") : t("assist.answered"));
    target.focus({ preventScroll: false });
  }

  function failureMarkup(status) {
    const box = document.createElement("div");
    box.className = "alert alert-danger py-2 small mb-0";
    box.setAttribute("role", "alert");
    box.setAttribute("data-assist-failure", "transport");
    box.textContent = t("assist.unreachable", { status });
    return box.outerHTML;
  }

  async function ask(path, fields, target, button) {
    target.setAttribute("aria-busy", "true");
    busy(button, true);
    try {
      const { payload, status } = await post(path, fields);
      place(target, payload.html || failureMarkup(status));
    } finally {
      busy(button, false);
    }
  }

  document.addEventListener("submit", (event) => {
    const form = event.target.closest && event.target.closest("form[data-assist]");
    if (!form || !form.dataset.api) return;
    const target = document.getElementById(form.dataset.target || "");
    if (!target) return;
    event.preventDefault();
    const button = event.submitter || form.querySelector("button[type=submit]");
    const fields = Object.fromEntries(new FormData(form));
    delete fields.csrf_token;
    ask(form.dataset.api, fields, target, button);
  });

  document.addEventListener("click", (event) => {
    const copy = event.target.closest && event.target.closest("[data-assist-copy]");
    if (copy && document.getElementById("workbench-app")) {
      event.preventDefault();
      const sql = copy.dataset.sql || "";
      window.dispatchEvent(new CustomEvent("pravaha:open-draft", { detail: { sql, title: copy.dataset.title || "" } }));
      /* The plain form, when the island never mounted: its textarea is the editor. */
      const plain = document.getElementById("sql");
      if (plain && plain.tagName === "TEXTAREA") plain.value = sql;
      announce(t("assist.copied"));
      return;
    }
    const why = event.target.closest && event.target.closest("[data-assist-explain]");
    if (why) {
      event.preventDefault();
      const slot = why.nextElementSibling;
      if (!slot || !slot.classList.contains("assist-slot")) return;
      ask("/assist/explain-refusal", { code: why.dataset.assistExplain, sql: why.dataset.assistSql || "" },
          slot, why);
    }
  });
}
