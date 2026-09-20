/*
 * Pravaha console — stepping a debug session.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The server rendered the session and every control is a real form, so the screen steps with
 * no script at all — each post answers with the page carrying that step's report. What this
 * adds is the thing a person stepping fifty times wants: the step happens without navigating,
 * and the reports stack newest first, so the sequence is readable as a sequence.
 *
 * Nothing is polled and no socket is opened. A fork moves only when somebody steps it
 * (ADR-048 3: no feed thread, no watermark clock, no periodic checkpointer), so there is
 * nothing to arrive that was not asked for. Design 23.11 reserves a WebSocket for this
 * screen and ADR-048 did not build one, for exactly that reason.
 *
 * The operator-state and view panels below are NOT redrawn from a step. They are a read of
 * the fork at the position the page was loaded at, and quietly re-rendering them from a
 * step's report would mean two panels claiming to be the same read when one of them had
 * been rebuilt from something else. The note says to reload for them instead.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates;
  var root = document.getElementById("dbg-app");
  if (!root || !api) { return; }

  var session = root.getAttribute("data-session");
  var log = document.getElementById("dbg-log");
  var empty = document.getElementById("dbg-log-empty");
  var stepped = 0;

  function esc(value) { return api.escapeHtml(value); }
  function num(value) { return Number(value || 0).toLocaleString(); }

  function weight(value) {
    /* A Z-set weight, with a real minus sign: an update is the old row withdrawn at −1 and
       the new one inserted at +1, and a hyphen reads as a dash at this size. */
    return Number(value) < 0 ? "−" + Math.abs(Number(value)) : "+" + Number(value);
  }

  function rowsTable(rows) {
    if (!rows.length) {
      return '<p class="small text-muted mb-3" data-empty="rows">' + esc(api.t("debug.no_rows")) + "</p>";
    }
    var head = ["debug.weight", "debug.stream", "debug.position", "debug.event_time", "debug.values"]
      .map(function (key, i) {
        return '<th scope="col"' + (i === 0 ? ' class="num"' : "") + ">" + esc(api.t(key)) + "</th>";
      }).join("");
    var body = rows.map(function (row) {
      return '<tr><td class="num">' + esc(weight(row.weight)) + "</td><td>" + esc(row.stream)
        + "</td><td>" + esc(row.partition) + "#" + esc(row.offset) + '</td><td class="num">'
        + esc(row.eventTimeNanos) + "</td><td>" + esc((row.values || []).join(", ")) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mono mb-3"><thead><tr>' + head
      + "</tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function operatorsTable(operators) {
    var head = ["debug.node", "debug.operator", "debug.in", "debug.out"]
      .map(function (key, i) {
        return '<th scope="col"' + (i > 1 ? ' class="num"' : "") + ">" + esc(api.t(key)) + "</th>";
      }).join("");
    var body = (operators || []).map(function (op) {
      return '<tr><td class="mono">' + esc(op.id) + "</td><td>" + esc(op.label)
        + '</td><td class="num mono">' + esc(op.rowsIn) + '</td><td class="num mono">'
        + esc(op.rowsOut) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mb-3 dbg-operators"><thead><tr>' + head
      + "</tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function changesTable(changes) {
    if (!changes.length) {
      return '<p class="small text-muted mb-3" data-empty="changes">' + esc(api.t("debug.no_changes")) + "</p>";
    }
    var body = changes.map(function (change) {
      return '<tr><td class="num">' + esc(weight(change.weight)) + "</td><td>"
        + esc((change.values || []).join(", ")) + "</td></tr>";
    }).join("");
    return '<div class="table-responsive"><table class="table table-sm small mono mb-3">'
      + '<thead><tr><th scope="col" class="num">' + esc(api.t("debug.weight"))
      + '</th><th scope="col">' + esc(api.t("debug.values"))
      + "</th></tr></thead><tbody>" + body + "</tbody></table></div>";
  }

  function report(step) {
    var rows = step.rowsIn || [], changes = step.viewChanges || [];
    var watermark = step.watermarkNanos === null || step.watermarkNanos === undefined
      ? api.t("debug.no_watermark") : String(step.watermarkNanos);
    var article = document.createElement("article");
    article.className = "card mb-3 dbg-report";
    article.innerHTML =
      '<div class="card-header d-flex justify-content-between align-items-center"><span>'
      + esc(api.t("debug.step", {n: step.sequence, kind: step.kind})) + '</span><span class="chip '
      + (step.exhausted ? "warn" : "mute") + '">' + esc(step.stopped) + "</span></div>"
      + '<div class="card-body">'
      + '<h3 class="section-label mt-0">' + esc(api.t("debug.rows_in", {n: rows.length})) + "</h3>"
      + rowsTable(rows)
      + '<h3 class="section-label">' + esc(api.t("debug.operators")) + "</h3>"
      + operatorsTable(step.operators)
      + '<h3 class="section-label">' + esc(api.t("debug.changes", {n: changes.length})) + "</h3>"
      + changesTable(changes)
      + '<p class="small text-muted mb-0">' + esc(api.t("debug.tail", {
        rows: num(step.rowsConsumed), view: num(step.viewSize), watermark: watermark
      })) + "</p></div>";
    return article;
  }

  function summarise(step) {
    var set = function (field, text) {
      var chip = document.querySelector('#dbg-summary [data-field="' + field + '"]');
      if (chip) { chip.textContent = text; }
    };
    set("steps", api.t("debug.steps", {n: step.sequence}));
    set("rowsConsumed", api.t("debug.rows", {n: num(step.rowsConsumed)}));
    set("viewSize", api.t("debug.view_size", {n: num(step.viewSize)}));
    set("watermarkNanos", step.watermarkNanos === null || step.watermarkNanos === undefined
      ? api.t("debug.no_watermark") : api.t("debug.watermark", {n: step.watermarkNanos}));
  }

  var note = null;
  function staleBelow() {
    /* Said once, the first time a step moves the fork past what the panels below were read
       at. Repeating it per step would be noise about a thing that is true from step one. */
    if (note || stepped !== 1) { return; }
    note = document.createElement("p");
    note.className = "small text-muted";
    note.id = "dbg-below-stale";
    note.setAttribute("role", "status");
    note.textContent = api.t("debug.reread");
    log.parentNode.insertBefore(note, log.nextSibling);
  }

  function failed(error) {
    var banner = document.createElement("div");
    banner.id = "dbg-step-error";
    banner.innerHTML = States.error(error, true);
    log.insertBefore(banner, log.firstChild);
    /* Retry reloads rather than steps again. A step the engine refused by name (PRV-8015)
       offers no retry at all, because the request itself was wrong; one that failed without
       an answer may or may not have been applied, and the honest way to find out is to ask
       the engine what the session has consumed. */
    var retry = banner.querySelector("[data-state-action=retry]");
    if (retry) { retry.addEventListener("click", function () { window.location.reload(); }); }
  }

  async function take(spec, button) {
    var was = button ? button.textContent : "";
    if (button) { button.disabled = true; button.textContent = api.t("debug.stepping"); }
    try {
      var old = document.getElementById("dbg-step-error");
      if (old) { old.remove(); }
      var step = await api.call("/debug/sessions/" + encodeURIComponent(session) + "/step",
        {method: "POST", body: JSON.stringify({step: spec})});
      if (empty) { empty.remove(); empty = null; }
      log.insertBefore(report(step), log.firstChild);
      summarise(step);
      stepped += 1;
      staleBelow();
    } catch (error) {
      /* A refusal is the engine's, with its code: an unreadable step, a predicate naming a
         column the view does not have, a session that has expired. Shown and kept, because
         the next thing the reader does is fix the spec and step again. */
      failed(error);
    } finally {
      if (button) { button.disabled = false; button.textContent = was; }
    }
  }

  function intercept(form, field) {
    if (!form) { return; }
    form.addEventListener("submit", function (event) {
      /* Which button was pressed: the three quick steps are one form and each carries its own
         spec, so the submitter is the step. `activeElement` is the fallback for a browser
         without SubmitEvent.submitter. */
      var pressed = event.submitter
        || (document.activeElement && document.activeElement.form === form
          ? document.activeElement : null);
      var spec = field ? (document.getElementById(field) || {}).value
        : (pressed && pressed.value);
      /* Nothing to send: let the form post and the server refuse it by name, rather than
         deciding here what an empty box meant. */
      if (!spec) { return; }
      event.preventDefault();
      take(spec, pressed && pressed.tagName === "BUTTON" ? pressed : null);
    });
  }
  intercept(document.getElementById("dbg-step-quick"), null);
  intercept(document.getElementById("dbg-step-form"), "dbg-step");
}());
