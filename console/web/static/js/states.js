/*
 * Pravaha console — the eight states of design 23.12.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Most internal tools are polished on the happy path and raw everywhere else.
 * These are the other seven, written once so a screen cannot quietly implement
 * six of them.
 */
(function () {
  "use strict";

  var esc = window.PravahaApi.escapeHtml;

  var States = {
    /* Loading, first time: a skeleton the shape of the answer, never a spinner
       on a blank page. */
    loadingFirst: function (columns, rows) {
      var cells = "";
      for (var c = 0; c < (columns || 4); c++) { cells += '<td><div class="skeleton"></div></td>'; }
      var out = "";
      for (var r = 0; r < (rows || 5); r++) { out += "<tr>" + cells + "</tr>"; }
      return out;
    },

    /* Never had any. Explains what this is and offers the action that makes the
       first one. */
    emptyNever: function (what, action) {
      return '<div class="state"><h2>No ' + esc(what) + ' yet</h2>' +
        "<p>A continuous query is registered once and maintained for as long as it is " +
        "registered. Nothing has been registered on this engine.</p>" + (action || "") + "</div>";
    },

    /* Filtered to nothing, which is a different thing and offers a different way
       out. Conflating the two tells somebody there is no data when there is
       plenty and their filter is wrong. */
    emptyFiltered: function (onClear) {
      return '<div class="state"><h2>Nothing matches this filter</h2>' +
        "<p>There are registered queries, but none match what you have typed. The filter is in " +
        "the URL, so this view is shareable either way.</p>" +
        '<button type="button" class="btn btn-sm btn-outline-secondary" onclick="' +
        onClear + '">Clear the filter</button></div>';
    },

    /* What failed, whether retrying is worth it, and an id to paste into a ticket. */
    error: function (err, retry) {
      var code = err.code
        ? ' <a href="/help/troubleshooting">' + esc(err.code) + "</a>" : "";
      var button = err.retryable && retry
        ? '<button type="button" class="btn btn-sm btn-primary" onclick="' + retry + '">Try again</button>'
        : '<span class="chip mute">Retrying will not help — the request itself was rejected</span>';
      return '<div class="alert alert-danger" role="alert">' +
        '<div class="fw-semibold">' + esc(err.message) + code + "</div>" +
        '<div class="small mono text-muted">correlation ' + esc(err.correlation || "n/a") + "</div>" +
        '<div class="mt-2">' + button + "</div></div>";
    },

    /* Some of it answered. Says what is missing rather than under-reporting in
       silence, which reads as a smaller but complete picture. */
    partial: function (missing) {
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">Showing partial results</div>' +
        '<div class="small">' + esc(missing) + " did not answer. The numbers below are lower " +
        "than the truth, not a complete picture of a smaller thing.</div></div>";
    },

    /* Disconnected. The data dims and says how old it is; it is never presented
       as live. */
    stale: function (ageSeconds, reconnecting) {
      return '<div class="alert alert-warning py-2" role="status">' +
        '<div class="fw-semibold small">Live updates disconnected</div>' +
        '<div class="small">Showing data from ' + esc(ageSeconds) + "s ago. " +
        (reconnecting ? "Reconnecting…" : "Not reconnecting.") + "</div></div>";
    },

    /* No permission. The affordance is disabled with the reason in its title,
       never a button that fails on click. */
    unauthorized: function (action) {
      return '<button type="button" class="btn btn-sm" disabled title="You do not have ' +
        'permission to ' + esc(action) + '">' + esc(action) + "</button>";
    }
  };

  /* How old what is on screen is. Separate from the data because "loading for
     the first time" and "refreshing something already shown" must look
     different: the second keeps the old numbers visible and moves an indicator,
     and the first has no numbers to keep. */
  function Freshness(element) {
    this.element = element;
    this.updatedAt = null;
    this.state = "fresh";
    var self = this;
    setInterval(function () { self.render(); }, 1000);
  }
  Freshness.prototype.refreshing = function () { this.state = "refreshing"; this.render(); };
  Freshness.prototype.updated = function () {
    this.updatedAt = Date.now(); this.state = "fresh"; this.render();
  };
  Freshness.prototype.stale = function () { this.state = "stale"; this.render(); };
  Freshness.prototype.age = function () {
    return this.updatedAt ? Math.round((Date.now() - this.updatedAt) / 1000) : null;
  };
  Freshness.prototype.render = function () {
    if (!this.element) return;
    var age = this.age();
    var label = this.state === "refreshing" ? "refreshing…"
      : age === null ? "never loaded"
      : age < 2 ? "just now" : age + "s ago";
    this.element.setAttribute("data-state", this.state);
    this.element.innerHTML = '<span class="dot"></span><span>' + label + "</span>";
  };

  window.PravahaStates = States;
  window.PravahaFreshness = Freshness;
}());
