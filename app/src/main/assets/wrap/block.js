// Ad servers and trackers, blocked inside the app's engine (only for this app, no VPN or DNS
// filter needed). The engine only shows this filter a page's requests if the extension has
// permission for the page's own host too ("*://*.spotify.com/*" in manifest.json) and the app has
// registered the page as one of the extension's tabs (MainActivity.listenToExtension).
// Audio ads cannot be blocked this way: they come from the same hosts as music. Those are handled
// by app.js and MainActivity (skip by restarting the player, or mute).
'use strict';

var BLOCK = ['video-fa.scdn.co', 'video-cf.spotifycdn.com', 's0.2mdn.net'];

// Analytics, tracking and error reporting: the page loads these on every start, and they compete
// with the player for the network and the CPU, which slows the player's start (and so every
// restart). Nothing in the player or in logging in depends on them. Not blocked on purpose:
// Spotify's own hosts, the cookie-consent service and reCAPTCHA (needed for logging in).
var TRACKERS = [
  'www.google-analytics.com', 'analytics.google.com', 'stats.g.doubleclick.net',
  'www.googletagmanager.com', 'sb.scorecardresearch.com', 'o22381.ingest.sentry.io',
  'www.fastly-insights.com'
];
BLOCK = BLOCK.concat(TRACKERS);
// A plain block of this host freezes playback; an answer that ends in an
// HTTP 403 does not, so it is sent to a host that answers 403 instead.
var REDIRECT = { 'adstudio-assets.scdn.co': 'misc.scdn.co' };

browser.webRequest.onBeforeRequest.addListener(function (details) {
  var url = new URL(details.url);
  if (BLOCK.indexOf(url.hostname) !== -1) return { cancel: true };
  var to = REDIRECT[url.hostname];
  if (to) { url.hostname = to; return { redirectUrl: url.href }; }
  return {};
}, { urls: ['*://*.scdn.co/*', '*://*.spotifycdn.com/*', '*://*.2mdn.net/*']
    .concat(TRACKERS.map(function (h) { return '*://' + h + '/*'; })) }, ['blocking']);
