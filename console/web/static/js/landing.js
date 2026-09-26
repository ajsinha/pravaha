/*
 * Pravaha console — the landing page's figure: sources in, one engine, every reader kept answered.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * One <canvas> and plain script: no library, nothing fetched. On the left, the sources a query
 * reads, named the way the product names its connectors. In the middle, the engine. On the
 * right, what reads the answer. Rows travel as weights -- an insert is a +1 in the accent, a
 * retraction a −1 in `--retract` -- into the engine, which applies them a commit at a time and
 * pulses once per commit, and the same weights travel on to the readers. The −1 is the point:
 * the answer is maintained, and a retraction is how it is corrected.
 *
 * Every colour and font is a design token, read with getComputedStyle when the figure starts
 * and again whenever the theme changes, so the figure is crimson in crimson, blue in blue,
 * green in green and legible in dark. Every word on it comes from the string catalog, through
 * the canvas's data-net attribute.
 *
 * `prefers-reduced-motion` gets one still frame that still says the sentence -- weights on the
 * edges, an update drawn as its −1 and its +1 -- and no loop at all. That is also the frame the
 * visual baselines photograph, because the harness emulates reduced motion; the still frame is
 * a pure function of the canvas's size and the theme, with nothing random and nothing timed.
 * The loop stops whenever the page is hidden or the figure is scrolled out of view.
 *
 * The canvas is aria-hidden; the section under it says everything the figure says, in words.
 */
