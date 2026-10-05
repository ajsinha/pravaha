/*
 * Pravaha console — a live tail over server-sent events.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * The connection is to the console's service layer, not to the engine: many
 * browsers watching one view share a single engine subscription behind it, so
 * opening the page in ten tabs costs the engine one subscriber rather than ten.
 */
(function () {
  "use strict";

  function LiveTail(view, target, options) {
    options = options || {};
    this.view = view;
    this.target = target;
    this.filters = options.filters || {};
    this.max = options.max || 500;
    this.onState = options.onState || function () {};
    this.received = 0;
    this.source = null;
  }

  LiveTail.prototype.start = function () {
    var self = this;
    var params = new URLSearchParams(this.filters).toString();
    var url = window.PravahaApi.base + "/views/" + encodeURIComponent(this.view) +
      "/stream" + (params ? "?" + params : "");
    this.source = new EventSource(url);

    this.source.addEventListener("open", function () { self.onState("live"); });
    this.source.addEventListener("row", function (event) {
      self.append(JSON.parse(event.data));
    });
    this.source.addEventListener("lag", function (event) {
      var dropped = JSON.parse(event.data).dropped;
      /* Said out loud rather than hidden. A tail that silently drops rows shows
         a reader a sample and lets them believe it is everything. */
      self.onState("lagging", window.PravahaApi.t("tail.dropped", {n: dropped}));
    });
    this.source.addEventListener("error", function () { self.onState("stale"); });
    this.source.onerror = function () { self.onState("stale"); };
  };

  LiveTail.prototype.append = function (row) {
    this.received += 1;
    /* The "nothing yet" placeholder the page came with: the tail has had data now. */
    var empty = this.target.querySelector(".tail-empty");
    if (empty) { empty.remove(); }
    var line = document.createElement("div");
    line.className = "new";
    line.textContent = JSON.stringify(row);
    this.target.appendChild(line);
    while (this.target.childElementCount > this.max) {
      this.target.removeChild(this.target.firstChild);
    }
    this.target.scrollTop = this.target.scrollHeight;
  };

  LiveTail.prototype.stop = function () {
    if (this.source) { this.source.close(); }
    this.source = null;
  };

  window.PravahaLiveTail = LiveTail;
}());
