(function () {
  'use strict';
  const T = window.TK;
  const root = document;
  const $ = s => root.querySelector(s);
  const $$ = s => Array.from(root.querySelectorAll(s));

  // Lade-Signal fuer assistive Technik: setzt aria-busy auf der Live-Region, damit ein Screenreader
  // den Wechsel von Ladeplatzhalter zu Inhalt bzw. den Fehler-/Leerzustand korrekt ansagt.
  function setBusy(sel, on) { const el = $(sel); if (el) el.setAttribute('aria-busy', on ? 'true' : 'false'); }

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
    setBusy('#list', false);
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

    const addr = [s.street, [s.postCode, s.place].filter(Boolean).join(' ')].filter(Boolean).join(', ');
    const html = '<div class="info-card"><h3>Details</h3>' +
      kv('Marke', T.esc(s.brand)) +
      kv('Adresse', T.esc(addr || '—'), 'addr') +
      idField(s.id) +
      kv('Entfernung', c.ct1(s.dist) + ' km') +
      '<div class="kv"><span class="k">Status</span><span class="stp ' + (s.isOpen ? 'open' : 'closed') + '">' + (s.isOpen ? 'Geöffnet' : 'Geschlossen') + '</span></div>' +
      openingBlock(s) + '</div>';
    $('#rcol').innerHTML = html;
  }

  // ── Öffnungszeiten ───────────────────────────────────────────────
  // Strukturierte Darstellung statt eines einzeiligen kv-Werts: je Tagesbereich eine
  // eigene Zeile (Tag links, Zeitspanne rechts), der heutige Tag hervorgehoben, plus eine
  // Kopf-Badge mit dem aktuellen Status. 24h und fehlende Daten erhalten eigene Layouts.
  const CLOCK_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 14"/></svg>';
  const DAY_TOKENS = { mo: 0, montag: 0, di: 1, die: 1, dienstag: 1, mi: 2, mittwoch: 2, do: 3, don: 3, donnerstag: 3, fr: 4, freitag: 4, sa: 5, samstag: 5, so: 6, son: 6, sonntag: 6 };

  // Wandelt eine Tagesbeschreibung („Mo-Fr", „Sa", „täglich" …) in die Menge der abgedeckten
  // Wochentage (0=Mo … 6=So). Best-effort: bei unklarer Angabe null → kein Heute-Highlight.
  function parseDays(desc) {
    if (!desc) return null;
    const t = String(desc).toLowerCase().replace(/[–—]/g, '-').replace(/\s*-\s*/g, '-').replace(/feiertage?/g, '').trim();
    const set = new Set();
    const add = (a, b) => { if (b == null) b = a; for (let i = a; i <= b; i++) set.add(((i % 7) + 7) % 7); };
    if (/(täglich|taeglich|durchgehend|alle tage|jeden tag|\btgl\b)/.test(t)) { add(0, 6); return set; }
    if (/(wochentags|werktags|unter der woche)/.test(t)) add(0, 4);
    if (/wochenende/.test(t)) add(5, 6);
    t.split(/[,+/&]|\bund\b|\bu\.\b/).forEach(part => {
      part = part.trim();
      const range = part.match(/^([a-zä]+)-([a-zä]+)$/);
      if (range && DAY_TOKENS[range[1]] != null && DAY_TOKENS[range[2]] != null) {
        let a = DAY_TOKENS[range[1]], b = DAY_TOKENS[range[2]];
        if (b < a) b += 7;
        add(a, b);
      } else if (DAY_TOKENS[part] != null) {
        add(DAY_TOKENS[part]);
      }
    });
    return set.size ? set : null;
  }
  function todayIdx() {
    const sec = (window.TKCLOCK && window.TKCLOCK.now) || Math.round(Date.now() / 1000);
    return (new Date(sec * 1000).getUTCDay() + 6) % 7; // 0=Mo … 6=So (sec ist lokale Zeit als UTC kodiert)
  }
  function ohCap(badge) {
    return '<div class="oh-cap"><span class="oh-lbl">' + CLOCK_ICON + 'Öffnungszeiten</span>' + (badge || '') + '</div>';
  }
  function openingBlock(s) {
    // 24-Stunden-Betrieb: eigene, deutlich lesbare Karte.
    if (s.wholeDay) {
      return '<div class="oh">' + ohCap('') +
        '<div class="oh-24"><span class="oh-24-ic">' + CLOCK_ICON + '</span>' +
        '<span class="oh-24-tx"><span class="t1">Durchgehend geöffnet</span><span class="t2">Rund um die Uhr · 7 Tage die Woche</span></span></div></div>';
    }
    const times = (s.openingTimes || []).filter(Boolean);
    if (!times.length) {
      return '<div class="oh">' + ohCap('') + '<div class="oh-note">Keine Zeiten hinterlegt · nach Aushang</div></div>';
    }
    const today = todayIdx();
    let todayClose = null, todayOpen = null;
    const rows = times.map(o => {
      const days = parseDays(o.days);
      const isToday = !!(days && days.has(today));
      if (isToday) { if (o.close) todayClose = o.close; if (o.open) todayOpen = o.open; }
      const time = (o.open && o.close) ? T.esc(o.open) + '–' + T.esc(o.close)
        : (o.open ? 'ab ' + T.esc(o.open) : (o.close ? 'bis ' + T.esc(o.close) : 'nach Aushang'));
      return '<div class="oh-row' + (isToday ? ' today' : '') + '">' +
        '<span class="oh-day">' + T.esc(o.days) + '</span>' +
        '<span class="oh-rt">' + (isToday ? '<span class="oh-now">Heute</span>' : '') +
        '<span class="oh-time">' + time + '</span></span></div>';
    }).join('');
    // Kopf-Badge: nutzt den verbindlichen isOpen-Status plus die heutige Schluss-/Öffnungszeit.
    const badge = s.isOpen
      ? '<span class="oh-badge open">Geöffnet' + (todayClose ? ' · bis ' + T.esc(todayClose) : '') + '</span>'
      : '<span class="oh-badge closed">Geschlossen' + (todayOpen ? ' · ab ' + T.esc(todayOpen) : '') + '</span>';
    return '<div class="oh">' + ohCap(badge) + '<div class="oh-sched">' + rows + '</div></div>';
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

  // ── Tankstellen-ID: eigenes Wertfeld mit Kopier-Schaltfläche ─────────────────
  // Bezeichnung in eigener Zeile (wie eine Teilüberschrift), darunter ein ruhiges
  // Feld im Stil der Modal-Eingaben (--surf2-Fläche, Fokusring) mit der ID in
  // Monospace und einem angedockten Ghost-Button zum Kopieren.
  const COPY_ICON = '<svg class="ic-copy" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
  const CHECK_ICON = '<svg class="ic-check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>';
  // Der Roh-Wert wird sowohl im sichtbaren <code> als auch im data-copy-Attribut per
  // T.esc(...) maskiert; beim Klick liest der Browser das Attribut wieder entschlüsselt.
  function idField(id) {
    const e = T.esc(id);
    return '<div class="idkv"><span class="idk">Tankstellen-ID</span>' +
      '<div class="idfield"><code class="idval">' + e + '</code>' +
      '<button type="button" class="copy-btn" data-copy="' + e + '" aria-label="Tankstellen-ID kopieren" title="ID kopieren">' +
      COPY_ICON + CHECK_ICON + '<span class="copy-tx">Kopiert</span></button></div></div>';
  }

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
    setBusy('#list', true);
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
    setBusy('#list', false);
    $$('[data-retry]').forEach(b => b.addEventListener('click', () => { if (typeof retry === 'function') retry(); }));
  }
  // Keine Tankstellen (leere Region): freundlicher Leerzustand statt leerem Detail.
  function renderEmpty() {
    chartLoading(false);
    $('#list').innerHTML = stateView(EMPTY_ICON, 'Keine Tankstellen', 'Für diese Region liegen derzeit keine Daten vor.', '');
    $('#dhead').innerHTML = '';
    const t = $('#tiles'); if (t) t.style.display = 'none';
    $('#rcol').innerHTML = stateView(EMPTY_ICON, 'Keine Auswahl', 'Sobald Daten vorliegen, erscheinen hier die Details.', '');
    setBusy('#list', false);
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
      setBusy('#list', false);
      $$('[data-retry]').forEach(b => b.addEventListener('click', () => location.reload()));
    } catch (e) {}
  }
  // Regionen-Auswahl befuellen; die Engine ruft dies bei Start und nach erfolgreichem Retry.
  function renderRegions(regions, c) {
    const rs = $('#regionSel'); if (!rs) return;
    rs.innerHTML = regions.map(r => '<option value="' + T.esc(r.id) + '">' + T.esc(r.label) + ' (' + r.count + ')</option>').join('');
    rs.value = c.state.region;
  }

  // ── Kopieren in die Zwischenablage (für die Tankstellen-ID) ──────────────────
  // Bevorzugt die Async-Clipboard-API (nur im sicheren Kontext verfügbar), fällt
  // sonst auf ein verstecktes <textarea> + execCommand zurück. Liefert true/false.
  async function copyToClipboard(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) { /* fällt unten auf execCommand zurück */ }
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '-9999px';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }
  // Erfolg/Fehler für Screenreader über eine eigene Live-Region außerhalb von #rcol
  // ansagen, damit sie nicht bei jedem Neuaufbau der Detailspalte verworfen wird und
  // nicht mit deren eigener aria-live-Region kollidiert.
  function announceCopy(message) { const node = $('#copyAnnounce'); if (node) node.textContent = message; }
  // Sichtbares Feedback am Button (grünes Häkchen bzw. roter Hinweis) samt Ansage; ein
  // gemeinsamer Timer setzt nach kurzer Zeit zurück und übersteht schnelles Mehrfachklicken.
  let copyResetTimer = null;
  function flashCopy(btn, ok) {
    btn.classList.remove('copied', 'failed');
    btn.classList.add(ok ? 'copied' : 'failed');
    announceCopy(ok ? 'Tankstellen-ID kopiert' : 'Kopieren fehlgeschlagen');
    clearTimeout(copyResetTimer);
    copyResetTimer = setTimeout(() => { btn.classList.remove('copied', 'failed'); announceCopy(''); }, 1500);
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

    // ── Tankstellen-ID kopieren ──
    // Eine delegierte Listener-Instanz: renderSelection baut die Detailspalte bei jeder
    // Auswahl/jedem Poll per innerHTML neu auf, ein einmaliger Listener auf root überlebt das.
    root.addEventListener('click', async e => {
      const btn = e.target.closest('.copy-btn');
      if (!btn) return;
      const ok = await copyToClipboard(btn.dataset.copy || '');
      flashCopy(btn, ok);
    });

    // ── wiederhergestellten Zustand auf die Bedienelemente spiegeln (Kraftstoff/Sortierung/Vergleich) ──
    $$('[data-fuel]').forEach(b => b.classList.toggle('active', b.dataset.fuel === api.state.fuel));
    $$('[data-sort]').forEach(b => b.classList.toggle('active', b.dataset.sort === api.state.sort));
    if (cb && api.state.compare) { cb.classList.add('active'); const lbl = cb.querySelector('.lbl'); if (lbl) lbl.textContent = 'Vergleich beenden'; }
    // Anfangswerte fuer aria-pressed aus dem sichtbaren Aktiv-Zustand ableiten (die Schalter sind <button>).
    $$('[data-fuel],[data-sort],[data-tf],[data-ct],#themeSeg button').forEach(b => b.setAttribute('aria-pressed', String(b.classList.contains('active'))));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => start().catch(fatalError)); else start().catch(fatalError);
})();
