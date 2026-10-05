/*
 * Pravaha console — the theme menu, the density, and the mega menu's hover.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Ask once. Answer always.
 *
 * MAYA's mechanism, as MAYA's app.js has it: four themes -- Crimson (stored as "light"), Dark,
 * Blue and Green -- chosen from the theme menu, stored in localStorage, and applied as
 * `data-theme` on <html> with `data-bs-theme` beside it, so Bootstrap's own components know
 * that only Dark is dark. Somebody who has never chosen gets the crimson theme, or the dark one
 * when their system is dark: tokens.css answers `prefers-color-scheme` wherever no theme is set.
 *
 * The one addition is the inline snippet in base.html's <head>, which applies a stored choice
 * before the first paint. MAYA's CSP forbids inline script and it lives with the flash; the
 * console has no such policy, and somebody who chose dark should never see a white page first.
 *
 * DENSITY works the same way and is separate from the browser's zoom, because zoom scales the
 * LAYOUT, where this scales only the rows, the cells and the space between sections.
 */
(function () {
  "use strict";

  /* The catalog helper lives in api.js, which loads after this; it is looked up when used. */
  function t(key, params) {
    return window.PravahaApi ? window.PravahaApi.t(key, params) : key;
  }

  var KEY = "pravaha.theme";
  /* The single source of truth for which themes exist, in the theme menu's order. */
  var THEMES = ["light", "dark", "blue", "green"];
  var DENSITY_KEY = "pravaha.density";
  var DENSITIES = ["comfortable", "compact"];
  var PAGE_HELP_KEY = "pravaha.pageHelp";

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return THEMES.indexOf(value) >= 0 ? value : null;
    } catch (e) { return null; }
  }

  /* The theme on screen: the stored one, else what the system asks for. */
  function current() {
    var chosen = document.documentElement.getAttribute("data-theme");
    if (THEMES.indexOf(chosen) >= 0) return chosen;
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  function mark(theme) {
    document.querySelectorAll("[data-theme-choice]").forEach(function (b) {
      b.setAttribute("aria-checked", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
    });
  }

  function applyTheme(theme) {
    if (THEMES.indexOf(theme) < 0) return;
    var root = document.documentElement;
    root.setAttribute("data-theme", theme);
    root.setAttribute("data-bs-theme", theme === "dark" ? "dark" : "light");
    mark(theme);
  }

  function apply(theme) {
    applyTheme(theme);
    try { window.localStorage.setItem(KEY, theme); } catch (e) { /* storage blocked */ }
    var label = document.querySelector('[data-theme-choice="' + theme + '"]');
    announce(t("theme.chosen", {choice: label ? label.textContent.trim() : theme}));
  }

  function density() {
    return document.documentElement.getAttribute("data-density") || "comfortable";
  }

  function applyDensity(choice) {
    if (choice === "comfortable") {
      document.documentElement.removeAttribute("data-density");
    } else {
      document.documentElement.setAttribute("data-density", choice);
    }
    try { window.localStorage.setItem(DENSITY_KEY, choice); } catch (e) {}
    markDensity();
    announce(t("theme.density", {choice: choice}));
  }

  function markDensity() {
    var button = document.getElementById("density-toggle");
    if (button) button.setAttribute("aria-checked", density() === "compact" ? "true" : "false");
  }

  /* Spoken to a screen reader without stealing focus. A control that changes the whole page and
     says nothing leaves somebody who cannot see it guessing whether it did anything. */
  function announce(message) {
    var region = document.getElementById("live-region");
    if (region) region.textContent = message;
  }

  function cycle(list, value) {
    return list[(list.indexOf(value) + 1) % list.length];
  }

  document.addEventListener("DOMContentLoaded", function () {
    mark(current());
    markDensity();
    document.querySelectorAll("[data-theme-choice]").forEach(function (b) {
      b.addEventListener("click", function (ev) {
        ev.preventDefault();
        apply(b.getAttribute("data-theme-choice"));
      });
    });
    var toggle = document.getElementById("density-toggle");
    if (toggle) {
      toggle.addEventListener("click", function (ev) {
        ev.preventDefault();
        applyDensity(cycle(DENSITIES, density()));
      });
    }

    /* The mega menu: on a wide screen a panel opens on hover as well as on click and keyboard. */
    var wide = window.matchMedia("(min-width: 992px)");
    document.querySelectorAll(".pv-nav .mega").forEach(function (li) {
      var trigger = li.querySelector('[data-bs-toggle="dropdown"]');
      var timer = null;
      if (!trigger || !window.bootstrap) return;
      var dd = window.bootstrap.Dropdown.getOrCreateInstance(trigger);
      li.addEventListener("mouseenter", function () {
        if (!wide.matches) return;
        clearTimeout(timer);
        document.querySelectorAll(".pv-nav .mega .dropdown-toggle.show").forEach(function (other) {
          if (other !== trigger) window.bootstrap.Dropdown.getOrCreateInstance(other).hide();
        });
        timer = setTimeout(function () { dd.show(); }, 90);
      });
      li.addEventListener("mouseleave", function () {
        if (!wide.matches) return;
        clearTimeout(timer);
        timer = setTimeout(function () { dd.hide(); }, 180);
      });
    });

    /* "About this page" (_page_help.html): closed once, it stays closed on this browser; the ? in
       the top bar opens it, brings it into view, moves focus to it and flashes its tiles. A
       private window may refuse storage, which only means it is not remembered. */
    var pageHelp = document.getElementById("page-help");
    if (pageHelp) {
      try { if (localStorage.getItem(PAGE_HELP_KEY) === "closed") pageHelp.open = false; } catch (e) { /* private window */ }
      pageHelp.addEventListener("toggle", function () {
        try { localStorage.setItem(PAGE_HELP_KEY, pageHelp.open ? "open" : "closed"); } catch (e) { /* private window */ }
      });
      document.querySelectorAll("[data-page-help]").forEach(function (link) {
        link.addEventListener("click", function (ev) {
          ev.preventDefault();
          pageHelp.open = true;
          var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
          pageHelp.scrollIntoView({behavior: still ? "auto" : "smooth", block: "start"});
          var summary = pageHelp.querySelector("summary");
          if (summary) summary.focus({preventScroll: true});
          pageHelp.classList.remove("ph-flash");
          void pageHelp.offsetWidth;
          pageHelp.classList.add("ph-flash");
        });
      });
    }

    document.addEventListener("keydown", function (event) {
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName) || event.target.isContentEditable) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === "t") { apply(cycle(THEMES, current())); }
      else if (event.key === "d") { applyDensity(cycle(DENSITIES, density())); }
      else if (event.key === "/") {
        var search = document.querySelector('main input[type="search"]');
        if (search) { event.preventDefault(); search.focus(); }
      } else if (event.key === "?") { window.location.href = "/help"; }
    });
  });

  window.PravahaTheme = {apply: apply, stored: stored, current: current, announce: announce};
}());
