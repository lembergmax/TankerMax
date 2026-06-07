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
  const hhmm = t => new Date(t * 1000).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
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
  // Takt des Auto-Pollings der Preise (ms). Das Backend erfasst rotierend neue Preise.
  const POLL_INTERVAL_MS = 300000;
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
      compareOn: !!(savedCompare && savedCompare.on),
      compareIds: (savedCompare && Array.isArray(savedCompare.ids)) ? savedCompare.ids.slice(0, 4) : [],
    };

    const state = {
      fuel: saved.fuel || adapter.fuel || 'diesel', sort: saved.sort || 'dist',
      region: null, selectedId: null, chartType: 'candle', tf: 900,
      indicators: new Set(),
      compare: saved.compareOn, compareIds: saved.compareIds, loadError: false,
    };

    let regions = [], regionsFailed = false;
    try { regions = await API.getRegions(); }
    catch (e) { regionsFailed = true; }
    state.region = (saved.region && regions.some(r => r.id === saved.region)) ? saved.region : (regions.length ? regions[0].id : null);

    let stationCache = [];
    const seriesCache = new Map();
    // Generationszaehler gegen Races: jede Nutzeraktion (Region/Kraftstoff/Auswahl/Vergleich/Poll)
    // erhoeht ihn; eine veraltete asynchrone Antwort erkennt das und ueberschreibt nichts mehr.
    let nav = 0;

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
        const list = await API.getStations(state.region, state.fuel);
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
      if (g != null && g !== nav) return;
      try { window.ChartView.render(history, { type: state.chartType, tf: state.tf, indicators: state.indicators }); } catch (e) {}
      adapter.renderSelection({ s, hist: history, cur: priceOf(s) }, ctx);
    }
    // Aktualisiert nur die rechte Detailspalte aus dem Cache (kein Chart-Neuaufbau) – etwa fuer
    // das Auto-Polling der Preise.
    function refreshSelectionPanel() {
      const s = selected(); if (!s) return;
      const history = seriesCache.get(s.id); if (!history) return;
      adapter.renderSelection({ s, hist: history, cur: priceOf(s) }, ctx);
    }
    async function renderChart(g) {
      const s = selected(); if (!s) return;
      const history = await ensureHistory(s.id);
      if (g != null && g !== nav) return;
      try { window.ChartView.render(history, { type: state.chartType, tf: state.tf, indicators: state.indicators }); } catch (e) {}
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
      if (state.region) lsSet('region', state.region);
      lsSet('compare', JSON.stringify({ on: state.compare, ids: state.compareIds }));
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
      $$('[data-ind]').forEach(b => b.addEventListener('click', () => { if (state.compare) return; const k = b.dataset.ind; const on = !state.indicators.has(k); if (on) state.indicators.add(k); else state.indicators.delete(k); b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); renderChart(++nav); }));
    }

    await loadStations();
    state.compareIds = state.compareIds.filter(id => stationCache.some(s => s.id === id));
    const first = pickTop();
    state.selectedId = first ? first.id : null;
    if (state.compare && !state.compareIds.length && state.selectedId) state.compareIds = [state.selectedId];
    wire();
    if (adapter.renderRegions) adapter.renderRegions(regions, ctx);
    await refresh();

    // Auto-Polling der Preise: aktualisiert Liste + Detailspalte aus frischen Daten, ohne den Chart
    // neu aufzubauen (Zoom/Pan bleiben erhalten). Nur bei sichtbarem Tab und nicht im Vergleichsmodus.
    // Passiver Refresh: nav wird NICHT erhoeht, damit ein laufendes Nutzer-Rendern nicht abgebrochen
    // wird; ein zwischenzeitlicher Nutzerwechsel (nav aendert sich) verwirft das Poll-Ergebnis.
    async function pollList() {
      if (document.visibilityState !== 'visible' || state.compare || !state.region) return;
      const g = nav;
      let list;
      try { list = await API.getStations(state.region, state.fuel); }
      catch (e) { return; }
      if (g !== nav) return;
      list.forEach(s => { s.brandStyle = window.TKBRAND.style(s.brand); });
      stationCache = list;
      state.loadError = false;
      if (renderBootState()) return;
      if (!selected()) { const f = pickTop(); state.selectedId = f ? f.id : null; }
      refreshList();
      refreshSelectionPanel();
    }
    setInterval(pollList, POLL_INTERVAL_MS);

    return { state, refresh, setRegion, setCompare, applyChartTheme };
  }

  window.TK = { eur, eur2, ct1, hhmm, esc, FUEL, makeDashboard };
})();
