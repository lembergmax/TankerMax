/* ───────────────────────────────────────────────────────────────────
   Chart-Modul — TradingView-Optik via lightweight-charts v5.2.0.
   Zeichnet den Preisverlauf (Kerze/Linie/Flaeche/Balken/Heikin),
   die Aktuellpreis-Linie, einen Crosshair-Tooltip und Indikator-Overlays
   (SMA/EMA/Bollinger). Im Vergleichsmodus mehrere Stationen als Linien.
   ─────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const LWC = window.LightweightCharts;
  const C = {
    bg: '#0b0f18', grid: '#161d2b', text: '#8ea4c0', border: '#2a3548',
    up: '#26a69a', down: '#ef5350', accent: '#4f90ff', accentSoft: '#58a6ff',
    crosshair: '#58a6ff66',
  };
  const MIN = 60, HOUR = 3600, DAY = 86400;
  // Indikator- und Sichtfenster-Konstanten (benannt statt Magic Numbers):
  const INDICATOR_PERIOD = 20;               // Fensterbreite fuer SMA/EMA/Bollinger
  const BOLLINGER_MULT = 2;                  // Faktor der Standardabweichung der Bollinger-Baender
  const HISTORY_WINDOW_S = 2 * DAY;          // sichtbarer Verlauf links von „jetzt"
  const FUTURE_PAD_S = 30 * MIN;             // Rand rechts des letzten Punktes

  // Greift verzoegert auf window.TK.esc zu: shared.js laedt nach chart.js, daher
  // existiert window.TK erst zur Laufzeit (Tooltip wird erst nach dem Laden gebaut).
  function esc(s) { return window.TK && window.TK.esc ? window.TK.esc(s) : String(s == null ? '' : s); }

  function fmtPrice(p) { return p.toFixed(3).replace('.', ',') + ' €'; }
  function fmtTimeLabel(t) {
    const d = new Date(t * 1000);
    return d.toLocaleDateString('de-DE', { weekday: 'short', day: '2-digit', month: '2-digit' }) +
      ' · ' + d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  }

  // ── Aggregation ──────────────────────────────────────────────────
  function bucket(t, tf) { return Math.floor(t / tf) * tf; }
  function aggregateOHLC(data, tf) {
    if (!data.length) return [];
    const map = new Map();
    for (const { time, value } of data) {
      const t = bucket(time, tf);
      const c = map.get(t);
      if (!c) map.set(t, { time: t, open: value, high: value, low: value, close: value });
      else { if (value > c.high) c.high = value; if (value < c.low) c.low = value; c.close = value; }
    }
    const bars = [...map.values()].sort((a, b) => a.time - b.time);
    linkOpensToPriorClose(bars);
    return bars;
  }
  // Tankpreise sind Treppenfunktionen: pro Zeitfenster liegt oft nur eine
  // Beobachtung, sodass open=close waere (flache Doji-"Punkte"). Damit eine
  // Kerze die tatsaechliche Bewegung zeigt (gruen/rot statt einzelner Strich),
  // eroeffnet jede Kerze auf dem Schluss der vorherigen und Hoch/Tief umfassen
  // diesen Eroeffnungswert.
  function linkOpensToPriorClose(bars) {
    for (let i = 1; i < bars.length; i++) {
      const prevClose = bars[i - 1].close;
      bars[i].open = prevClose;
      if (prevClose > bars[i].high) bars[i].high = prevClose;
      if (prevClose < bars[i].low) bars[i].low = prevClose;
    }
  }
  function toHeikin(ohlc) {
    if (!ohlc.length) return [];
    const out = []; let pO = ohlc[0].open, pC = (ohlc[0].open + ohlc[0].high + ohlc[0].low + ohlc[0].close) / 4;
    for (const c of ohlc) {
      const haC = (c.open + c.high + c.low + c.close) / 4;
      const haO = (pO + pC) / 2;
      out.push({ time: c.time, open: haO, high: Math.max(c.high, haO, haC), low: Math.min(c.low, haO, haC), close: haC });
      pO = haO; pC = haC;
    }
    return out;
  }
  function aggregateForType(data, tf, type) {
    if (type === 'line' || type === 'area') {
      if (tf === MIN) return data.map(d => ({ time: d.time, value: d.value }));
      return aggregateOHLC(data, tf).map(c => ({ time: c.time, value: c.close }));
    }
    const o = aggregateOHLC(data, tf);
    return type === 'heikin' ? toHeikin(o) : o;
  }

  // ── Indikator-Berechnung ─────────────────────────────────────────
  function closes(bars) { return bars.map(b => (b.close != null ? b.close : b.value)); }
  function SMA(bars, p) { const s = closes(bars), o = []; for (let i = p - 1; i < s.length; i++) { let sum = 0; for (let j = i - p + 1; j <= i; j++) sum += s[j]; o.push({ time: bars[i].time, value: sum / p }); } return o; }
  function EMA(bars, p) { const s = closes(bars), o = [], k = 2 / (p + 1); let e; for (let i = 0; i < s.length; i++) { e = i === 0 ? s[0] : s[i] * k + e * (1 - k); if (i >= p - 1) o.push({ time: bars[i].time, value: e }); } return o; }
  function BB(bars, p, mult) { const s = closes(bars), up = [], mid = [], lo = []; for (let i = p - 1; i < s.length; i++) { let sum = 0; for (let j = i - p + 1; j <= i; j++) sum += s[j]; const m = sum / p; let v = 0; for (let j = i - p + 1; j <= i; j++) v += (s[j] - m) ** 2; const sd = Math.sqrt(v / p); mid.push({ time: bars[i].time, value: m }); up.push({ time: bars[i].time, value: m + mult * sd }); lo.push({ time: bars[i].time, value: m - mult * sd }); } return { up, mid, lo }; }

  // ── Modulzustand ─────────────────────────────────────────────────
  let chart, mainSeries, currentPriceLine;
  let indSeries = {};   // Overlays auf der Hauptflaeche (SMA/EMA/Bollinger)
  let el, tooltipEl, container, ro;
  let lastBars = [];
  let compareSeries = [];

  function init(opts) {
    if (chart) { try { chart.remove(); } catch (e) {} chart = null; }
    if (ro) { try { ro.disconnect(); } catch (e) {} ro = null; }
    el = opts.chartEl; container = opts.container; tooltipEl = opts.tooltipEl;
    if (opts.theme) Object.assign(C, opts.theme);
    const fontFamily = opts.fontFamily || "'Inter', system-ui, sans-serif";
    chart = LWC.createChart(el, {
      layout: { background: { type: 'solid', color: C.bg }, textColor: C.text, fontFamily: fontFamily, attributionLogo: false },
      grid: { vertLines: { color: C.grid }, horzLines: { color: C.grid } },
      crosshair: { mode: LWC.CrosshairMode.Normal, vertLine: { color: C.crosshair, labelBackgroundColor: C.crosshairLabel || '#1f3146', width: 1 }, horzLine: { color: C.crosshair, labelBackgroundColor: C.crosshairLabel || '#1f3146' } },
      rightPriceScale: { borderColor: C.border, scaleMargins: { top: 0.12, bottom: 0.12 } },
      timeScale: { borderColor: C.border, timeVisible: true, secondsVisible: false, rightOffset: 6 },
      localization: {
        priceFormatter: fmtPrice,
        timeFormatter: t => new Date(t * 1000).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' }),
        locale: 'de-DE',
      },
      handleScroll: true, handleScale: true,
    });

    ro = new ResizeObserver(() => {
      if (!chart) return;
      chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
    });
    ro.observe(container);
    chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });

    chart.subscribeCrosshairMove(onCrosshair);
  }

  function addMain(type) {
    const o = { priceLineVisible: false, lastValueVisible: true };
    // Bewusst invertiert: steigender Preis = rot (schlecht fuer Tankende),
    // fallender Preis = gruen. Daher Up-Farbe = C.down (rot), Down-Farbe = C.up (gruen).
    if (type === 'candle' || type === 'heikin')
      return chart.addSeries(LWC.CandlestickSeries, { ...o, upColor: C.down, downColor: C.up, borderUpColor: C.down, borderDownColor: C.up, wickUpColor: C.down, wickDownColor: C.up });
    if (type === 'bar')
      return chart.addSeries(LWC.BarSeries, { ...o, upColor: C.down, downColor: C.up });
    if (type === 'area')
      return chart.addSeries(LWC.AreaSeries, { ...o, lineColor: C.accentSoft, topColor: 'rgba(88,166,255,.22)', bottomColor: 'rgba(88,166,255,0)', lineWidth: 2 });
    return chart.addSeries(LWC.LineSeries, { ...o, color: C.accentSoft, lineWidth: 2 });
  }

  function clearAll() {
    Object.values(indSeries).forEach(s => { try { chart.removeSeries(s); } catch (e) {} });
    indSeries = {};
    if (mainSeries) { try { chart.removeSeries(mainSeries); } catch (e) {} }
    mainSeries = currentPriceLine = null;
    compareSeries.forEach(o => { try { chart.removeSeries(o.s); } catch (e) {} });
    compareSeries = [];
  }

  // ── Vergleichsmodus: mehrere Stationen als Linien ueberlagern ────
  // Eingabe je Station: { name, color, history }
  function renderCompare(items, opts) {
    clearAll();
    items.forEach(it => {
      const histLine = aggregateForType(it.history, opts.tf, 'line');
      const ls = chart.addSeries(LWC.LineSeries, { color: it.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, crosshairMarkerVisible: true, crosshairMarkerRadius: 3, pointMarkersVisible: false });
      ls.setData(histLine);
      compareSeries.push({ s: ls, name: it.name, color: it.color });
    });
    const frame = () => {
      if (!chart) return;
      try {
        chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        const NOW = window.TKCLOCK.now, ts = chart.timeScale();
        ts.fitContent();
        ts.setVisibleRange({ from: NOW - HISTORY_WINDOW_S, to: NOW + FUTURE_PAD_S });
      } catch (e) { try { if (chart) chart.timeScale().fitContent(); } catch (e2) {} }
    };
    requestAnimationFrame(() => requestAnimationFrame(frame));
  }

  // opts (Optionen): { type, tf, indicators:Set }
  function render(history, opts) {
    clearAll();
    const bars = aggregateForType(history, opts.tf, opts.type);
    lastBars = bars;
    mainSeries = addMain(opts.type);
    mainSeries.setData(bars);

    const cur = (history[history.length - 1] || {}).value;
    // Aktuellpreis-Linie
    if (cur != null) currentPriceLine = mainSeries.createPriceLine({ price: cur, color: C.accent, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed, axisLabelVisible: true, title: 'jetzt' });

    applyIndicators(opts.indicators || new Set());

    // Groesse setzen und den interessanten Ausschnitt rahmen, NACHDEM das Layout geflossen ist
    // (der Chart entsteht hinter der verborgenen Detailansicht und startet daher mit 0×0).
    const frame = () => {
      if (!chart) return;
      try {
        chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        const NOW = window.TKCLOCK.now, ts = chart.timeScale();
        ts.fitContent();
        ts.setVisibleRange({ from: NOW - HISTORY_WINDOW_S, to: NOW + FUTURE_PAD_S });
      } catch (e) { try { if (chart) chart.timeScale().fitContent(); } catch (e2) {} }
    };
    requestAnimationFrame(() => requestAnimationFrame(frame));
  }

  function applyIndicators(set) {
    // bestehende Overlays entfernen (Bollinger-Baender bestehen aus mehreren Serien)
    Object.entries(indSeries).forEach(([k, s]) => { if (!set.has(k) || k === 'bb') { try { chart.removeSeries(s); } catch (e) {} delete indSeries[k]; } });
    if (indSeries.bbU) { ['bbU', 'bbL', 'bbM'].forEach(k => { try { chart.removeSeries(indSeries[k]); } catch (e) {} delete indSeries[k]; }); }

    const bars = lastBars;
    if (set.has('sma') && !indSeries.sma) { indSeries.sma = chart.addSeries(LWC.LineSeries, { color: '#fbbf24', lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false }); indSeries.sma.setData(SMA(bars, INDICATOR_PERIOD)); }
    if (set.has('ema') && !indSeries.ema) { indSeries.ema = chart.addSeries(LWC.LineSeries, { color: '#34d399', lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false }); indSeries.ema.setData(EMA(bars, INDICATOR_PERIOD)); }
    if (set.has('bb')) { const b = BB(bars, INDICATOR_PERIOD, BOLLINGER_MULT); const mk = c => chart.addSeries(LWC.LineSeries, { color: c, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false }); indSeries.bbU = mk('rgba(79,144,255,.55)'); indSeries.bbU.setData(b.up); indSeries.bbL = mk('rgba(79,144,255,.55)'); indSeries.bbL.setData(b.lo); indSeries.bbM = mk('rgba(142,164,192,.4)'); indSeries.bbM.setData(b.mid); }
  }

  // ── Crosshair-Tooltip ────────────────────────────────────────────
  function onCrosshair(param) {
    if (!param.point || !param.time || param.point.x < 0 || param.point.y < 0) { tooltipEl.style.display = 'none'; return; }
    const t = param.time;
    if (compareSeries.length) {
      let h = '<div class="tt-time">' + fmtTimeLabel(t) + '</div>';
      compareSeries.forEach(o => {
        const d = param.seriesData.get(o.s);
        if (d && d.value != null) h += row(o.color, esc(o.name), fmtPrice(d.value));
      });
      tooltipEl.innerHTML = h; tooltipEl.style.display = 'block';
      const w0 = tooltipEl.offsetWidth, h0 = tooltipEl.offsetHeight;
      let x0 = param.point.x + 16, y0 = param.point.y + 16;
      if (x0 + w0 > container.clientWidth) x0 = param.point.x - w0 - 16;
      if (y0 + h0 > container.clientHeight) y0 = param.point.y - h0 - 16;
      tooltipEl.style.left = Math.max(4, x0) + 'px'; tooltipEl.style.top = Math.max(4, y0) + 'px';
      return;
    }
    let html = '<div class="tt-time">' + fmtTimeLabel(t) + '</div>';
    const md = mainSeries ? param.seriesData.get(mainSeries) : null;
    if (md) {
      const v = md.close != null ? md.close : md.value;
      html += row(C.accentSoft, 'Ist-Preis', fmtPrice(v));
    }
    tooltipEl.innerHTML = html;
    tooltipEl.style.display = 'block';
    const w = tooltipEl.offsetWidth, h = tooltipEl.offsetHeight;
    let x = param.point.x + 16, y = param.point.y + 16;
    if (x + w > container.clientWidth) x = param.point.x - w - 16;
    if (y + h > container.clientHeight) y = param.point.y - h - 16;
    tooltipEl.style.left = Math.max(4, x) + 'px';
    tooltipEl.style.top = Math.max(4, y) + 'px';
  }
  function row(color, key, val) {
    return '<div class="tt-row"><span class="tt-key"><span class="tt-dot" style="background:' + color + '"></span>' + key + '</span><span class="tt-val">' + val + '</span></div>';
  }

  window.ChartView = { init, render, renderCompare, fmtPrice };
})();
