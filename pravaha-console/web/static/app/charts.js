/*
 * Pravaha console -- ECharts, dressed in the active theme.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * Proprietary and confidential. See LICENSE at the repository root.
 *
 * Canvas, because it survives high-frequency updates (design 23.3). Every colour is read
 * from the design tokens at draw time and re-read when the theme changes, so a chart is
 * never the one light rectangle on a dark page. Series colours come from the validated
 * categorical order (--series-1..8), assigned by identity and never cycled; text wears
 * text tokens, never a series colour; the grid is recessive; there is never a second
 * y-axis. A gap in the data draws as a gap.
 */
import { token, onThemeChange, t } from "pravaha/lib.js";
import { loadGlobalScript } from "pravaha/plan-graph.js";

/* After the page has loaded and gone idle. The verdict, the tables and the counters are
   server-rendered and live without a chart; fetching the chart library before the load event
   put 370 kB (gzipped) between an operator and a working dashboard, over the 250 kB budget of
   design 23.15 on its own. */
function afterLoad() {
  return new Promise((resolve) => {
    const idle = () => (window.requestIdleCallback
      ? window.requestIdleCallback(() => resolve(), { timeout: 400 }) : setTimeout(resolve, 0));
    if (document.readyState === "complete") idle();
    else window.addEventListener("load", idle, { once: true });
  });
}

/* The "common" build (line, bar, scatter, pie; grid, legend, tooltip, dataZoom, graphic):
   everything these charts use, at 234 kB gzipped against the full build's 370. */
export async function loadECharts() {
  await afterLoad();
  return loadGlobalScript("/static/vendor/echarts/echarts.common.min.js", "echarts");
}

export function seriesColor(index) {
  return token(`--series-${(index % 8) + 1}`, "#2a78d6");
}

/** Base options for a time-series line chart: one y-axis, crosshair tooltip, no animation. */
export function timeSeriesBase({ yName = "", yFormatter = null } = {}) {
  const ink = token("--ink", "#15181D");
  const muted = token("--muted", "#646B78");
  const hairline = token("--hairline", "#EBEEF3");
  const rule = token("--rule", "#D9DEE6");
  const raised = token("--raised", "#fff");
  const font = token("--sans", "sans-serif");
  return {
    animation: false,
    textStyle: { fontFamily: font, color: ink },
    grid: { left: 56, right: 16, top: 28, bottom: 36 },
    tooltip: {
      trigger: "axis", axisPointer: { type: "line", lineStyle: { color: muted } },
      backgroundColor: raised, borderColor: rule, textStyle: { color: ink, fontSize: 12 },
      valueFormatter: yFormatter || ((v) => (v === null || v === undefined ? t("charts.no_data") : Number(v).toLocaleString())),
    },
    legend: { top: 0, right: 0, textStyle: { color: muted, fontSize: 11 }, icon: "roundRect", itemWidth: 12 },
    xAxis: { type: "time", axisLine: { lineStyle: { color: rule } }, axisLabel: { color: muted, fontSize: 11 },
             splitLine: { show: false } },
    yAxis: { type: "value", name: yName, nameTextStyle: { color: muted, fontSize: 11, align: "left" },
             axisLabel: { color: muted, fontSize: 11, formatter: yFormatter || undefined },
             splitLine: { lineStyle: { color: hairline } } },
  };
}

export function lineSeries(name, data, index) {
  return {
    /* Markers only while there are too few points to draw a line, so a series that has just
       started is visible rather than an empty plot. */
    name, type: "line", data, showSymbol: data.filter((p) => p[1] !== null).length < 3, symbolSize: 8,
    connectNulls: false,
    lineStyle: { width: 2, color: seriesColor(index) }, itemStyle: { color: seriesColor(index) },
    emphasis: { focus: "series" },
  };
}

/** Creates a chart that follows the theme and the container's size. */
export async function themedChart(container, build) {
  const echarts = await loadECharts();
  let chart = echarts.init(container, null, { renderer: "canvas" });
  const redraw = () => chart.setOption(build(), { notMerge: true });
  redraw();
  const resize = new ResizeObserver(() => chart.resize());
  resize.observe(container);
  const stop = onThemeChange(() => { redraw(); });
  return {
    chart,
    redraw,
    dispose() { stop(); resize.disconnect(); chart.dispose(); chart = null; },
  };
}
