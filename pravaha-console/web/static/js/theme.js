/*
 * Pravaha console — the theme menu, the density, and the mega menu's hover.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Ask once. Answer always.
 *
 * drishti's mechanism: seven themes -- Terminal, Parchment, Blue, Wall Street, Green, Crimson and
 * Crimson dark, under drishti's ids -- chosen from the theme menu (each with its swatch), stored
 * in localStorage, and applied as `data-theme` on <html> with `data-bs-theme` beside it, so
 * Bootstrap's own components know which three are dark. Somebody who has never chosen gets
 * Crimson, Pravaha's brand, whatever the system prefers.
 *
 * theme-init.js, loaded in <head> before the stylesheets, applies a stored choice before the
 * first paint (no inline script: the CSP allows none without a nonce) and migrates the earlier
 * four-theme key; this file is the menu, the keyboard and the density.
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

  var KEY = "pravaha.theme.v2";
  /* The single source of truth for which themes exist, in the theme menu's order (drishti's). */
  var THEMES = ["terminal", "light", "blue", "wallstreet", "green", "crimson", "crimson-dark"];
  var DEFAULT = "crimson";
  var DARK = ["terminal", "wallstreet", "crimson-dark"];
  var DENSITY_KEY = "pravaha.density";
  var DENSITIES = ["comfortable", "compact"];
  var PAGE_HELP_KEY = "pravaha.pageHelp";

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return THEMES.indexOf(value) >= 0 ? value : null;
    } catch (e) { return null; }
  }

  /* The theme on screen: the stored one, else the default. */
  function current() {
    var chosen = document.documentElement.getAttribute("data-theme");
    return THEMES.indexOf(chosen) >= 0 ? chosen : DEFAULT;
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
    root.setAttribute("data-bs-theme", DARK.indexOf(theme) >= 0 ? "dark" : "light");
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
      /* Anchored under its own button (nav.css); one that would run off the right edge of a narrower
         screen is slid left until it fits, 16px in from the edge. */
      var panel = li.querySelector(".mega-panel");
      trigger.addEventListener("shown.bs.dropdown", function () {
        if (!panel || !wide.matches) return;
        panel.style.left = "";
        var over = panel.getBoundingClientRect().right - (window.innerWidth - 16);
        if (over > 0) panel.style.left = (-over) + "px";
      });
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
      } else if (event.key === "?") {
        /* drishti's ?: About this page, where the page has one (the ? in the top bar); else the
           help for this screen. F1 always opens the screen's help (help-keys.js). */
        var about = document.querySelector("[data-page-help]");
        if (about) { event.preventDefault(); about.click(); }
        else { window.location.href = "/help/context/" + encodeURIComponent(document.body.getAttribute("data-screen") || "help"); }
      }
    });
  });

  window.PravahaTheme = {apply: apply, stored: stored, current: current, announce: announce, themes: THEMES.slice()};
  /* The engine's pulse (_nav.html #engine-live): /health/ready every 10s while the tab is visible -- the
     console asks the engine, so green means the Java engine is live and answering, amber DEGRADED, red not
     answering. */
  (function () {
    var live = document.getElementById("engine-live");
    if (!live || !window.fetch) return;
    var text = live.querySelector("[data-live-text]");
    function show(state, words) {
      live.setAttribute("data-live-state", state);
      live.title = words;
      if (text) text.textContent = words;
    }
    function check() {
      if (document.hidden) return;
      var at = new Date().toLocaleTimeString();
      fetch("/health/ready", {cache: "no-store", credentials: "same-origin"}).then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) {
          var engine = body.engine || {};
          var where = engine.url ? " at " + engine.url : "";
          if (r.ok && engine.status === "DEGRADED") {
            show("degraded", "Engine" + where + " is up but DEGRADED (checked " + at + ")");
          } else if (r.ok) {
            show("live", "Engine" + where + " is live and answering (checked " + at + ")");
          } else {
            show("down", "Engine" + where + " is not answering (checked " + at + ")");
          }
        });
      }).catch(function () { show("down", "The console cannot be reached (checked " + at + ")"); });
    }
    check();
    setInterval(check, 10000);
    document.addEventListener("visibilitychange", function () { if (!document.hidden) check(); });
  }());
}());
