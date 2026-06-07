// Modus (Hell/Dunkel/System) vor dem ersten Rendern setzen, damit nichts im falschen Modus aufblitzt.
// Bewusst als eigene, im <head> synchron geladene Datei (statt Inline-Skript), damit eine strenge
// Content-Security-Policy ohne 'unsafe-inline' für Skripte möglich ist.
(function () {
  try {
    var pref = localStorage.getItem('tk-pro-theme') || 'system';
    var dark = pref === 'dark' || (pref === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  } catch (e) {}
})();