(function () {
  "use strict";

  var canvas = document.getElementById("hero-net");
  if (!canvas || !canvas.getContext) { return; }
  var ctx = canvas.getContext("2d");
  var words;
  try { words = JSON.parse(canvas.getAttribute("data-net") || "{}"); } catch (e) { words = {}; }
  var SOURCES = words.sources || [];
  var READERS = words.readers || [];
  var PLUS = words.plus || "+1";
  var MINUS = words.minus || "−1";

  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  var scheme = window.matchMedia("(prefers-color-scheme: dark)");

  /* ------------------------------------------------------------------ the theme */
  var C = {}, F = {};

  function readTheme() {
    var css = getComputedStyle(document.documentElement);
    var fallback = getComputedStyle(canvas).color;
    function token(name) { return css.getPropertyValue(name).trim() || fallback; }
    C.plus = token("--flow");
    C.glow = token("--flow-l");
    C.minus = token("--retract");
    C.onChip = token("--on-flow");
    C.ink = token("--ink");
    C.label = token("--slate");
    C.muted = token("--muted");
    C.edge = token("--edge");
    C.ground = token("--surface");
    F.code = token("--code");
    F.serif = token("--serif");
  }

  /* A token with an alpha, for glows and fading edges. The palette's colours are hex; rgb() is
     handled too, and anything else is returned as it is and drawn opaque. */
  function alpha(colour, a) {
    var hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(colour);
    var r, g, b;
    if (hex) {
      var h = hex[1].length === 3 ? hex[1].replace(/(.)/g, "$1$1") : hex[1];
      r = parseInt(h.slice(0, 2), 16); g = parseInt(h.slice(2, 4), 16); b = parseInt(h.slice(4, 6), 16);
    } else {
      var rgb = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i.exec(colour);
      if (!rgb) { return colour; }
      r = +rgb[1]; g = +rgb[2]; b = +rgb[3];
    }
    return "rgba(" + r + "," + g + "," + b + "," + Math.max(0, Math.min(1, a)).toFixed(3) + ")";
  }

  /* ------------------------------------------------------------------ the layout */
  var W = 0, H = 0, narrow = false, hub = {x: 0, y: 0, r: 24}, left = [], right = [];

  function column(names, x, side) {
    return names.map(function (label, i) {
      var f = names.length === 1 ? 0.5 : i / (names.length - 1);
      var bow = Math.sin(f * Math.PI) * W * (narrow ? 0.02 : 0.035);
      var y = H * 0.1 + (H * 0.8) * f;
      var node = {x: x + side * bow, y: y, label: label, side: side, glow: 0, tone: "plus"};
      /* The curve every weight travels: out of the node level, and into the hub. */
      node.c = {x: (node.x + hub.x) / 2, y: node.y};
      return node;
    });
  }

  function layout() {
    var box = canvas.getBoundingClientRect();
    W = Math.max(1, box.width);
    H = Math.max(1, box.height);
    var ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(W * ratio);
    canvas.height = Math.round(H * ratio);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    narrow = W < 560;
    hub = {x: W / 2, y: H * 0.42, r: narrow ? 19 : 25};
    var inset = Math.max(narrow ? 90 : 136, W * 0.2);
    left = column(SOURCES, inset, -1);
    right = column(READERS, W - inset, 1);
  }

  function along(node, t, inbound) {
    /* A point on the quadratic from the node to the hub (inbound) or back out to it. */
    var a = inbound ? node : hub, b = inbound ? hub : node, u = 1 - t;
    return {x: u * u * a.x + 2 * u * t * node.c.x + t * t * b.x,
            y: u * u * a.y + 2 * u * t * node.c.y + t * t * b.y};
  }

  /* ------------------------------------------------------------------ drawing */
  function font(weight, size, family) { return weight + " " + size + "px " + family; }

  function drawEdges() {
    ctx.lineWidth = 1;
    left.concat(right).forEach(function (n) {
      ctx.strokeStyle = alpha(C.edge, 0.22 + n.glow * 0.4);
      ctx.beginPath();
      ctx.moveTo(n.x, n.y);
      ctx.quadraticCurveTo(n.c.x, n.c.y, hub.x, hub.y);
      ctx.stroke();
    });
  }

  function drawNode(n) {
    var colour = n.tone === "minus" ? C.minus : C.plus;
    var halo = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, 18);
    halo.addColorStop(0, alpha(colour, 0.16 + n.glow * 0.42));
    halo.addColorStop(1, alpha(colour, 0));
    ctx.fillStyle = halo;
    ctx.beginPath(); ctx.arc(n.x, n.y, 18, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = colour;
    ctx.beginPath(); ctx.arc(n.x, n.y, 3.6 + n.glow * 2.4, 0, Math.PI * 2); ctx.fill();
    ctx.font = font(600, narrow ? 10 : 11.5, F.code);
    ctx.textBaseline = "middle";
    ctx.textAlign = n.side < 0 ? "right" : "left";
    ctx.fillStyle = n.glow > 0.35 ? C.ink : C.label;
    ctx.fillText(n.label, n.x + (n.side < 0 ? -12 : 12), n.y);
  }

  function drawHub(beat, ring, breathe) {
    var R = hub.r;
    var reach = R * 3.4 + beat * R * 0.8;
    var aura = ctx.createRadialGradient(hub.x, hub.y, 0, hub.x, hub.y, reach);
    aura.addColorStop(0, alpha(C.glow, 0.26 + beat * 0.22));
    aura.addColorStop(0.45, alpha(C.glow, 0.08));
    aura.addColorStop(1, alpha(C.glow, 0));
    ctx.fillStyle = aura;
    ctx.beginPath(); ctx.arc(hub.x, hub.y, reach, 0, Math.PI * 2); ctx.fill();
    /* The ring: born at the core on each commit, widening and fading until the next. */
    if (ring < 1) {
      ctx.strokeStyle = alpha(C.plus, 0.55 * (1 - ring));
      ctx.lineWidth = 1.6;
      ctx.beginPath(); ctx.arc(hub.x, hub.y, R * 0.9 + ring * R * 1.9, 0, Math.PI * 2); ctx.stroke();
    }
    ctx.strokeStyle = alpha(C.plus, 0.35);
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.arc(hub.x, hub.y, R, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = C.plus;
    ctx.beginPath(); ctx.arc(hub.x, hub.y, R * 0.52 + breathe * 2.2 + beat * 2.5, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = C.onChip;
    ctx.beginPath(); ctx.arc(hub.x, hub.y, 3, 0, Math.PI * 2); ctx.fill();
  }

  function drawWordmark() {
    var y = hub.y + hub.r + 14;
    var mark = words.hub || "";
    var slogan = words.slogan || "";
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    ctx.font = font(700, narrow ? 13 : 15, F.serif);
    if ("letterSpacing" in ctx) { ctx.letterSpacing = narrow ? "2px" : "3px"; }
    var wide = ctx.measureText(mark).width;
    ctx.font = font("italic 400", narrow ? 11.5 : 13, F.serif);
    if ("letterSpacing" in ctx) { ctx.letterSpacing = "0px"; }
    wide = Math.max(wide, ctx.measureText(slogan).width) + 16;
    /* A knockout in the ground's own colour, drawn last, so neither an edge nor a weight
       passing under the wordmark runs through its letters. */
    ctx.fillStyle = alpha(C.ground, 0.9);
    ctx.fillRect(hub.x - wide / 2, y - 4, wide, (narrow ? 40 : 46));
    ctx.fillStyle = C.ink;
    ctx.font = font(700, narrow ? 13 : 15, F.serif);
    if ("letterSpacing" in ctx) { ctx.letterSpacing = narrow ? "2px" : "3px"; }
    ctx.fillText(mark, hub.x, y);
    if ("letterSpacing" in ctx) { ctx.letterSpacing = "0px"; }
    ctx.fillStyle = C.muted;
    ctx.font = font("italic 400", narrow ? 11.5 : 13, F.serif);
    ctx.fillText(slogan, hub.x, y + (narrow ? 19 : 22));
  }

  /* A weight in flight: a filled disc in its colour with its sign written on it. */
  function drawChip(x, y, weight, size) {
    var colour = weight > 0 ? C.plus : C.minus;
    var trail = ctx.createRadialGradient(x, y, 0, x, y, size * 2);
    trail.addColorStop(0, alpha(colour, 0.3));
    trail.addColorStop(1, alpha(colour, 0));
    ctx.fillStyle = trail;
    ctx.beginPath(); ctx.arc(x, y, size * 2, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = colour;
    ctx.beginPath(); ctx.arc(x, y, size, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = C.onChip;
    ctx.font = font(700, size < 8.5 ? 8 : 9, F.code);
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(weight > 0 ? PLUS : MINUS, x, y + 0.5);
  }

  /* ------------------------------------------------------------------ the still frame */
  /* What a reader who asked for no motion sees, and what the visual baselines photograph: an
     insert and an update on their way in -- the update as its −1 and its +1 on one edge -- and
     the same weights on their way out to the readers. Positions are fractions of the edges, so
     the frame is the same picture at every width. */
  var STILL_IN = [[0, 0.58, -1], [0, 0.36, 1], [1, 0.55, 1], [4, 0.5, -1], [5, 0.62, 1]];
  var STILL_OUT = [[0, 0.5, 1], [0, 0.72, -1], [2, 0.55, 1], [3, 0.86, -1], [5, 0.6, 1]];

  function drawStill() {
    stills += 1;
    ctx.clearRect(0, 0, W, H);
    left.concat(right).forEach(function (n) { n.glow = 0; n.tone = "plus"; });
    STILL_IN.forEach(function (s) { var n = left[s[0]]; if (n) { n.glow = 0.7; n.tone = s[2] < 0 ? "minus" : n.tone; } });
    STILL_OUT.forEach(function (s) { var n = right[s[0]]; if (n) { n.glow = 0.7; n.tone = s[2] < 0 ? "minus" : n.tone; } });
    drawEdges();
    drawHub(0.5, 0.45, 0.5);
    STILL_IN.forEach(function (s) {
      var n = left[s[0]]; if (!n) { return; }
      var p = along(n, s[1], true); drawChip(p.x, p.y, s[2], 9);
    });
    STILL_OUT.forEach(function (s) {
      var n = right[s[0]]; if (!n) { return; }
      var p = along(n, s[1], false); drawChip(p.x, p.y, s[2], 8);
    });
    left.concat(right).forEach(drawNode);
    drawWordmark();
  }

  /* ------------------------------------------------------------------ the motion */
  /* The commits, in order, forever: [source, weight] pairs. An update is a −1 and a +1 from
     one source in one commit; a delete is a lone −1. Scripted rather than random, so what the
     figure says is the same every time it says it. */
  var SCRIPT = [
    [[1, 1]],
    [[0, -1], [0, 1]],
    [[2, 1], [5, 1]],
    [[4, -1]],
    [[3, 1]],
    [[2, -1], [2, 1]],
    [[1, 1], [4, 1]],
    [[5, -1]]
  ];
  var COMMIT_MS = 1900, TRAVEL_MS = 1150, STAGGER = 0.16;
  var packets = [], pending = {}, commitNo = 0, sinceCommit = COMMIT_MS - 300;
  var beatAt = -1e9, clock = 0, frame = 0, last = 0, frames = 0, stills = 0;

  function ease(t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

  function spawnCommit() {
    var id = commitNo, changes = SCRIPT[commitNo % SCRIPT.length];
    commitNo += 1;
    pending[id] = changes.length;
    changes.forEach(function (change, i) {
      var node = left[change[0] % Math.max(1, left.length)];
      if (!node) { return; }
      packets.push({node: node, inbound: true, weight: change[1], t: -i * STAGGER, commit: id, changes: changes});
    });
  }

  function arrive(p) {
    pending[p.commit] -= 1;
    if (pending[p.commit] > 0) { return; }
    delete pending[p.commit];
    /* The commit is applied: one pulse, and its changes go out to three readers in turn. */
    beatAt = clock;
    if (!right.length) { return; }
    for (var k = 0; k < 3; k += 1) {
      var reader = right[(p.commit + k * 2) % right.length];
      p.changes.forEach(function (change, i) {
        packets.push({node: reader, inbound: false, weight: change[1], t: -i * STAGGER - k * 0.05, commit: p.commit});
      });
    }
  }

  function tick(now) {
    var dt = Math.min(48, now - last || 16);
    last = now;
    clock += dt;
    frames += 1;
    sinceCommit += dt;
    if (sinceCommit >= COMMIT_MS) { sinceCommit = 0; spawnCommit(); }

    ctx.clearRect(0, 0, W, H);
    left.concat(right).forEach(function (n) { n.glow *= 0.94; });
    for (var i = packets.length - 1; i >= 0; i -= 1) {
      var p = packets[i];
      p.t += dt / TRAVEL_MS;
      if (p.t >= 1) {
        packets.splice(i, 1);
        if (p.inbound) { arrive(p); } else { p.node.glow = 1; p.node.tone = p.weight < 0 ? "minus" : "plus"; }
      } else if (p.t > 0 && p.inbound && p.t < 0.2) {
        p.node.glow = 1; p.node.tone = p.weight < 0 ? "minus" : "plus";
      }
    }
    var since = clock - beatAt;
    var beat = Math.max(0, 1 - since / 700);
    var ring = Math.min(1, since / 1400);
    var breathe = (Math.sin(clock / 850) + 1) / 2;

    drawEdges();
    drawHub(beat, ring, breathe);
    packets.forEach(function (p) {
      if (p.t <= 0) { return; }
      var at = along(p.node, ease(p.t), p.inbound);
      drawChip(at.x, at.y, p.weight, p.inbound ? 9 : 8);
    });
    left.concat(right).forEach(drawNode);
    drawWordmark();
    frame = window.requestAnimationFrame(tick);
  }

  /* ------------------------------------------------------------------ running, or not */
  var onscreen = true;

  function wanted() { return !reduce.matches && !document.hidden && onscreen; }

  function stop() {
    if (frame) { window.cancelAnimationFrame(frame); frame = 0; }
  }

  function start() {
    if (frame || !wanted()) { return; }
    last = performance.now();
    frame = window.requestAnimationFrame(tick);
  }

  function refresh() {
    stop();
    if (reduce.matches) { drawStill(); } else { start(); }
  }

  function restyle() { readTheme(); if (!frame) { refresh(); } }

  function begin() {
    readTheme();
    layout();
    refresh();
    document.addEventListener("visibilitychange", function () { if (document.hidden) { stop(); } else { start(); } });
    reduce.addEventListener("change", refresh);
    scheme.addEventListener("change", restyle);
    new MutationObserver(restyle).observe(document.documentElement, {attributes: true, attributeFilter: ["data-theme"]});
    if (window.ResizeObserver) {
      var queued = 0;
      new ResizeObserver(function () {
        if (queued) { return; }
        queued = window.requestAnimationFrame(function () { queued = 0; layout(); if (!frame) { refresh(); } });
      }).observe(canvas);
    }
    if (window.IntersectionObserver) {
      new IntersectionObserver(function (entries) {
        onscreen = entries[entries.length - 1].isIntersecting;
        if (onscreen) { start(); } else { stop(); }
      }).observe(canvas);
    }
    if (document.fonts && document.fonts.addEventListener) {
      document.fonts.addEventListener("loadingdone", function () { if (!frame) { refresh(); } });
    }
  }

  /* Canvas text does not wait for a web font, so the fonts it draws in are loaded first; a
     still frame drawn in a fallback face would be a different picture from one drawn a moment
     later, and the screenshots would catch whichever came first. */
  function ready() {
    readTheme();
    if (!document.fonts || !document.fonts.load) { return Promise.resolve(); }
    return Promise.all([
      document.fonts.load(font(600, 11.5, F.code)),
      document.fonts.load(font(700, 9, F.code)),
      document.fonts.load(font(700, 15, F.serif)),
      document.fonts.load(font("italic 400", 13, F.serif))
    ]).catch(function () {});
  }

  ready().then(begin);

  /* For the browser tests: whether the loop is running, how many frames it has drawn, and how
     many times the still frame has been drawn. */
  window.PravahaLanding = {
    running: function () { return frame !== 0; },
    frames: function () { return frames; },
    stills: function () { return stills; }
  };
}());
