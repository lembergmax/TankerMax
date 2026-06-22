/* ───────────────────────────────────────────────────────────────────
   Chart-Modul — TradingView-Optik via lightweight-charts v5.2.0.
   Zeichnet den Preisverlauf (Kerze/Linie/Flaeche/Balken/Heikin),
   die Aktuellpreis-Linie und einen Crosshair-Tooltip.
   Im Vergleichsmodus mehrere Stationen als Linien.
   ─────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const LWC = window.LightweightCharts;
  const C = {
    bg: '#0b0f18', grid: '#161d2b', text: '#8ea4c0', border: '#2a3548',
    up: '#26a69a', down: '#ef5350', accent: '#4f90ff', accentSoft: '#58a6ff',
    crosshair: '#58a6ff66', forecast: '#f5a524', forecastBand: 'rgba(245,165,36,.5)',
  };
  const MIN = 60, HOUR = 3600, DAY = 86400;
  // Sichtfenster-Konstanten (benannt statt Magic Numbers):
  const MIN_WINDOW_S = 2 * HOUR;             // kleinstes Sichtfenster (für sehr feine Zeitrahmen wie 5m)
  const FUTURE_PAD_S = 30 * MIN;             // Rand rechts des letzten Punktes
  // Geladene Historientiefe (muss zu StationQueryService.HISTORY_DAYS passen): das Sichtfenster
  // kann nie weiter zurueckreichen, als Daten vorliegen.
  const HISTORY_MAX_S = 14 * DAY;
  // Angestrebte Zahl sichtbarer Kerzen, aus der sich das anfängliche Sichtfenster ableitet.
  const TARGET_BARS = 48;
  // Anfaengliches Sichtfenster PROPORTIONAL zum gewählten Zeitrahmen (Aggregations-Bucket): jeder
  // Zeitrahmen zeigt rund TARGET_BARS Kerzen und damit einen sichtbar anderen Ausschnitt – feine
  // Rahmen (5m/15m/1h/4h) zoomen auf die jüngste Vergangenheit (4 h … 8 T), grobe weiten bis zur
  // geladenen Historientiefe (14 T). So unterscheiden sich die Zeitrahmen statt gleich auszusehen.
  function windowFor(tf) {
    return Math.min(HISTORY_MAX_S, Math.max(MIN_WINDOW_S, tf * TARGET_BARS));
  }

  // Greift verzoegert auf window.TK.esc zu: shared.js laedt nach chart.js, daher
  // existiert window.TK erst zur Laufzeit (Tooltip wird erst nach dem Laden gebaut).
  function esc(s) { return window.TK && window.TK.esc ? window.TK.esc(s) : String(s == null ? '' : s); }

  function fmtPrice(p) { return p.toFixed(3).replace('.', ',') + ' €'; }
  // t sind Chart-Sekunden: die lokale Wanduhrzeit ist bereits als UTC kodiert (siehe ChartTime).
  // Daher MUSS hier in UTC formatiert werden – sonst rechnet der Browser den Zonen-Versatz ein
  // zweites Mal ein und die Uhrzeiten erscheinen um 1–2 Stunden verschoben (passt sonst auch nicht
  // zur Zeitachse, die lightweight-charts ebenfalls in UTC beschriftet).
  function fmtTimeLabel(t) {
    const d = new Date(t * 1000);
    return d.toLocaleDateString('de-DE', { timeZone: 'UTC', weekday: 'short', day: '2-digit', month: '2-digit' }) +
      ' · ' + d.toLocaleTimeString('de-DE', { timeZone: 'UTC', hour: '2-digit', minute: '2-digit' });
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
  // Eine Heikin-Ashi-Kerze aus dem rohen OHLC-Bucket und der HA-Kerze des Vorgängers ableiten
  // (identisch zu toHeikin, aber inkrementell: erlaubt Live-Updates ohne die ganze Reihe neu zu rechnen).
  function heikinFrom(raw, prev) {
    const haC = (raw.open + raw.high + raw.low + raw.close) / 4;
    const haO = (prev.open + prev.close) / 2;
    return { time: raw.time, open: haO, high: Math.max(raw.high, haO, haC), low: Math.min(raw.low, haO, haC), close: haC };
  }
  // Anfangswert des HA-Vorgängers für die erste Kerze (entspricht der Initialisierung in toHeikin).
  function heikinSeed(raw) {
    return { open: raw.open, close: (raw.open + raw.high + raw.low + raw.close) / 4 };
  }
  // Live-Aggregationszustand nach einem Voll-Render festhalten, damit update() die nächste(n)
  // Beobachtung(en) in die laufende Kerze/Linie einpflegen kann, ohne den Chart neu aufzubauen
  // (Zoom/Pan bleiben erhalten – lightweight-charts hält die Sichtweite bei series.update()).
  function buildLive(history, opts) {
    const tf = opts.tf, type = opts.type;
    if (type === 'line' || type === 'area') {
      let lastTime = null;
      if (history.length) lastTime = (tf === MIN) ? history[history.length - 1].time : bucket(history[history.length - 1].time, tf);
      return { mode: 'single', tf, type, lastTime };
    }
    const raw = aggregateOHLC(history, tf);
    const rawLast = raw.length ? { ...raw[raw.length - 1] } : null;
    let haPrev = null, haCur = null;
    if (type === 'heikin' && raw.length) {
      const ha = toHeikin(raw);
      const last = ha[ha.length - 1];
      haCur = { open: last.open, close: last.close };
      haPrev = ha.length >= 2 ? { open: ha[ha.length - 2].open, close: ha[ha.length - 2].close } : heikinSeed(raw[0]);
    }
    return { mode: 'single', tf, type, rawLast, haPrev, haCur };
  }

  // ── Modulzustand ─────────────────────────────────────────────────
  let chart, mainSeries, currentPriceLine;
  let el, tooltipEl, container, ro;
  let compareSeries = [];
  // Vorhersage-Reihen (Prognosekurve, Unsicherheitsband, Tagestief-Linie) und der späteste
  // Prognosezeitpunkt, bis zu dem das Sichtfenster nach rechts geweitet wird.
  let forecastSeries = null, forecastLowSeries = null, forecastHighSeries = null;
  let forecastLowLine = null, forecastMaxTime = 0;
  // Aggregationszustand für inkrementelle Live-Updates (von render/renderCompare gesetzt).
  let live = null;

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
        timeFormatter: t => new Date(t * 1000).toLocaleTimeString('de-DE', { timeZone: 'UTC', hour: '2-digit', minute: '2-digit' }),
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
    if (mainSeries) { try { chart.removeSeries(mainSeries); } catch (e) {} }
    mainSeries = currentPriceLine = null;
    compareSeries.forEach(o => { try { chart.removeSeries(o.s); } catch (e) {} });
    compareSeries = [];
    [forecastSeries, forecastLowSeries, forecastHighSeries].forEach(s => { if (s) { try { chart.removeSeries(s); } catch (e) {} } });
    forecastSeries = forecastLowSeries = forecastHighSeries = forecastLowLine = null;
    forecastMaxTime = 0;
    live = null;
  }

  // ── Vorhersage als zusätzliche Reihen über den Ist-Verlauf legen ──
  // fc (Vorhersage): { points:[{time,value}], lower:[…], upper:[…], tip:{ low, … } }.
  // Die Reihen beginnen am letzten Ist-Punkt („jetzt"), damit die Prognose nahtlos anschließt.
  function addForecast(history, fc) {
    if (!fc || !fc.points || !fc.points.length) return;
    const last = history.length ? history[history.length - 1] : null;
    const join = (arr) => last ? [{ time: last.time, value: last.value }].concat(arr.filter(p => p.time > last.time)) : arr.slice();
    if (fc.lower && fc.lower.length && fc.upper && fc.upper.length) {
      const bandOpts = { color: C.forecastBand, lineWidth: 1, lineStyle: LWC.LineStyle.Dotted, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, pointMarkersVisible: false };
      forecastLowSeries = chart.addSeries(LWC.LineSeries, bandOpts);
      forecastHighSeries = chart.addSeries(LWC.LineSeries, bandOpts);
      forecastLowSeries.setData(join(fc.lower));
      forecastHighSeries.setData(join(fc.upper));
    }
    forecastSeries = chart.addSeries(LWC.LineSeries, { color: C.forecast, lineWidth: 2, lineStyle: LWC.LineStyle.Dashed, priceLineVisible: false, lastValueVisible: true, crosshairMarkerVisible: true, crosshairMarkerRadius: 3, pointMarkersVisible: false });
    forecastSeries.setData(join(fc.points));
    forecastMaxTime = fc.points[fc.points.length - 1].time;
    if (fc.tip && fc.tip.low != null) {
      try { forecastLowLine = forecastSeries.createPriceLine({ price: fc.tip.low, color: C.forecast, lineWidth: 1, lineStyle: LWC.LineStyle.Dotted, axisLabelVisible: true, title: 'Prog.-Tief' }); } catch (e) {}
    }
  }

  // ── Vergleichsmodus: mehrere Stationen als Linien ueberlagern ────
  // Eingabe je Station: { name, color, history }
  function renderCompare(items, opts) {
    clearAll();
    items.forEach(it => {
      const histLine = aggregateForType(it.history, opts.tf, 'line');
      const ls = chart.addSeries(LWC.LineSeries, { color: it.color, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, crosshairMarkerVisible: true, crosshairMarkerRadius: 3, pointMarkersVisible: false });
      ls.setData(histLine);
      compareSeries.push({ s: ls, id: it.id, name: it.name, color: it.color, lastTime: histLine.length ? histLine[histLine.length - 1].time : null });
    });
    live = { mode: 'compare', tf: opts.tf, type: 'line' };
    const frame = () => {
      if (!chart) return;
      try {
        chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        const NOW = window.TKCLOCK.now, ts = chart.timeScale();
        ts.fitContent();
        ts.setVisibleRange({ from: NOW - windowFor(opts.tf), to: (forecastMaxTime ? forecastMaxTime + FUTURE_PAD_S : NOW + FUTURE_PAD_S) });
      } catch (e) { try { if (chart) chart.timeScale().fitContent(); } catch (e2) {} }
    };
    requestAnimationFrame(() => requestAnimationFrame(frame));
  }

  // opts (Optionen): { type, tf }
  function render(history, opts) {
    clearAll();
    const bars = aggregateForType(history, opts.tf, opts.type);
    mainSeries = addMain(opts.type);
    mainSeries.setData(bars);

    const cur = (history[history.length - 1] || {}).value;
    // Aktuellpreis-Linie
    if (cur != null) currentPriceLine = mainSeries.createPriceLine({ price: cur, color: C.accent, lineWidth: 1, lineStyle: LWC.LineStyle.Dashed, axisLabelVisible: true, title: 'jetzt' });
    live = buildLive(history, opts);
    // Vorhersage einzeichnen (sofern aktiviert und vorhanden); weitet zugleich das Sichtfenster.
    addForecast(history, opts.forecast);

    // Groesse setzen und den interessanten Ausschnitt rahmen, NACHDEM das Layout geflossen ist
    // (der Chart entsteht hinter der verborgenen Detailansicht und startet daher mit 0×0).
    const frame = () => {
      if (!chart) return;
      try {
        chart.applyOptions({ width: container.clientWidth, height: container.clientHeight });
        const NOW = window.TKCLOCK.now, ts = chart.timeScale();
        ts.fitContent();
        ts.setVisibleRange({ from: NOW - windowFor(opts.tf), to: (forecastMaxTime ? forecastMaxTime + FUTURE_PAD_S : NOW + FUTURE_PAD_S) });
      } catch (e) { try { if (chart) chart.timeScale().fitContent(); } catch (e2) {} }
    };
    requestAnimationFrame(() => requestAnimationFrame(frame));
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
    if (forecastSeries) {
      const fd = param.seriesData.get(forecastSeries);
      if (fd && fd.value != null) html += row(C.forecast, 'Prognose', fmtPrice(fd.value));
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

  // ── Live-Update: einzelne Beobachtung einpflegen ─────────────────
  // Aktualisiert die laufende Kerze/Linie über series.update() statt eines Neuaufbaus, sodass die
  // aktuelle Sichtweite (Zoom/Pan) erhalten bleibt. point: { time, value } (Chart-Sekunden, €/L).
  function update(point) {
    if (!chart || !mainSeries || !live || live.mode !== 'single' || !point) return;
    const t = point.time, v = point.value;
    if (v == null) return;
    const tf = live.tf;

    if (live.type === 'line' || live.type === 'area') {
      const time = (tf === MIN) ? t : bucket(t, tf);
      if (live.lastTime != null && time < live.lastTime) return;
      try { mainSeries.update({ time, value: v }); } catch (e) { return; }
      live.lastTime = time;
      updateNowLine(v);
      return;
    }

    const bt = bucket(t, tf);
    let raw = live.rawLast;
    if (!raw) {
      raw = { time: bt, open: v, high: v, low: v, close: v };
      live.rawLast = raw;
    } else if (bt > raw.time) {
      if (live.type === 'heikin') live.haPrev = live.haCur || live.haPrev;
      const prevClose = raw.close;
      raw = { time: bt, open: prevClose, high: Math.max(prevClose, v), low: Math.min(prevClose, v), close: v };
      live.rawLast = raw;
    } else if (bt === raw.time) {
      if (v > raw.high) raw.high = v;
      if (v < raw.low) raw.low = v;
      raw.close = v;
    } else {
      return; // ältere Beobachtung als die laufende Kerze – ignorieren
    }

    let bar = raw;
    if (live.type === 'heikin') {
      bar = heikinFrom(raw, live.haPrev || heikinSeed(raw));
      live.haCur = { open: bar.open, close: bar.close };
    }
    try { mainSeries.update(bar); } catch (e) { return; }
    updateNowLine(v);
  }

  // Aktuellpreis-Linie ("jetzt") auf den neuen Wert nachziehen.
  function updateNowLine(v) {
    if (currentPriceLine) { try { currentPriceLine.applyOptions({ price: v }); } catch (e) {} }
  }

  // ── Live-Update im Vergleichsmodus ───────────────────────────────
  // values: [{ id, value }] – setzt je Station den jüngsten Linienpunkt auf den aktuellen Preis,
  // verortet im Bucket der aktuellen Zeit. Erhält ebenfalls die Sichtweite.
  function updateCompare(values) {
    if (!chart || !live || live.mode !== 'compare' || !values || !values.length) return;
    const tf = live.tf;
    const now = (window.TKCLOCK && window.TKCLOCK.now) || Math.floor(Date.now() / 1000);
    const time = bucket(now, tf);
    const byId = new Map(values.map(x => [x.id, x.value]));
    compareSeries.forEach(o => {
      const v = byId.get(o.id);
      if (v == null) return;
      if (o.lastTime != null && time < o.lastTime) return;
      try { o.s.update({ time, value: v }); o.lastTime = time; } catch (e) {}
    });
  }

  window.ChartView = { init, render, renderCompare, update, updateCompare, fmtPrice };
})();
