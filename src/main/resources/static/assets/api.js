/* ───────────────────────────────────────────────────────────────────
   Datenschicht: read-only Aufrufe an das Spring-Backend (/api/*). Es werden
   KEINE Tankerkönig-Live-Abfragen ausgelöst – gelesen wird nur aus der Datenbank.
   Alle Preise €/L; Zeiten sind Chart-Sekunden (lokale Zeit als UTC kodiert).
   ─────────────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const BASE = '/api';

  async function get(path) {
    const response = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error('HTTP ' + response.status + ' bei ' + path);
    return response.json();
  }

  window.TKAPI = {
    getMeta: () => get('/meta'),
    getRegions: () => get('/regions'),
    getStations: (region, fuel, openOnly) =>
      get('/stations?region=' + encodeURIComponent(region) + '&fuel=' + encodeURIComponent(fuel) + (openOnly ? '&openOnly=true' : '')),
    getHistory: (station, fuel) =>
      get('/history?station=' + encodeURIComponent(station) + '&fuel=' + encodeURIComponent(fuel)),
  };

  // Markenfarben fuer die Tankstellen-Kacheln (rein kosmetisch, im Frontend gepflegt).
  const BRANDS = {
    Aral: { bg: '#2f6df0', fg: '#fff', tag: 'A' },
    Shell: { bg: '#f6d029', fg: '#0b0f18', tag: 'S' },
    Esso: { bg: '#e0392f', fg: '#fff', tag: 'E' },
    TotalEnergies: { bg: '#ec5a3c', fg: '#fff', tag: 'T' },
    Total: { bg: '#ec5a3c', fg: '#fff', tag: 'T' },
    JET: { bg: '#f2c10f', fg: '#0b0f18', tag: 'J' },
    Star: { bg: '#4f90ff', fg: '#fff', tag: '★' },
    HEM: { bg: '#ee4d4d', fg: '#fff', tag: 'H' },
    AVIA: { bg: '#d23636', fg: '#fff', tag: 'Av' },
    'OIL!': { bg: '#ffd23f', fg: '#0b0f18', tag: 'OIL' },
    Sprint: { bg: '#2bb673', fg: '#04130d', tag: 'Sp' },
    bft: { bg: '#3a9d4a', fg: '#04130d', tag: 'bft' },
    Agip: { bg: '#f6c542', fg: '#0b0f18', tag: 'eni' },
  };

  function tagFor(brand) {
    if (!brand) return '?';
    return brand.slice(0, 1).toUpperCase();
  }

  window.TKBRAND = {
    style(brand) {
      return BRANDS[brand] || { bg: '#4f90ff', fg: '#fff', tag: tagFor(brand) };
    },
  };
})();
