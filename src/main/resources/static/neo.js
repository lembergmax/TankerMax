(function () {
  'use strict';
  const T = window.TK;
  const root = document;
  const $ = s => root.querySelector(s);
  const $$ = s => Array.from(root.querySelectorAll(s));

  // Chart-Palette aus den CSS-Variablen ableiten (Single Source: nur neo.css pflegen).
  // Wird nach dem Setzen von data-theme gelesen, damit Hell/Dunkel korrekt greifen.
  const CHART_TOKENS = { bg: '--chart-bg', grid: '--chart-grid', text: '--chart-text', border: '--chart-border', up: '--chart-up', down: '--chart-down', accent: '--chart-accent', accentSoft: '--chart-accent-soft', crosshair: '--chart-crosshair', crosshairLabel: '--chart-crosshair-label' };
  function chartPalette() {
    const cs = getComputedStyle(document.documentElement);
    const out = {};
    for (const k in CHART_TOKENS) out[k] = cs.getPropertyValue(CHART_TOKENS[k]).trim();
    return out;
  }

  // ── list (single + compare aware) ────────────────────────────────
  function renderList(list, c) {
    const cmp = c.state.compare;
    // Scroll- UND Fokusposition ueber den innerHTML-Neuaufbau hinweg bewahren: Auto-Polling und
    // Sortierwechsel rendern die Liste neu – sonst springt sie nach oben bzw. die per Tastatur
    // fokussierte Karte verliert den Fokus an <body>.
    const listEl = $('#list'); const scrollTop = listEl ? listEl.scrollTop : 0;
    const ae = document.activeElement;
    const focusedId = (ae && listEl && listEl.contains(ae) && ae.dataset) ? ae.dataset.id : null;
    listEl.innerHTML = list.map(s => {
      const tier = c.priceTier(s);
      const pv = s.isOpen ? '<div class="v ' + (tier === 'cheap' ? 'cheap' : '') + '">' + c.eur(c.priceOf(s)) + '</div><div class="u">€/L</div>' : '<div class="v closed">geschl.</div>';
      const sel = !cmp && s.id === c.state.selectedId;
      const on = cmp && c.state.compareIds.indexOf(s.id) >= 0;
      const dot = cmp ? '<span class="cmp-dot' + (on ? '' : ' empty') + '"' + (on ? ' style="background:' + c.compareColor(s.id) + ';border-color:' + c.compareColor(s.id) + '"' : '') + '></span>' : '';
      return '<div class="scard' + (sel ? ' sel' : '') + (cmp ? ' cmp' : '') + (on ? ' cmp-on' : '') + '" data-id="' + T.esc(s.id) + '" role="button" tabindex="0" aria-pressed="' + ((sel || on) ? 'true' : 'false') + '">' +
        '<div class="scard-top">' + dot + '<div class="bdot" style="background:' + s.brandStyle.bg + ';color:' + s.brandStyle.fg + '">' + T.esc(s.brandStyle.tag) + '</div>' +
        '<div class="scard-id"><div class="n">' + T.esc(c.shortName(s)) + '</div><div class="m">' + T.esc(s.brand) + ' · ' + c.ct1(s.dist) + ' km</div></div>' +
        '<div class="scard-p">' + pv + '</div></div></div>';
    }).join('');
    if (listEl) listEl.scrollTop = scrollTop;
    if (focusedId && listEl) { const again = listEl.querySelector('.scard[data-id="' + (window.CSS && CSS.escape ? CSS.escape(focusedId) : focusedId) + '"]'); if (again) again.focus(); }
  }

  // ── single selection ─────────────────────────────────────────────
  function renderSelection(d, c) {
    chartLoading(false);
    const s = d.s, cur = d.cur, hist = d.hist;
    $('#dhead').innerHTML =
      '<div class="dhead-l"><div class="bdot" style="background:' + s.brandStyle.bg + ';color:' + s.brandStyle.fg + '">' + T.esc(s.brandStyle.tag) + '</div>' +
      '<div style="min-width:0"><div class="nm">' + T.esc(c.shortName(s)) + '</div><div class="ad">' + T.esc(s.brand) + ' · ' + T.esc(s.street) + ', ' + T.esc(s.place) + '</div></div></div>' +
      '<div class="dhead-r"><div class="pv">' + (s.isOpen ? c.eur(cur) : '—') + '</div>' + deltaHTML(hist, cur, c) + '</div>';

    const t = $('#tiles'); if (t) { t.style.display = ''; renderTiles(d, c); }

    const html = '<div class="info-card"><h3>Details</h3>' +
      kv('Marke', T.esc(s.brand)) + kv('Entfernung', c.ct1(s.dist) + ' km') +
      '<div class="kv"><span class="k">Status</span><span class="stp ' + (s.isOpen ? 'open' : 'closed') + '">' + (s.isOpen ? 'Geöffnet' : 'Geschlossen') + '</span></div>' +
      kv('Öffnungszeiten', openingHours(s)) + '</div>';
    $('#rcol').innerHTML = html;
  }

  // Öffnungszeiten als HTML: 24h-Betrieb, sonst die echten Zeiten je Tagesbereich,
  // Fallback „Nach Aushang" nur, wenn keine angereicherten Daten vorliegen.
  function openingHours(s) {
    if (s.wholeDay) return '24 Stunden';
    const times = s.openingTimes || [];
    if (!times.length) return 'Nach Aushang';
    return times.map(function (o) {
      const span = (o.open && o.close) ? ' ' + T.esc(o.open) + '–' + T.esc(o.close) : '';
      return T.esc(o.days) + span;
    }).join('<br>');
  }

  function renderTiles(d, c) {
    const s = d.s, cur = d.cur;
    const open = c.openList().slice().sort((a, b) => c.priceOf(a) - c.priceOf(b));
    const rank = open.findIndex(x => x.id === s.id) + 1;
    let html = tile('Aktueller Preis', (s.isOpen ? c.eur(cur) : '—'), 'i', 'var(--ind)', c.FUEL[c.state.fuel]);
    html += tile('Rang im Umkreis', (rank > 0 ? '#' + rank : '—'), 'i', 'var(--ind)', 'von ' + open.length + ' geöffnet');
    $('#tiles').innerHTML = html;
  }
  function tile(k, v, vc, dot, sub) { return '<div class="tile"><div class="tk"><i style="background:' + dot + '"></i>' + k + '</div><div class="tv ' + vc + '">' + v + '</div><div class="ts">' + sub + '</div></div>'; }

  // ── compare panel ────────────────────────────────────────────────
  function renderCompare(items, c) {
    chartLoading(false);
    $('#dhead').innerHTML =
      '<div class="dhead-l"><div class="cmp-ic">⇄</div><div><div class="nm">Preisvergleich</div>' +
      '<div class="ad">' + items.length + ' Stationen · ' + T.esc(c.state.region) + ' · ' + c.FUEL[c.state.fuel] + '</div></div></div>' +
      '<div class="dhead-r"><div class="pv" style="font-size:21px">' + items.length + '/4</div><div class="dl flat">im Vergleich</div></div>';
    const t = $('#tiles'); if (t) t.style.display = 'none';

    const rows = items.slice().sort((a, b) => a.cur - b.cur);
    let html = '<div class="cmp-panel"><h3>Im Vergleich</h3>';
    rows.forEach(it => {
      html += '<div class="cmp-row" data-id="' + T.esc(it.id) + '"><span class="cmp-sw" style="background:' + it.color + '"></span>' +
        '<div class="cmp-info"><div class="n">' + T.esc(it.name) + '</div><div class="m">' + T.esc(it.station.brand) + ' · ' + c.ct1(it.station.dist) + ' km</div></div>' +
        '<div class="cmp-p"><div class="v">' + (it.station.isOpen ? c.eur(it.cur) : '—') + '</div></div>' +
        '<button class="cmp-x" data-id="' + T.esc(it.id) + '" title="Entfernen">×</button></div>';
    });
    html += '</div><div class="cmp-hint">Tippe Tankstellen links an, um sie hinzuzufügen oder zu entfernen (max. 4). Linien zeigen den Preisverlauf je Station.</div>';
    $('#rcol').innerHTML = html;
  }

  // Vergleichswert „gestern": der juengste Ist-Punkt, der mindestens 24 h zurueckliegt
  // (zeitbasiert statt festem Indexabstand, da das Sampling je nach Anzahl der Orte variiert).
  function dayAgoValue(hist) {
    if (!hist.length) return null;
    const now = (window.TKCLOCK && window.TKCLOCK.now) || hist[hist.length - 1].time;
    const target = now - 86400;
    let best = null;
    for (const p of hist) { if (p.time <= target) best = p; else break; }
    return (best || hist[0]).value;
  }
  function deltaHTML(hist, cur, c) {
    const da = dayAgoValue(hist), dl = (da != null && cur != null) ? cur - da : 0;
    const cls = dl > 0.0005 ? 'up' : dl < -0.0005 ? 'down' : 'flat';
    return '<div class="dl ' + cls + '">' + (dl >= 0 ? '+' : '−') + c.ct1(Math.abs(dl * 100)) + ' ct seit gestern</div>';
  }
  function kv(k, v, cls) { return '<div class="kv"><span class="k">' + k + '</span><span class="v ' + (cls || '') + '">' + v + '</span></div>'; }

  // ── Skeleton-Platzhalter waehrend des Ladens ─────────────────────
  function skLine(width, cls) {
    return '<div class="sk sk-line ' + (cls || '') + '"' + (width ? ' style="width:' + width + '"' : '') + '></div>';
  }
  function chartLoading(on) {
    const frame = $('#chartFrame'); if (!frame) return;
    let el = frame.querySelector('.chart-skel');
    if (on) { if (!el) { el = document.createElement('div'); el.className = 'chart-skel sk'; frame.appendChild(el); } }
    else if (el) { el.remove(); }
  }
  function renderListSkeleton() {
    let html = '';
    for (let i = 0; i < 7; i++) {
      html += '<div class="scard sk-card"><div class="scard-top"><div class="sk sk-bdot"></div>' +
        '<div class="scard-id" style="display:flex;flex-direction:column;gap:6px">' + skLine('62%', 'lg') + skLine('42%', 'sm') + '</div>' +
        '<div class="sk sk-price"></div></div></div>';
    }
    $('#list').innerHTML = html;
  }
  function renderSelectionSkeleton() {
    chartLoading(true);
    $('#dhead').innerHTML =
      '<div class="dhead-l"><div class="sk sk-bdot"></div>' +
      '<div style="min-width:0;flex:1;display:flex;flex-direction:column;gap:8px">' + skLine('180px', 'lg') + skLine('240px', 'sm') + '</div></div>' +
      '<div class="dhead-r" style="display:flex;flex-direction:column;gap:8px;align-items:flex-end">' + skLine('120px', 'lg') + skLine('90px', 'sm') + '</div>';
    const t = $('#tiles');
    if (t) {
      t.style.display = '';
      let th = '';
      for (let i = 0; i < 2; i++) th += '<div class="tile" style="gap:8px">' + skLine('60%', 'sm') + skLine('70%', 'lg') + skLine('50%', 'sm') + '</div>';
      t.innerHTML = th;
    }
    $('#rcol').innerHTML =
      '<div class="info-card" style="display:flex;flex-direction:column;gap:12px">' + skLine('50%', 'sm') + skLine('80%', 'sm') + skLine('65%', 'sm') + skLine('75%', 'sm') + '</div>';
  }

  // ── Fehler- und Leerzustaende (Adapter-Callbacks der Engine) ─────
  const WARN_ICON = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
  const EMPTY_ICON = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>';
  function stateView(icon, title, sub, btnLabel) {
    return '<div class="state"><div class="state-ico">' + icon + '</div>' +
      '<div class="state-tt">' + title + '</div>' +
      (sub ? '<div class="state-sub">' + sub + '</div>' : '') +
      (btnLabel ? '<button class="state-btn" type="button" data-retry>' + btnLabel + '</button>' : '') +
      '</div>';
  }
  // Verbindungsfehler: Liste und rechte Spalte zeigen denselben Zustand; der Retry-
  // Button (in beiden Bereichen) ruft die von der Engine uebergebene reload-Funktion.
  function renderError(c, retry) {
    chartLoading(false);
    const title = 'Verbindung zum Server fehlgeschlagen';
    $('#list').innerHTML = stateView(WARN_ICON, title, 'Die Tankstellen konnten nicht geladen werden.', 'Erneut versuchen');
    $('#dhead').innerHTML = '';
    const t = $('#tiles'); if (t) t.style.display = 'none';
    $('#rcol').innerHTML = stateView(WARN_ICON, title, 'Bitte Verbindung prüfen und erneut versuchen.', 'Erneut versuchen');
    $$('[data-retry]').forEach(b => b.addEventListener('click', () => { if (typeof retry === 'function') retry(); }));
  }
  // Keine Tankstellen (leere Region): freundlicher Leerzustand statt leerem Detail.
  function renderEmpty() {
    chartLoading(false);
    $('#list').innerHTML = stateView(EMPTY_ICON, 'Keine Tankstellen', 'Für diese Region liegen derzeit keine Daten vor.', '');
    $('#dhead').innerHTML = '';
    const t = $('#tiles'); if (t) t.style.display = 'none';
    $('#rcol').innerHTML = stateView(EMPTY_ICON, 'Keine Auswahl', 'Sobald Daten vorliegen, erscheinen hier die Details.', '');
  }
  // Letzte Sicherung: schlaegt die Initialisierung fehl (z. B. Chart-Bibliothek nicht geladen oder
  // ein unerwarteter Fehler in start/makeDashboard), zeigt die App statt einer weissen Seite einen
  // Fehlerzustand mit „Neu laden"-Button.
  function fatalError() {
    try {
      chartLoading(false);
      const html = stateView(WARN_ICON, 'Etwas ist schiefgelaufen', 'Die Oberfläche konnte nicht geladen werden.', 'Neu laden');
      const list = $('#list'); if (list) list.innerHTML = html;
      const rcol = $('#rcol'); if (rcol) rcol.innerHTML = html;
      const dh = $('#dhead'); if (dh) dh.innerHTML = '';
      $$('[data-retry]').forEach(b => b.addEventListener('click', () => location.reload()));
    } catch (e) {}
  }
  // Regionen-Auswahl befuellen; die Engine ruft dies bei Start und nach erfolgreichem Retry.
  function renderRegions(regions, c) {
    const rs = $('#regionSel'); if (!rs) return;
    rs.innerHTML = regions.map(r => '<option value="' + T.esc(r.id) + '">' + T.esc(r.label) + ' (' + r.count + ')</option>').join('');
    rs.value = c.state.region;
  }

  async function start() {
    // Zuerst den Modus (Hell/Dunkel/System) bestimmen und anwenden, damit die
    // Oberflaeche von Anfang an im richtigen Modus erscheint (kein Umschalt-Blitzen).
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    let pref = 'system';
    try { pref = localStorage.getItem('tk-pro-theme') || 'system'; } catch (e) {}
    const resolve = p => p === 'system' ? (mq.matches ? 'dark' : 'light') : p;
    let resolved = resolve(pref);
    document.documentElement.setAttribute('data-theme', resolved);
    $$('#themeSeg button').forEach(b => b.classList.toggle('active', b.dataset.mode === pref));

    const api = await T.makeDashboard({
      root, fuel: 'diesel',
      chart: { chartEl: $('#chartEl'), container: $('#chartFrame'), tooltipEl: $('#chartTooltip') },
      font: "'Plus Jakarta Sans', system-ui, sans-serif",
      theme: chartPalette(),
      renderList, renderSelection, renderCompare, renderListSkeleton, renderSelectionSkeleton, chartLoading,
      renderError, renderEmpty, renderRegions,
    });

    // ── theme: spaeteres Umschalten (Buttons oder System-Aenderung) ──
    function applyTheme(p) {
      pref = p; try { localStorage.setItem('tk-pro-theme', p); } catch (e) {}
      resolved = resolve(p);
      document.documentElement.setAttribute('data-theme', resolved);
      $$('#themeSeg button').forEach(b => { const on = b.dataset.mode === p; b.classList.toggle('active', on); b.setAttribute('aria-pressed', String(on)); });
      api.applyChartTheme(chartPalette());
    }
    $$('#themeSeg button').forEach(b => b.addEventListener('click', () => applyTheme(b.dataset.mode)));
    mq.addEventListener('change', () => { if (pref === 'system') applyTheme('system'); });

    // ── region ──
    const rs = $('#regionSel');
    if (rs) rs.addEventListener('change', () => api.setRegion(rs.value));

    // ── compare toggle ──
    const cb = $('#cmpBtn');
    if (cb) cb.addEventListener('click', () => {
      const on = !api.state.compare;
      cb.classList.toggle('active', on);
      cb.querySelector('.lbl').textContent = on ? 'Vergleich beenden' : 'Vergleichen';
      api.setCompare(on);
    });

    // ── wiederhergestellten Zustand auf die Bedienelemente spiegeln (Kraftstoff/Sortierung/Vergleich) ──
    $$('[data-fuel]').forEach(b => b.classList.toggle('active', b.dataset.fuel === api.state.fuel));
    $$('[data-sort]').forEach(b => b.classList.toggle('active', b.dataset.sort === api.state.sort));
    if (cb && api.state.compare) { cb.classList.add('active'); const lbl = cb.querySelector('.lbl'); if (lbl) lbl.textContent = 'Vergleich beenden'; }
    // Anfangswerte fuer aria-pressed aus dem sichtbaren Aktiv-Zustand ableiten (die Schalter sind <button>).
    $$('[data-fuel],[data-sort],[data-tf],[data-ct],[data-ind],#themeSeg button').forEach(b => b.setAttribute('aria-pressed', String(b.classList.contains('active') || b.classList.contains('on'))));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => start().catch(fatalError)); else start().catch(fatalError);
})();
