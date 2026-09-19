/*
 * Pravaha console — the component gallery's eight states.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Each card is filled by the same PravahaStates function a screen calls, with sample data
 * the template supplies, so the gallery shows what the screens show and cannot drift from
 * it. Nothing here fetches: the states are drawn, not provoked.
 */
(function () {
  "use strict";
  var States = window.PravahaStates;
  var samples = JSON.parse(document.getElementById("g-samples").textContent || "{}");
  var table = document.getElementById("g-sample-table");
  var rows = table ? table.querySelector("tbody").innerHTML : "";
  var head = table ? table.querySelector("thead").outerHTML : "";

  function sampleTable(extra) {
    return '<div class="table-responsive"><table class="table table-sm mb-0' + (extra || "") + '">' +
      head + "<tbody>" + rows + "</tbody></table></div>";
  }
  function fill(key, html) {
    var target = document.getElementById("state-" + key);
    if (target) target.innerHTML = html;
  }

  fill("loading_first", '<table class="table table-sm mb-0" aria-busy="true">' + head + "<tbody>" +
    States.loadingFirst(4, 3) + "</tbody></table>");
  fill("loading_refresh", '<span class="freshness mb-2" id="g-freshness"></span>' + sampleTable());
  new window.PravahaFreshness(document.getElementById("g-freshness")).refreshing();
  fill("empty_never", States.emptyNever(samples.what,
    '<a class="btn btn-sm btn-primary" href="/start">' + window.PravahaApi.escapeHtml(samples.create) + "</a>"));
  fill("empty_filtered", States.emptyFiltered("void 0"));
  fill("error", States.error({message: samples.error, code: "PRV-8002", retryable: true,
    correlation: "c-5f2e9a1b"}, "void 0"));
  fill("partial", States.partial(samples.missing) + sampleTable());
  fill("stale", States.stale(42, true) + '<div class="stale">' + sampleTable() + "</div>");
  fill("unauthorized", States.unauthorized(samples.action, samples.reason));
}());
