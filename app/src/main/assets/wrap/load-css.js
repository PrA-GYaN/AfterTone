// Runs before inject.js in the app's built-in extension: hands it inject.css the same way the
// userscript does (window.__WRAP_CSS__); inject.js re-applies the style on its next tick.
fetch(browser.runtime.getURL('inject.css'))
  .then(function (r) { return r.text(); })
  .then(function (css) { window.__WRAP_CSS__ = css; });
