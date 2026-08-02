/* ───────────────────────────────────────────────────────────────────
   Engine der Oberflaeche: Zustand + Datenzugriff (async, ueber window.TKAPI)
   + Chart-Verdrahtung. Das Markup liefert das jeweilige Design (renderList /
   renderSelection). Daten werden read-only aus der Datenbank gelesen; pro
   Region/Kraftstoff einmal die Liste, je Auswahl die Historie.
   Requires: window.TKAPI, window.TKBRAND, window.ChartView.
   ─────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';
  const API = window.TKAPI;

  const eur = v => v.toFixed(3).replace('.', ',') + ' €';
  const eur2 = v => v.toFixed(2).replace('.', ',') + ' €';
  const ct1 = v => v.toFixed(1).replace('.', ',');
  // Chart-Sekunden sind lokale Wanduhrzeit als UTC kodiert; daher in UTC formatieren, sonst
  // wuerde der Browser den Zonen-Versatz ein zweites Mal aufschlagen.
  const hhmm = t => new Date(t * 1000).toLocaleTimeString('de-DE', { timeZone: 'UTC', hour: '2-digit', minute: '2-digit' });
  // HTML-Escape fuer in Markup interpolierte API-/DB-Strings (& < > " ').
  // Verhindert, dass Werte wie Tankstellennamen das per innerHTML gebaute Markup
  // zerlegen (XSS bzw. Layout-Bruch). Reihenfolge: & zuerst.
  const esc = s => s == null ? '' : String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  // Client-Fallback fuer die Chart-"Jetzt"-Zeit, falls /meta nicht erreichbar ist:
  // lokale Wanduhr als UTC-Epoch kodiert (entspricht ChartTime.now() im Backend).
  const clientChartNow = () => Math.floor(Date.now() / 1000) - new Date().getTimezoneOffset() * 60;
  const FUEL = { e5: 'Super E5', e10: 'Super E10', diesel: 'Diesel' };
  // Rückfall-Takt der Preisaktualisierung (ms): greift nur, wenn die Echtzeit-Verbindung (SSE) NICHT
  // steht (kein EventSource, Verbindungsabbruch). Im Normalbetrieb schiebt der Server die Updates
  // über window.TKLIVE/SSE; dann läuft dieser Poll leer und erzeugt keine zusätzliche Last.
  const POLL_INTERVAL_MS = 60000;
  // Persistenz nutzerseitiger Auswahl in localStorage (Praefix wie beim Theme: tk-pro-*).
  const LS_PREFIX = 'tk-pro-';
  function lsGet(key) { try { return localStorage.getItem(LS_PREFIX + key); } catch (e) { return null; } }
  function lsSet(key, value) { try { localStorage.setItem(LS_PREFIX + key, value); } catch (e) {} }

  async function makeDashboard(adapter) {
    const root = adapter.root || document;
    const $ = s => root.querySelector(s);
    const $$ = s => Array.from(root.querySelectorAll(s));

    let meta;
    try { meta = await API.getMeta(); }
    catch (e) { meta = { now: clientChartNow() }; }
    // Chart-„jetzt"-Anker (Chart-Sekunden). Laeuft mit der Echtzeit weiter (Anker: Server-now
    // bei Start plus verstrichene Zeit) statt eingefroren zu bleiben; der Chart liest dies pro Frame.
    const bootRealMs = Date.now(), bootNow = meta.now;
    window.TKCLOCK = { get now() { return Math.round(bootNow + (Date.now() - bootRealMs) / 1000); } };

    const FUELS = ['e5', 'e10', 'diesel'], SORTS = ['price', 'dist'];
    let savedCompare = null; try { savedCompare = JSON.parse(lsGet('compare') || 'null'); } catch (e) {}
    // Zuvor gewaehlte Einstellungen wiederherstellen (validiert; ungueltige Werte fallen auf Default).
    const saved = {
      fuel: FUELS.includes(lsGet('fuel')) ? lsGet('fuel') : null,
      sort: SORTS.includes(lsGet('sort')) ? lsGet('sort') : null,
      region: lsGet('region'),
      hideClosed: lsGet('hideClosed') === 'true',
      compareOn: !!(savedCompare && savedCompare.on),
      compareIds: (savedCompare && Array.isArray(savedCompare.ids)) ? savedCompare.ids.slice(0, 4) : [],
      forecast: lsGet('forecast') === 'true',
    };

    const state = {
      fuel: saved.fuel || adapter.fuel || 'diesel', sort: saved.sort || 'dist',
      region: null, selectedId: null, chartType: 'candle', tf: 3600,
      hideClosed: saved.hideClosed,
      compare: saved.compareOn, compareIds: saved.compareIds, loadError: false,
      forecast: saved.forecast,
    };

    let regions = [], regionsFailed = false;
    try { regions = await API.getRegions(); }
    catch (e) { regionsFailed = true; }
    state.region = (saved.region && regions.some(r => r.id === saved.region)) ? saved.region : (regions.length ? regions[0].id : null);

    let stationCache = [];
    const seriesCache = new Map();
    // Zwischenspeicher der KI-Vorhersagen je Tankstelle (wird wie seriesCache bei Region-/
    // Kraftstoffwechsel geleert, da die Vorhersage vom Kraftstoff abhängt).
    const forecastCache = new Map();
    // Generationszaehler gegen Races: jede Nutzeraktion (Region/Kraftstoff/Auswahl/Vergleich/Poll)
    // erhoeht ihn; eine veraltete asynchrone Antwort erkennt das und ueberschreibt nichts mehr.
    let nav = 0;
    // Jüngster im Chart dargestellter Beobachtungszeitpunkt (Chart-Sekunden) der aktuellen Auswahl.
    // Live-Updates speisen nur echte neue Punkte (time > diesem Wert) ein; zeitbasiert statt indexbasiert,
    // damit das nachrückende 14-Tage-Fenster (vorne fallen Punkte weg) nicht zu Fehlzuordnungen führt.
    let chartRawMaxTime = 0;
    // Kennung der Station, deren Verlauf aktuell im Chart steht – damit ein Live-Update bei zwischen-
    // zeitlich gewechselter Auswahl voll neu zeichnet statt fälschlich Punkte einzuspeisen.
    let chartStationId = null;
    // Zustand der Echtzeit-Verbindung (Server-Sent Events).
    const liveConn = { es: null, connected: false, everConnected: false };

    const priceOf = s => s.priceNow;
    const shortName = s => (s.name ? s.name.replace(s.brand, '').trim() : s.name) || s.brand;
    const median = a => { if (!a.length) return 0; const x = a.slice().sort((p, q) => p - q); return x[Math.floor(x.length / 2)]; };
    function stations() { return stationCache; }
    function openList() { return stationCache.filter(s => s.isOpen); }
    function sorted() {
      const a = stationCache.slice();
      if (state.sort === 'price') a.sort((x, y) => (x.isOpen === y.isOpen) ? (priceOf(x) - priceOf(y)) : (x.isOpen ? -1 : 1));
      else if (state.sort === 'dist') a.sort((x, y) => x.dist - y.dist);
      return a;
    }
    function priceTier(s) {
      if (!s.isOpen) return 'closed';
      const ps = openList().map(priceOf).filter(v => v != null).sort((a, b) => a - b), p = priceOf(s);
      if (!ps.length) return 'mid';
      if (p <= ps[Math.floor(ps.length / 3)]) return 'cheap';
      if (p > median(ps)) return 'pricey';
      return 'mid';
    }

    const COMPARE_COLORS = ['#5b6cff', '#16b364', '#f0603f', '#a855f7'];
    function compareColor(id) { const i = state.compareIds.indexOf(id); return COMPARE_COLORS[(i < 0 ? 0 : i) % COMPARE_COLORS.length]; }

    const ctx = { state, eur, eur2, ct1, hhmm, FUEL, priceOf, priceTier, shortName, stations, openList, sorted, median, compareColor, regions };

    window.ChartView.init({ chartEl: adapter.chart.chartEl, container: adapter.chart.container, tooltipEl: adapter.chart.tooltipEl, theme: adapter.theme, fontFamily: adapter.font });

    async function loadStations(g) {
      if (adapter.renderListSkeleton) adapter.renderListSkeleton(ctx);
      if (!state.region) { stationCache = []; state.loadError = regionsFailed; seriesCache.clear(); return; }
      try {
        const list = await API.getStations(state.region, state.fuel, state.hideClosed);
        if (g != null && g !== nav) return;
        list.forEach(s => { s.brandStyle = window.TKBRAND.style(s.brand); });
        stationCache = list;
        state.loadError = false;
      } catch (e) {
        if (g != null && g !== nav) return;
        stationCache = [];
        state.loadError = true;
      }
      seriesCache.clear();
      forecastCache.clear();
    }
    // Einzelne Historie robust laden: ein Fehler darf die Detailansicht nicht
    // abbrechen; der Fehlschlag wird nicht gecacht, damit er spaeter erneut greift.
    async function ensureHistory(id) {
      if (seriesCache.has(id)) return seriesCache.get(id);
      let history;
      try { history = await API.getHistory(id, state.fuel); }
      catch (e) { return []; }
      seriesCache.set(id, history);
      return history;
    }
    // KI-Vorhersage robust laden: ein Fehler darf die Detailansicht nicht abbrechen; der Fehlschlag
    // wird nicht gecacht, damit er später erneut greift. Liefert null bei Fehler.
    async function ensureForecast(id) {
      if (forecastCache.has(id)) return forecastCache.get(id);
      let forecast;
      try { forecast = await API.getForecast(id, state.fuel); }
      catch (e) { return null; }
      forecastCache.set(id, forecast);
      return forecast;
    }
    function selected() { return stationCache.find(s => s.id === state.selectedId); }
    // Standardauswahl: die oberste Tankstelle der aktuell sortierten Liste.
    function pickTop() {
      return sorted()[0] || stationCache[0] || null;
    }

    // Fehler-/Leerzustand zentral: bei Ladefehler bzw. leerer Region uebernimmt die
    // View (Adapter) die Darstellung; normales Listen-/Detail-Rendern entfaellt dann.
    function renderBootState() {
      if (state.loadError) { if (adapter.renderError) adapter.renderError(ctx, reload); return true; }
      if (!stationCache.length) { if (adapter.renderEmpty) adapter.renderEmpty(ctx); return true; }
      return false;
    }
    function refreshList() { if (state.loadError || !stationCache.length) { renderBootState(); return; } adapter.renderList(sorted(), ctx); }
    async function refreshSelection(g) {
      const s = selected(); if (!s) return;
      if (!seriesCache.has(s.id) && adapter.renderSelectionSkeleton) adapter.renderSelectionSkeleton(s, ctx);
      const history = await ensureHistory(s.id);
      const forecast = state.forecast ? await ensureForecast(s.id) : null;
      if (g != null && g !== nav) return;
      try { window.ChartView.render(history, { type: state.chartType, tf: state.tf, forecast }); } catch (e) {}
      chartStationId = s.id;
      chartRawMaxTime = history.length ? history[history.length - 1].time : 0;
      adapter.renderSelection({ s, hist: history, cur: priceOf(s), forecast }, ctx);
    }
    // Aktualisiert nur die rechte Detailspalte aus dem Cache (kein Chart-Neuaufbau) – etwa fuer
    // das Auto-Polling der Preise. Die Vorhersage stammt aus dem Cache (kein erneuter Abruf).
    function refreshSelectionPanel() {
      const s = selected(); if (!s) return;
      const history = seriesCache.get(s.id); if (!history) return;
      const forecast = state.forecast ? forecastCache.get(s.id) : null;
      adapter.renderSelection({ s, hist: history, cur: priceOf(s), forecast }, ctx);
    }
    async function renderChart(g) {
      const s = selected(); if (!s) return;
      const history = await ensureHistory(s.id);
      const forecast = state.forecast ? await ensureForecast(s.id) : null;
      if (g != null && g !== nav) return;
      try { window.ChartView.render(history, { type: state.chartType, tf: state.tf, forecast }); } catch (e) {}
      chartStationId = s.id;
      chartRawMaxTime = history.length ? history[history.length - 1].time : 0;
    }
    async function renderCompareView(g) {
      const anyFresh = state.compareIds.some(id => !seriesCache.has(id));
      if (anyFresh && adapter.chartLoading) adapter.chartLoading(true);
      const items = await Promise.all(state.compareIds.map(async (id, i) => {
        const s = stationCache.find(x => x.id === id);
        const history = await ensureHistory(id);
        return { id, station: s, name: shortName(s), color: COMPARE_COLORS[i % COMPARE_COLORS.length], history, cur: priceOf(s) };
      }));
      if (g != null && g !== nav) return;
      try { window.ChartView.renderCompare(items, { tf: state.tf }); } catch (e) {}
      if (adapter.chartLoading) adapter.chartLoading(false);
      if (adapter.renderCompare) adapter.renderCompare(items, ctx);
    }
    async function refresh(g) { if (renderBootState()) return; refreshList(); if (state.compare) await renderCompareView(g); else await refreshSelection(g); persist(); }
    // Erneuter Versuch nach Verbindungsfehler: fehlende Regionen nachladen, dann
    // Stationen neu laden und normal rendern (wird dem Retry-Button uebergeben).
    async function reload() {
      const g = ++nav;
      if (regionsFailed || !regions.length) {
        try {
          regions = await API.getRegions();
          ctx.regions = regions;
          regionsFailed = false;
          if (!state.region && regions.length) state.region = regions[0].id;
          if (adapter.renderRegions) adapter.renderRegions(regions, ctx);
        } catch (e) { regionsFailed = true; }
      }
      await loadStations(g);
      if (g !== nav) return;
      if (!selected()) { const f = pickTop(); state.selectedId = f ? f.id : null; }
      await refresh(g);
    }

    function showDetailLoading() {
      if (state.compare) { if (adapter.chartLoading) adapter.chartLoading(true); }
      else if (adapter.renderSelectionSkeleton) adapter.renderSelectionSkeleton(selected(), ctx);
    }
    async function setFuel(fuel) {
      const g = ++nav;
      state.fuel = fuel;
      showDetailLoading();
      await loadStations(g);
      if (g !== nav) return;
      if (!selected()) { const f = pickTop(); state.selectedId = f ? f.id : null; }
      await refresh(g);
    }
    // Geschlossene Tankstellen aus-/einblenden: laedt die Liste serverseitig gefiltert neu
    // (openOnly), bereinigt eine evtl. ausgeblendete Auswahl/Vergleichsauswahl und rendert neu.
    async function setHideClosed(on) {
      const g = ++nav;
      state.hideClosed = on;
      persist();
      showDetailLoading();
      await loadStations(g);
      if (g !== nav) return;
      state.compareIds = state.compareIds.filter(id => stationCache.some(s => s.id === id));
      if (!selected()) { const f = pickTop(); state.selectedId = f ? f.id : null; }
      if (state.compare && !state.compareIds.length && state.selectedId) state.compareIds = [state.selectedId];
      await refresh(g);
    }
    async function setRegion(rg) {
      const g = ++nav;
      state.region = rg;
      showDetailLoading();
      await loadStations(g);
      if (g !== nav) return;
      const f = pickTop(); state.selectedId = f ? f.id : null;
      state.compareIds = state.compareIds.filter(id => stationCache.some(s => s.id === id));
      if (state.compare && !state.compareIds.length && state.selectedId) state.compareIds = [state.selectedId];
      await refresh(g);
    }
    async function setCompare(on) {
      const g = ++nav;
      state.compare = on;
      if (on && !state.compareIds.length && state.selectedId) state.compareIds = [state.selectedId];
      await refresh(g);
    }
    // Vorhersage ein-/ausblenden. Wirkt nur in der Einzelansicht (im Vergleichsmodus wird keine
    // Prognose gezeichnet); der Zustand bleibt erhalten und greift wieder, sobald der Vergleich endet.
    async function setForecast(on) {
      const g = ++nav;
      state.forecast = on;
      persist();
      if (state.compare) return;
      await refreshSelection(g);
    }
    async function toggleCompareId(id) {
      const i = state.compareIds.indexOf(id);
      if (i >= 0) { if (state.compareIds.length > 1) state.compareIds.splice(i, 1); }
      else if (state.compareIds.length < 4) state.compareIds.push(id);
      await refresh(++nav);
    }
    function applyChartTheme(theme) {
      window.ChartView.init({ chartEl: adapter.chart.chartEl, container: adapter.chart.container, tooltipEl: adapter.chart.tooltipEl, theme, fontFamily: adapter.font });
      const g = ++nav;
      if (state.compare) renderCompareView(g); else refreshSelection(g);
    }

    // Aktuelle Nutzerauswahl sichern (Region/Kraftstoff/Sortierung/Vergleich).
    function persist() {
      lsSet('fuel', state.fuel);
      lsSet('sort', state.sort);
      lsSet('hideClosed', String(state.hideClosed));
      if (state.region) lsSet('region', state.region);
      lsSet('compare', JSON.stringify({ on: state.compare, ids: state.compareIds }));
      lsSet('forecast', String(state.forecast));
    }

    // Auswahl bzw. Vergleich-Umschalten einer Listenkarte – gemeinsam fuer Maus und Tastatur.
    function activateCard(id) {
      if (state.compare) toggleCompareId(id);
      else { const g = ++nav; state.selectedId = id; refreshList(); refreshSelection(g); }
    }
    function wire() {
      // Aktiven Zustand einer segmentierten Schaltergruppe setzen (Klasse + aria-pressed).
      const markActive = (sel, b) => $$(sel).forEach(x => { const on = x === b; x.classList.toggle('active', on); x.setAttribute('aria-pressed', String(on)); });
      $$('[data-fuel]').forEach(b => b.addEventListener('click', () => { markActive('[data-fuel]', b); setFuel(b.dataset.fuel); }));
      $$('[data-sort]').forEach(b => b.addEventListener('click', () => {
        state.sort = b.dataset.sort; markActive('[data-sort]', b); refreshList(); persist();
      }));
      const hc = $('#hideClosed');
      if (hc) {
        hc.checked = state.hideClosed;
        hc.addEventListener('change', () => setHideClosed(hc.checked));
      }
      root.addEventListener('click', e => {
        // Entfernen-X im Vergleichspanel hat Vorrang und entfernt gezielt; ein Klick auf die
        // restliche Karte (linke Liste) waehlt aus bzw. schaltet die Station im Vergleich um.
        const rm = e.target.closest('.cmp-x[data-id]');
        if (rm && root.contains(rm)) { toggleCompareId(rm.dataset.id); return; }
        const card = e.target.closest('.scard[data-id]');
        if (card && root.contains(card)) activateCard(card.dataset.id);
      });
      // Tastaturbedienung der Liste: Enter/Leertaste auf einer fokussierten Karte waehlt aus bzw.
      // schaltet im Vergleich um; danach wird die neu gerenderte Karte wieder fokussiert.
      root.addEventListener('keydown', e => {
        if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') return;
        const card = e.target.closest('.scard[data-id]');
        if (!card || !root.contains(card)) return;
        e.preventDefault();
        const id = card.dataset.id;
        activateCard(id);
        const again = root.querySelector('.scard[data-id="' + (window.CSS && CSS.escape ? CSS.escape(id) : id) + '"]');
        if (again) again.focus();
      });
      $$('[data-tf]').forEach(b => b.addEventListener('click', () => { state.tf = +b.dataset.tf; markActive('[data-tf]', b); const g = ++nav; if (state.compare) renderCompareView(g); else renderChart(g); }));
      $$('[data-ct]').forEach(b => b.addEventListener('click', () => { if (state.compare) return; state.chartType = b.dataset.ct; markActive('[data-ct]', b); renderChart(++nav); }));
    }

    await loadStations();
    state.compareIds = state.compareIds.filter(id => stationCache.some(s => s.id === id));
    const first = pickTop();
    state.selectedId = first ? first.id : null;
    if (state.compare && !state.compareIds.length && state.selectedId) state.compareIds = [state.selectedId];
    wire();
    if (adapter.renderRegions) adapter.renderRegions(regions, ctx);
    await refresh();

    // ── Aktualisierung aus frischen Serverdaten ──────────────────────
    // Alle folgenden Funktionen sind passiv: nav wird NICHT erhoeht, damit ein laufendes
    // Nutzer-Rendern nicht abgebrochen wird; ein zwischenzeitlicher Nutzerwechsel (nav aendert sich)
    // verwirft das Ergebnis.

    // Liste + Detailspalte aus frischen Stationsdaten erneuern (Chart bleibt unberuehrt).
    async function refreshStationsFromServer() {
      const g = nav;
      let list;
      try { list = await API.getStations(state.region, state.fuel, state.hideClosed); }
      catch (e) { return false; }
      if (g !== nav) return false;
      list.forEach(s => { s.brandStyle = window.TKBRAND.style(s.brand); });
      stationCache = list;
      state.loadError = false;
      if (renderBootState()) return false;
      if (!selected()) { const f = pickTop(); state.selectedId = f ? f.id : null; }
      refreshList();
      refreshSelectionPanel();
      return true;
    }

    // Chart der aktuellen Auswahl live nachfuehren: nur echte neue Verlaufspunkte werden ueber
    // ChartView.update() eingespeist (Zoom/Pan bleiben erhalten). Bei gewechselter Auswahl oder
    // leerem Chart wird stattdessen voll neu gezeichnet.
    async function liveUpdateChart() {
      const s = selected(); if (!s) return;
      const g = nav;
      let nh;
      try { nh = await API.getHistory(s.id, state.fuel); }
      catch (e) { return; }
      if (g !== nav) return;
      seriesCache.set(s.id, nh);
      if (s.id !== chartStationId || !chartRawMaxTime) {
        const forecast = state.forecast ? await ensureForecast(s.id) : null;
        if (g !== nav) return;
        try { window.ChartView.render(nh, { type: state.chartType, tf: state.tf, forecast }); } catch (e) {}
        chartStationId = s.id;
        chartRawMaxTime = nh.length ? nh[nh.length - 1].time : 0;
        refreshSelectionPanel();
        return;
      }
      let changed = false;
      for (const p of nh) {
        if (p.time > chartRawMaxTime) { try { window.ChartView.update(p); } catch (e) {} chartRawMaxTime = p.time; changed = true; }
      }
      if (changed) refreshSelectionPanel();
    }

    // Vergleichsmodus live nachfuehren: frische Preise holen, je Station den juengsten Linienpunkt
    // aktualisieren (ChartView.updateCompare) sowie Liste und Vergleichspanel neu aufbauen.
    async function liveUpdateCompare() {
      const g = nav;
      let list;
      try { list = await API.getStations(state.region, state.fuel, state.hideClosed); }
      catch (e) { return; }
      if (g !== nav) return;
      list.forEach(s => { s.brandStyle = window.TKBRAND.style(s.brand); });
      stationCache = list;
      state.loadError = false;
      if (renderBootState()) return;
      state.compareIds = state.compareIds.filter(id => stationCache.some(s => s.id === id));
      refreshList();
      const values = [], items = [];
      state.compareIds.forEach((id, i) => {
        const s = stationCache.find(x => x.id === id);
        if (!s) return;
        values.push({ id, value: priceOf(s) });
        items.push({ id, station: s, name: shortName(s), color: COMPARE_COLORS[i % COMPARE_COLORS.length], cur: priceOf(s) });
      });
      try { window.ChartView.updateCompare(values); } catch (e) {}
      if (adapter.renderCompare) adapter.renderCompare(items, ctx);
    }

    // Ein Live-Ereignis (neue Preise) verarbeiten: je nach Modus Einzel- oder Vergleichsansicht.
    async function applyLiveUpdate() {
      if (!state.region) return;
      if (state.compare) { await liveUpdateCompare(); return; }
      if (await refreshStationsFromServer()) await liveUpdateChart();
    }

    // ── Echtzeit-Verbindung (Server-Sent Events) ─────────────────────
    function setLiveStatus(s) { if (adapter.renderLiveStatus) { try { adapter.renderLiveStatus(s); } catch (e) {} } }

    // Baut die SSE-Verbindung auf. Der Browser (EventSource) verbindet bei Abbruch selbsttaetig neu;
    // bei jedem (Wieder-)Verbinden wird einmal voll nachgeladen, um waehrend des Ausfalls verpasste
    // Aenderungen aufzuholen. Fehlt EventSource, uebernimmt der Rueckfall-Poll die Aktualisierung.
    function connectLive() {
      if (typeof window.EventSource === 'undefined') { setLiveStatus('offline'); return; }
      let es;
      try { es = new EventSource(API.STREAM_URL); }
      catch (e) { setLiveStatus('offline'); return; }
      liveConn.es = es;
      setLiveStatus('connecting');
      es.onopen = () => {
        const reconnected = liveConn.everConnected;
        liveConn.connected = true;
        liveConn.everConnected = true;
        setLiveStatus('live');
        if (reconnected && document.visibilityState === 'visible') applyLiveUpdate();
      };
      es.addEventListener('prices', () => { if (document.visibilityState === 'visible') applyLiveUpdate(); });
      es.onerror = () => { liveConn.connected = false; setLiveStatus('connecting'); };
    }

    // Rueckfall-Poll: greift nur, solange die Echtzeit-Verbindung NICHT steht. Bei stehender SSE-
    // Verbindung laeuft er leer und erzeugt keine zusaetzliche Last.
    async function pollList() {
      if (document.visibilityState !== 'visible' || !state.region || liveConn.connected) return;
      await applyLiveUpdate();
    }
    setInterval(pollList, POLL_INTERVAL_MS);

    // Nach dem Wechsel zurueck auf einen sichtbaren Tab einmal nachladen: waehrend der Tab verborgen
    // war, wurden Live-Ereignisse bewusst ignoriert (kein sichtbarer Chart).
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && liveConn.connected) applyLiveUpdate();
    });

    connectLive();

    return { state, refresh, setRegion, setCompare, setForecast, applyChartTheme, forecastEnabled: meta.forecastEnabled !== false };
  }

  window.TK = { eur, eur2, ct1, hhmm, esc, FUEL, makeDashboard };
})();
