/*
 * Pravaha console — the query list.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The form works without this file: it is a real GET to /queries and the server
 * renders the filtered page. This intercepts it so typing filters without a
 * round trip, and keeps the URL in step so the back button and a pasted link
 * both still work.
 */
(function () {
  "use strict";
  var api = window.PravahaApi, States = window.PravahaStates, t = api.t;
  var fresh = new window.PravahaFreshness(document.getElementById("freshness"));
  var tbody = document.querySelector("#table tbody");
  var form = document.getElementById("filters");
  if (!form || !tbody) { return; }

  var state = Object.assign(
    {search: "", state: "", sort: "name", offset: 0, limit: 25}, api.Url.read());
  state.offset = Number(state.offset) || 0;
  state.limit = Number(state.limit) || 25;

  window.clearQueryFilter = function () {
    state.search = ""; state.state = ""; state.offset = 0;
    document.getElementById("search").value = "";
    document.getElementById("state").value = "";
    load();
  };
  window.queriesReload = function () { load(); };

  function row(q) {
    var tone = q.state === "RUNNING" ? "ok" : q.state === "FAILED" ? "bad" : "mute";
    var sql = q.sql.length > 64 ? q.sql.slice(0, 64) + "…" : q.sql;
    return "<tr><td><a href=\"/queries/" + encodeURIComponent(q.name) + "\">" +
      api.escapeHtml(q.name) + "</a></td>" +
      '<td><span class="chip ' + tone + '">' + api.escapeHtml(q.state) + "</span></td>" +
      '<td class="num">' + q.rows_in.toLocaleString() + "</td>" +
      "<td>" + (q.shared
        ? '<span class="chip warn" title="' + api.escapeHtml(t("queries.shared_title")) + '">' +
          api.escapeHtml(t("queries.shared")) + "</span>"
        : "") + "</td>" +
      "<td><code>" + api.escapeHtml(sql) + "</code></td>" +
      "<td><a href=\"/queries/" + encodeURIComponent(q.name) + "\">" + api.escapeHtml(t("queries.open")) +
      "</a></td></tr>";
  }

  async function load() {
    fresh.refreshing();
    api.Url.write(state);
    var query = new URLSearchParams({
      search: state.search, state: state.state, sort: state.sort,
      offset: state.offset, limit: state.limit
    });
    try {
      var page = await api.call("/queries?" + query);
      document.getElementById("banner").innerHTML = "";
      tbody.classList.remove("stale");
      if (!page.items.length) {
        tbody.innerHTML = '<tr><td colspan="6">' +
          ((state.search || state.state)
            ? States.emptyFiltered("clearQueryFilter()")
            : States.emptyNever(t("queries.queries"),
                '<a class="btn btn-sm btn-primary" href="/workbench">' +
                api.escapeHtml(t("queries.register_one")) + "</a>")) +
          "</td></tr>";
      } else {
        tbody.innerHTML = page.items.map(row).join("");
      }
      var count = document.getElementById("count");
      if (count) { count.textContent = t("queries.showing", {n: page.items.length, total: page.total}); }
      fresh.updated();
    } catch (err) {
      fresh.stale();
      /* The server-rendered rows stay, dimmed. Blanking them would throw away
         the last thing known to be true because the next poll failed. */
      tbody.classList.add("stale");
      document.getElementById("banner").innerHTML = States.error(err, "queriesReload()");
    }
  }

  form.addEventListener("submit", function (event) { event.preventDefault(); load(); });

  var timer;
  document.getElementById("search").addEventListener("input", function (event) {
    clearTimeout(timer);
    state.search = event.target.value;
    state.offset = 0;
    timer = setTimeout(load, 200);
  });
  document.getElementById("state").addEventListener("change", function (event) {
    state.state = event.target.value; state.offset = 0; load();
  });
  document.getElementById("sort").addEventListener("change", function (event) {
    state.sort = event.target.value; load();
  });

  setInterval(function () { if (!document.hidden) { load(); } }, 5000);
}());
