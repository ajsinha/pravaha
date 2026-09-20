/*
 * Pravaha console — a blue/green replacement and the backfill behind it.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The server rendered the first answer and every control is a real form, so the screen works
 * with no script at all. This keeps the numbers current over one server-sent stream at 1 Hz
 * (design 23.11's "long jobs" row), dims them and says how old they are when the stream drops
 * (23.12's stale state), and swaps the plain cutover and rollback buttons for the
 * typed-name confirmation a destructive action deserves (23.16).
 *
 * It draws no bar and no estimate, because there is no denominator: the source does not say
 * how much history it holds. What moves here is what the engine measured.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates;
  var root = document.getElementById("rep-app");
  var initial = document.getElementById("rep-initial");
  if (!root || !initial) { return; }

  var query = window.location.pathname.split("/")[2];
  var started = JSON.parse(initial.textContent || "null");
  var fresh = new window.PravahaFreshness(document.getElementById("rep-freshness"));
  var banner = document.getElementById("rep-banner");
  var source = null, reloading = false;
  fresh.updated();

  var num = function (v, digits) {
    return Number(v || 0).toLocaleString(undefined,
      { minimumFractionDigits: digits || 0, maximumFractionDigits: digits || 0 });
  };
  function set(field, html) {
    var cell = document.querySelector('#rep-numbers [data-field="' + field + '"]');
    if (cell) { cell.innerHTML = html; }
  }
  function chip(kind, label) {
    return '<span class="chip ' + kind + '">' + api.escapeHtml(label) + "</span>";
  }

  function render(status) {
    var bf = status.backfill || {};
    set("historyRows", api.escapeHtml(num(bf.historyRows)));
    set("liveRows", api.escapeHtml(num(bf.liveRows)));
    set("rowsPerSecond", api.escapeHtml(api.t("cutover.rows_per_second", { n: num(bf.rowsPerSecond) })));
    set("partitions", api.escapeHtml(api.t("cutover.of", { live: num(bf.partitionsLive), total: num(bf.partitions) })));
    /* Never zero for "not known": the candidate has not reached the live stream yet, and a
       0.0 s lag would say it had caught up exactly. */
    set("lagSeconds", bf.lagSeconds === null || bf.lagSeconds === undefined
      ? '<span class="text-muted">' + api.escapeHtml(api.t("cutover.lag_unknown")) + "</span>"
      : api.escapeHtml(api.t("cutover.seconds", { n: num(bf.lagSeconds, 1) })));
    set("rateLimit", bf.rateLimit
      ? api.escapeHtml(api.t("cutover.rows_per_second", { n: num(bf.rateLimit) }))
      : '<span class="text-muted">' + api.escapeHtml(api.t("cutover.no_limit")) + "</span>");
    set("historyComplete", bf.historyComplete
      ? chip("ok", api.t("cutover.history_complete")) : chip("warn", api.t("cutover.history_reading")));
    set("paused", bf.paused
      ? chip("mute", api.t("cutover.paused")) : chip("ok", api.t("cutover.running")));

    /* The state decides which controls exist at all, and those are the server's to draw --
       one place that decides what may be pressed, not two that can disagree. So a state
       that has moved on is a reload, once, rather than a set of buttons rebuilt here. */
    var chipEl = document.getElementById("rep-state");
    if (chipEl && status.state && chipEl.dataset.state !== status.state && !reloading) {
      reloading = true;
      window.location.reload();
    }
  }

  function stale() {
    if (!root.classList.contains("stale")) { return; }
    banner.innerHTML = States.stale(fresh.age(), source !== null);
  }
  setInterval(stale, 1000);

  function connect() {
    if (source) { source.close(); }
    source = new EventSource("/api/v1/queries/" + encodeURIComponent(query) + "/replacement/stream");
    source.addEventListener("replacement", function (event) {
      var payload = JSON.parse(event.data);
      if (!payload.replacement) {
        /* It ended while the page was open -- abandoned elsewhere, or finished. The screen
           for "there is no replacement" is a different screen, so ask the server for it. */
        if (!reloading) { reloading = true; window.location.reload(); }
        return;
      }
      render(payload.replacement);
      root.classList.remove("stale");
      fresh.updated();
      banner.innerHTML = "";
    });
    source.addEventListener("failed", function (event) {
      var payload = JSON.parse(event.data);
      /* No correlation id: an EventSource cannot send the header api.js mints for a fetch,
         so the error state says the id is not available rather than showing an invented one
         that is in no log line. */
      banner.innerHTML = States.error(
        { message: payload.message, code: payload.code, retryable: true }, true);
      var retry = banner.querySelector("[data-state-action=retry]");
      if (retry) { retry.addEventListener("click", function () { window.location.reload(); }); }
      fresh.stale();
      root.classList.add("stale");
    });
    source.onerror = function () { fresh.stale(); root.classList.add("stale"); stale(); };
  }
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) { if (source) { source.close(); source = null; } }
    else { connect(); }
  });
  window.addEventListener("beforeunload", function () { if (source) { source.close(); } });
  if (started) { render(started); connect(); }

  /* Cutover and rollback move what every reader of the name sees, so each is confirmed by the
     typed name -- the same control the drop dialog uses, for the same reason: a plain
     OK/Cancel is what a hurried click clears without reading. The plain form stays the one
     that works with no script; this replaces it only once it has proven it can run. */
  function typedConfirmation(plainId, triggerId, inputId, submitId, modalId) {
    var plain = document.getElementById(plainId);
    var trigger = document.getElementById(triggerId);
    var input = document.getElementById(inputId);
    var submit = document.getElementById(submitId);
    if (!plain || !trigger || !input || !submit) { return; }
    plain.closest("form").classList.add("d-none");
    trigger.classList.remove("d-none");
    var expected = input.getAttribute("data-expected");
    input.addEventListener("input", function () {
      submit.disabled = input.value !== expected;
    });
    var modal = document.getElementById(modalId);
    if (modal) {
      modal.addEventListener("hidden.bs.modal", function () {
        input.value = ""; submit.disabled = true;
      });
      modal.addEventListener("shown.bs.modal", function () { input.focus(); });
    }
  }
  typedConfirmation("rep-cutover-plain", "rep-cutover", "cutoverConfirmInput",
    "cutoverConfirmSubmit", "cutoverConfirmModal");
  typedConfirmation("rep-rollback-plain", "rep-rollback-btn", "rollbackConfirmInput",
    "rollbackConfirmSubmit", "rollbackConfirmModal");
}());
