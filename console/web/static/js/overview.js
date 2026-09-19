/*
 * Pravaha console — the overview, kept current.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The server rendered the first version of everything here. This refreshes it,
 * which is a different job: the rows stay on screen and a freshness marker
 * moves, rather than the page blanking and refilling every five seconds.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates, t = api.t;
  var fresh = new window.PravahaFreshness(document.getElementById("freshness"));

  function statCard(label, value, note) {
    return '<div class="col-6 col-lg-3"><div class="card stat"><div class="card-body">' +
      '<div class="label">' + label + '</div>' +
      '<div class="value">' + value + '</div>' +
      '<div class="note">' + note + "</div></div></div></div>";
  }

  async function load() {
    fresh.refreshing();
    try {
      var stats = await api.call("/stats");
      var queries = await api.call("/queries?limit=8&sort=-rows_in");
      document.getElementById("stats").innerHTML = [
        statCard(t("overview.registered"), stats.queries, t("overview.registered_note")),
        statCard(t("overview.running"), stats.states.RUNNING || 0, t("overview.running_note")),
        statCard(t("overview.shared"), stats.shared, t("overview.shared_note")),
        statCard(t("overview.feeds"), stats.upstream_subscriptions, t("overview.feeds_note"))
      ].join("");

      var body = document.querySelector("#recent tbody");
      if (queries.items.length) {
        body.innerHTML = queries.items.map(function (q) {
          var tone = q.state === "RUNNING" ? "ok" : q.state === "FAILED" ? "bad" : "mute";
          return "<tr><td><a href=\"/queries/" + encodeURIComponent(q.name) + "\">" +
            api.escapeHtml(q.name) + "</a></td>" +
            '<td><span class="chip ' + tone + '">' + api.escapeHtml(q.state) + "</span></td>" +
            '<td class="num">' + q.rows_in.toLocaleString() + "</td>" +
            "<td>" + (q.shared ? '<span class="chip warn">' + api.escapeHtml(t("overview.shared_chip")) +
              "</span>" : "") + "</td></tr>";
        }).join("");
      } else {
        body.innerHTML = '<tr><td colspan="4">' +
          States.emptyNever(t("overview.queries"), '<a class="btn btn-sm btn-primary" href="/workbench">' +
            api.escapeHtml(t("overview.register_one")) + "</a>") +
          "</td></tr>";
      }
      fresh.updated();
    } catch (err) {
      /* The page keeps whatever the server rendered and says it is stale. It
         does not blank: an operator watching a failing engine needs the last
         known numbers more than an empty screen. */
      fresh.stale();
      var stats = document.getElementById("stats");
      stats.classList.add("stale");
      if (!document.getElementById("overview-error")) {
        stats.insertAdjacentHTML("beforebegin",
          '<div id="overview-error">' + States.error(err, "window.overviewReload()") + "</div>");
      }
    }
  }

  window.overviewReload = function () {
    var banner = document.getElementById("overview-error");
    if (banner) { banner.remove(); }
    document.getElementById("stats").classList.remove("stale");
    load();
  };

  load();
  setInterval(function () { if (!document.hidden) { load(); } }, 5000);
}());
