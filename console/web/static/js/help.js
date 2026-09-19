/* Pravaha console -- the help index's live filter.
   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
   Proprietary and confidential. See LICENSE at the repository root.

   The index works without this: the search box is a GET form to /help/search, which searches
   every page's full text. This only narrows the cards in place as somebody types -- every word
   must match a card's title, summary, headings, codes or keywords -- opens every category while
   a filter is active, and hides a category with nothing left in it. Enter still submits. */
(function () {
  "use strict";
  var box = document.getElementById("help-search");
  if (!box) { return; }
  var items = Array.prototype.slice.call(document.querySelectorAll(".help-item"));
  var cats = Array.prototype.slice.call(document.querySelectorAll(".help-cat"));
  var none = document.getElementById("help-nohits");
  var more = document.getElementById("help-nohits-search");
  var opened = null;

  function apply() {
    var words = box.value.toLowerCase().split(/\s+/).filter(Boolean);
    var any = false;
    items.forEach(function (item) {
      var text = item.getAttribute("data-search") || "";
      var hit = words.every(function (w) { return text.indexOf(w) !== -1; });
      item.hidden = !hit;
      if (hit) { any = true; }
    });
    if (words.length && opened === null) {
      opened = cats.map(function (c) { return c.open; });
    }
    cats.forEach(function (cat, i) {
      var visible = cat.querySelectorAll(".help-item:not([hidden])").length > 0;
      cat.hidden = !visible;
      if (words.length) { cat.open = true; } else if (opened) { cat.open = opened[i]; }
    });
    if (!words.length) { opened = null; }
    none.style.display = any ? "none" : "block";
    if (more) { more.setAttribute("href", "/help/search?q=" + encodeURIComponent(box.value.trim())); }
  }

  box.addEventListener("input", apply);
  // A chip opens the category it points at, in case somebody had closed it.
  document.querySelectorAll(".cat-chips a").forEach(function (chip) {
    chip.addEventListener("click", function () {
      var target = document.getElementById("cat-" + chip.getAttribute("data-cat"));
      if (target) { target.open = true; }
    });
  });
  // /help?q=... arrives filtered.
  var q = new URLSearchParams(window.location.search).get("q");
  if (q) { box.value = q; apply(); }
})();
