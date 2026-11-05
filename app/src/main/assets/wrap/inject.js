// Injected into every open.spotify.com page by MainActivity (after the page is shown and again
// when it has loaded). It does five things:
//   1. sets the viewport (phone layout, or desktop mode as a fallback, see LAYOUT),
//   2. adds inject.css (passed in as window.__WRAP_CSS__) and keeps it last in <head>,
//   3. phone layout: marks Spotify's layout areas so inject.css can rearrange them, and adds a
//      bottom tab bar (Home / Search / Library) and full-screen Library and Now Playing sheets,
//   4. reports the playing track and its cover to the app (notification, lock screen, headset
//      buttons),
//   5. offers window.__wrap.playPause()/next()/previous()/back() for the app to call.
// Edit this file and inject.css, rebuild, run. To try changes without rebuilding, use
// chrome://inspect on a computer (debug builds) and paste them into the page's console.
(function () {
  'use strict';
  if (window.__wrap) return;   // already installed in this page

  // ---- things you will tune ----

  // 'mobile': the page is as wide as the phone and rearranged for it by inject.css.
  // 'desktop': Spotify's own desktop layout scaled down to fit (pinch to zoom). Use it if a
  // Spotify update breaks the phone layout.
  var LAYOUT = 'mobile';
  var DESKTOP_WIDTH_LANDSCAPE = 1100, DESKTOP_WIDTH_PORTRAIT = 820;

  var POLL_MS = 1000;   // how often the playing state is checked

  // Spotify's markup changes without notice. When something stops working, find the new
  // data-testid / id in chrome://inspect and fix it here. Checked against open.spotify.com on
  // 2026-10-27.
  var SEL = {
    playPause: '[data-testid="control-button-playpause"]',
    next: '[data-testid="control-button-skip-forward"]',
    previous: '[data-testid="control-button-skip-back"]',
    title: '[data-testid="context-item-link"]',
    artist: '[data-testid="context-item-info-artist"]',
    cover: '[data-testid="cover-art-image"]',
    position: '[data-testid="playback-position"]',   // the player's elapsed-time clock
    duration: '[data-testid="playback-duration"]',   // the player's total time
    // Shown in the player only while an ad plays (used by app.js).
    adTitle: '[data-testid="context-item-info-ad-title"]',
    adSubtitle: '[data-testid="context-item-info-ad-subtitle"]',
    adCountdown: '[data-testid="ad-countdown-timer"]',
    // Where Spotify's pop-up messages (snackbars) appear, including the accessibility copy.
    messages: '[aria-live], [role="alert"], [role="status"], [data-encore-id*="nackbar"]',
    // Layout. The grid's children are recognised by their CSS grid area, not by class names.
    grid: '[data-testid="root"] > div:first-child',
    // Only Spotify's desktop page has it. Without Desktop site mode Spotify serves its own
    // mobile web player, which needs nothing from us.
    desktopPage: '#Desktop_LeftSidebar_Id',
    // Spotify's download buttons (playlist / album action bar, "..." menus): the app shows its own
    // "coming soon" note instead (English label for the action-bar button).
    download: '[data-testid="download-button"], #main-view button[aria-label^="Download"], [role="menuitem"][aria-label^="Download"]',
    // The wide picture at the top of an artist page.
    banner: '#main-view [data-testid="background-image"]',
    playerBar: '[data-testid="now-playing-bar"]',
    playerControls: '[data-testid="player-controls"]',
    volume: '[data-testid="volume-bar"]',
    homeButton: '[data-testid="home-button"]',
    topbarRight: '#global-nav-bar > div:last-child',
    // Top-bar items kept on a phone: your profile, What's New, and sign-up / log-in when logged
    // out. Everything else there ("Install App", the friends / activity button) is hidden.
    topbarKeep: '[data-testid="user-widget-link"], [data-testid="unauth-user-widget-link"], [data-testid="signup-button"], [data-testid="login-button"], [aria-label="What\'s New"]',
    browseButton: '[data-testid="browse-button"]',
    searchInput: '[data-testid="search-input"]',
    panel: '#Desktop_PanelContainer_Id',
    panelClose: '[data-testid="PanelHeader_CloseButton"] button, button[data-testid="PanelHeader_CloseButton"]',
    // The player's panel buttons say when their panel is open (aria-pressed): queue, devices.
    panelToggleOn: '[data-wrap="np-extra"] button[aria-pressed="true"]:not([data-testid="lyrics-button"])',
    // Lyrics: at phone width Spotify shows them in the left area (where Your Library lives).
    lyricsOn: '[data-testid="lyrics-button"][aria-pressed="true"]',
    // The Now Playing panel shrunk to a thin strip: shown as closed.
    panelCollapsed: 'button[aria-label^="Show Now Playing"]'
  };

  // ---- helpers ----

  function q(selector) { return document.querySelector(selector); }

  function click(selector) {
    var el = q(selector);
    if (el) { el.click(); return true; }
    console.log('wrap: not found ' + selector);
    return false;
  }

  // Spotify's green "Playing on Web Player (Firefox)" bar under the player: it only says this app
  // is the player, which you know. Another device's name (the phone app, a speaker) still shows.
  var CONNECT_BAR = /^\s*Playing on Web Player/i;

  var html = document.documentElement;
  // Only on a phone: the same script also runs as a userscript, where it may meet a computer.
  var phone = Math.min(screen.width, screen.height) < 600 && window.matchMedia('(pointer: coarse)').matches;
  var mobile = LAYOUT === 'mobile' && phone;

  // ---- viewport and style ----

  var landscape = window.matchMedia('(orientation: landscape)');

  function viewportContent() {
    // No zoom: no pinch-zoom, and no jump into the search box when it gets the keyboard.
    if (mobile) return 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover';
    var width = landscape.matches ? DESKTOP_WIDTH_LANDSCAPE : DESKTOP_WIDTH_PORTRAIT;
    // No initial-scale: the WebView picks the scale that fits the width.
    return 'width=' + width + ', user-scalable=yes, minimum-scale=0.25, maximum-scale=4';
  }

  function applyViewport() {
    if (!document.head) return;
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    var content = viewportContent();
    if (m.content !== content) m.content = content;
  }

  function applyStyle() {
    var head = document.head;
    if (!head) return;
    var css = window.__WRAP_CSS__ || '';
    var s = document.getElementById('__wrap_style');
    if (!s) {
      s = document.createElement('style');
      s.id = '__wrap_style';
    }
    if (s.textContent !== css) s.textContent = css;
    // Last in <head>, so rules of equal strength beat Spotify's own.
    if (head.lastElementChild !== s) head.appendChild(s);
  }

  // A browser in "Desktop site" mode (e.g. Firefox) lays the page out about 980px wide and
  // ignores the viewport tag. Zoom the page so it is laid out at the phone's own width; the
  // browser still presents itself as a desktop, nothing about that changes.
  // Measured once per orientation with the zoom off, then reused: once zoomed, Firefox reports
  // sizes that already include the zoom, and measuring again would stack it.
  var zoomFor = {}, measuring = false;
  function fitZoom() {
    if (!mobile || measuring) return;
    var key = landscape.matches ? 'landscape' : 'portrait';
    if (key in zoomFor) {
      if (html.style.zoom !== zoomFor[key]) html.style.zoom = zoomFor[key];
      return;
    }
    measuring = true;
    html.style.zoom = '';
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        var z = window.innerWidth / screen.width;
        zoomFor[key] = z > 1.15 ? String(Math.round(z * 100) / 100) : '';
        measuring = false;
        html.style.zoom = zoomFor[key];
      });
    });
  }

  if (landscape.addEventListener) landscape.addEventListener('change', applyViewport);

  // ---- phone layout ----
  // inject.css does the rearranging; this marks the parts it needs with data-wrap attributes,
  // because Spotify's class names are generated.

  function mark(el, value) {
    if (el && el.getAttribute('data-wrap') !== value) el.setAttribute('data-wrap', value);
  }

  // The children of the layout grid, by the grid area Spotify gave them.
  function markGrid() {
    var grid = q(SEL.grid);
    if (!grid) return;
    mark(grid, 'grid');
    for (var i = 0; i < grid.children.length; i++) {
      var c = grid.children[i];
      // A child placed in one named area has it as both its row and column start; overlay layers
      // that span several areas do not, and are left alone.
      var cs = getComputedStyle(c);
      var area = cs.gridRowStart === cs.gridColumnStart ? cs.gridRowStart : '';
      if (area === 'left-sidebar') mark(c, 'left');
      else if (area === 'right-sidebar') mark(c, 'right');
      else if (area === 'main-view') mark(c, 'main');
      else if (area === 'now-playing-bar') mark(c, 'player');
    }
  }

  function markConnectBar() {
    var bar = q(SEL.playerBar);
    if (!bar) return;
    var walker = document.createTreeWalker(bar, NodeFilter.SHOW_TEXT), n;
    while ((n = walker.nextNode())) {
      if (!CONNECT_BAR.test(n.nodeValue)) continue;
      var el = n.parentElement;
      while (el.parentElement && el.parentElement !== bar) el = el.parentElement;   // the bar's own block
      if (!el.querySelector(SEL.playerControls)) mark(el, 'hide');
      return;
    }
  }

  // The player bar is a row of three: track | controls | extra buttons. The row is the closest
  // element holding both the play controls and the volume control; each part is recognised by
  // what it contains, so wrapper elements Spotify adds (logged in vs out) do not matter.
  function markPlayer() {
    var controls = q(SEL.playerControls), volume = q(SEL.volume);
    if (!controls || !volume) return;
    var row = controls.parentElement;
    while (row && !row.contains(volume)) row = row.parentElement;
    if (!row || !q(SEL.playerBar) || !q(SEL.playerBar).contains(row)) return;
    mark(row, 'np-row');
    for (var i = 0; i < row.children.length; i++) {
      var c = row.children[i];
      if (c.contains(controls)) mark(c, 'np-controls');
      else if (c.contains(volume)) mark(c, 'np-extra');
      else mark(c, 'np-track');
    }
  }

  // In a browser's Desktop site mode Spotify still sees a wide window and adds text links
  // (Premium, Support, Download) and a divider to the top bar; they do not fit a phone. Icon
  // buttons (notifications, friends, your profile) and sign-up / log-in stay.
  function markTopbar() {
    var right = q(SEL.topbarRight);
    if (!right) return;
    var items = right.querySelectorAll('button, a, div');
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (el.parentElement !== right && el.parentElement.parentElement !== right) continue;
      var textOnly = (el.tagName === 'BUTTON' || el.tagName === 'A') && !el.hasAttribute('data-testid') && !el.querySelector('svg, img');
      var divider = el.tagName === 'DIV' && el.children.length === 0;
      if (textOnly || divider) mark(el, 'hide');
    }
    var items = right.querySelectorAll('button, a');
    for (var j = 0; j < items.length; j++) {
      var it = items[j];
      if (it.matches(SEL.topbarKeep) || it.closest(SEL.topbarKeep) || it.querySelector(SEL.topbarKeep)) continue;
      mark(it, 'hide');
    }
  }

  // Library and Now Playing open as full-screen sheets over the page.
  var libraryOpen = false;

  // At phone width Spotify keeps its collapsed-panel button on the page even with the queue open,
  // so a pressed panel button counts first.
  function panelOpen() {
    if (q(SEL.panelToggleOn)) return true;
    var right = q('[data-wrap="right"]');
    return !!(right && right.querySelector(SEL.panel) && !right.querySelector(SEL.panelCollapsed));
  }

  function lyricsOpen() { return !!q(SEL.lyricsOn); }

  function closeLyrics() {
    if (lyricsOpen()) click(SEL.lyricsOn);
  }

  // In a browser, Back walks the history, so an open sheet gets a history step to close it. The
  // app handles Back itself (app.js asks __wrap.back()), and skips the step.
  function sheetStep() {
    if (!(window.__wrap && window.__wrap.appBack)) history.pushState({ wrapSheet: true }, '');
  }

  function setLibrary(open) {
    // Opening adds a history step at the same address, so the phone's Back button closes the
    // sheet (in the app and in the browser alike) instead of leaving the page.
    if (open && !libraryOpen) sheetStep();
    libraryOpen = open;
    html.classList.toggle('wrap-library', open);
    updateNav();
  }

  window.addEventListener('popstate', function () {
    if (lyricsOpen()) { closeLyrics(); return; }
    if (panelOpen()) { closePanel(); return; }
    if (libraryOpen) setLibrary(false);
  });

  // Pressing the panel's own (lit) button again closes it, as a finger would; the panel's close
  // button is the fallback (its test id marks a wrapper, the button is inside).
  function closePanel() {
    if (!panelOpen()) return;
    if (!click(SEL.panelToggleOn)) click(SEL.panelClose);
  }

  // Lyrics and the queue / devices panel open as full-screen sheets; opening one adds a history
  // step at the same address (like the Library sheet), so Back closes it.
  var sheetWas = { lyrics: false, panel: false };
  function trackSheets() {
    var now = { lyrics: lyricsOpen(), panel: panelOpen() };
    html.classList.toggle('wrap-lyrics', now.lyrics);
    html.classList.toggle('wrap-panel', now.panel && !now.lyrics);
    if ((now.lyrics && !sheetWas.lyrics) || (now.panel && !sheetWas.panel)) sheetStep();
    sheetWas = now;
  }

  // Artist banners are wide pictures; Spotify fills a desktop-height header with them ("cover"),
  // which on a narrow phone shows a blown-up middle part. inject.css shows the whole picture at
  // full width instead; this measures its proportions once per picture so the fade into the page
  // starts where the picture ends.
  var bannerSeen = '';
  function fitBanner() {
    var el = q(SEL.banner);
    if (!el) return;
    var m = /url\("?([^")]+)"?\)/.exec(getComputedStyle(el).backgroundImage);
    if (!m || m[1] === bannerSeen) return;
    bannerSeen = m[1];
    var img = new Image();
    img.onload = function () {
      if (img.naturalWidth) el.style.setProperty('--wrap-banner-ratio', String(img.naturalHeight / img.naturalWidth));
    };
    img.src = m[1];
  }

  // ---- bottom tab bar ----

  var NS = 'http://www.w3.org/2000/svg';
  var NAV_ID = '__wrap_nav';
  var TABS = [
    { id: 'home', label: 'Home', icon: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
      active: function () { return !libraryOpen && location.pathname === '/'; },
      go: function () { if (!click(SEL.homeButton)) navigate('/'); } },
    { id: 'search', label: 'Search', icon: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
      active: function () { return !libraryOpen && location.pathname.indexOf('/search') === 0; },
      go: function () {
        if (!click(SEL.browseButton)) navigate('/search');
        var input = q(SEL.searchInput);
        if (input) input.focus();
      } },
    { id: 'downloads', label: 'Downloads', icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm1-13h-2v5.6l-2.3-2.3-1.4 1.4L12 16.4l4.7-4.7-1.4-1.4-2.3 2.3V7z',
      active: function () { return false; },
      go: function () { comingSoon(); } },
    { id: 'library', label: 'Your Library', icon: 'M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z',
      active: function () { return libraryOpen; },
      go: function () { setLibrary(true); } }
  ];

  // Spotify's router listens to popstate; used when its own link is not on the page.
  function navigate(path) {
    if (location.pathname === path) return;
    history.pushState(null, '', path);
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }

  function buildNav() {
    var nav = document.createElement('nav');
    nav.id = NAV_ID;
    nav.setAttribute('aria-label', 'Main');
    TABS.forEach(function (tab) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('data-tab', tab.id);
      var svg = document.createElementNS(NS, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('aria-hidden', 'true');
      var path = document.createElementNS(NS, 'path');
      path.setAttribute('d', tab.icon);
      svg.appendChild(path);
      var label = document.createElement('span');
      label.textContent = tab.label;
      b.appendChild(svg);
      b.appendChild(label);
      b.addEventListener('click', function () {
        closePanel();
        closeLyrics();
        if (tab.id !== 'library') setLibrary(false);
        tab.go();
        updateNav();
      });
      nav.appendChild(b);
    });
    return nav;
  }

  function updateNav() {
    var nav = document.getElementById(NAV_ID);
    if (!nav) return;
    TABS.forEach(function (tab) {
      var b = nav.querySelector('[data-tab="' + tab.id + '"]');
      var on = tab.active();
      b.classList.toggle('active', on);
      if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
    });
  }

  // ---- "Downloads are coming soon" ----

  var MODAL_ID = '__wrap_modal';

  function modalOpen() {
    var m = document.getElementById(MODAL_ID);
    return !!m && m.classList.contains('show');
  }

  function closeModal() {
    var m = document.getElementById(MODAL_ID);
    if (!m) return;
    m.classList.remove('show');
    html.classList.remove('wrap-modal');
  }

  function comingSoon() {
    var m = document.getElementById(MODAL_ID);
    if (!m) {
      m = document.createElement('div');
      m.id = MODAL_ID;
      m.setAttribute('role', 'dialog');
      m.setAttribute('aria-modal', 'true');
      m.setAttribute('aria-labelledby', MODAL_ID + '_title');
      var card = document.createElement('div');
      card.className = 'card';
      var icon = document.createElementNS(NS, 'svg');
      icon.setAttribute('viewBox', '0 0 24 24');
      icon.setAttribute('aria-hidden', 'true');
      var path = document.createElementNS(NS, 'path');
      path.setAttribute('d', TABS.filter(function (t) { return t.id === 'downloads'; })[0].icon);
      icon.appendChild(path);
      var title = document.createElement('h2');
      title.id = MODAL_ID + '_title';
      title.textContent = 'Downloads are coming soon';
      var text = document.createElement('p');
      text.textContent = 'Saving songs and playlists to listen offline is on its way.';
      var ok = document.createElement('button');
      ok.type = 'button';
      ok.textContent = 'Got it';
      ok.addEventListener('click', closeModal);
      card.appendChild(icon);
      card.appendChild(title);
      card.appendChild(text);
      card.appendChild(ok);
      m.appendChild(card);
      // A tap on the dimmed backdrop closes it too.
      m.addEventListener('click', function (e) { if (e.target === m) closeModal(); });
      document.body.appendChild(m);
    }
    // Next frame, so the opening animation runs.
    requestAnimationFrame(function () {
      m.classList.add('show');
      html.classList.add('wrap-modal');
      var b = m.querySelector('button');
      if (b) b.focus();
    });
  }

  // A download button, or a "Download" item in a "..." menu (its text, English).
  function isDownload(target) {
    if (!target || !target.closest) return false;
    if (target.closest(SEL.download)) return true;
    var item = target.closest('[role="menuitem"]');
    return !!item && /^download$/i.test((item.textContent || '').trim());
  }

  var lastDownloadNote = 0;

  // Spotify's download buttons act on the finger lifting (pointerup), not only on click: stop
  // every step of the press before Spotify sees it (capture phase), and show the note instead.
  ['pointerdown', 'mousedown', 'touchstart', 'pointerup', 'mouseup', 'touchend', 'click'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (!mobile || !isDownload(e.target)) return;
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      // Blocking the finger going down also stops the browser from making a click afterwards, so
      // the note opens when the finger lifts (or on a click from a mouse or keyboard); once per tap.
      if (type === 'pointerup' || type === 'touchend' || type === 'mouseup' || type === 'click') {
        var now = Date.now();
        if (now - lastDownloadNote > 600) { lastDownloadNote = now; comingSoon(); }
      }
    }, true);
  });

  // ---- keeping it all in place ----

  function isTextField(el) {
    return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
  }

  var lastPath = location.pathname;

  function layout() {
    // Spotify's full-screen view covers everything: show it as Spotify made it, fitted to the
    // screen (no phone layout, no zoom), and come back when it closes.
    var on = mobile && !!q(SEL.desktopPage) && !videoFullscreen();
    html.classList.toggle('wrap-mobile', on);
    if (!on) {
      if (mobile && html.style.zoom) html.style.zoom = '';
      return;
    }
    if (!document.body) return;
    fitZoom();
    markGrid();
    markPlayer();
    markConnectBar();
    markTopbar();
    fitBanner();
    trackSheets();
    if (!document.getElementById(NAV_ID)) document.body.appendChild(buildNav());
    // Picking a playlist in the Library opens it: close the sheet.
    if (location.pathname !== lastPath) {
      lastPath = location.pathname;
      if (libraryOpen) setLibrary(false);
    }
    updateNav();
  }

  if (mobile) {
    document.addEventListener('focusin', function (e) {
      if (isTextField(e.target)) html.classList.add('wrap-typing');
    });
    document.addEventListener('focusout', function () {
      html.classList.remove('wrap-typing');
    });
  }

  // Spotify's full-screen button makes the whole page full screen: keep the phone layout then (the
  // app hides Android's bars). Only something else full screen, such as a video, gets the screen
  // as Spotify made it.
  function videoFullscreen() {
    var fs = document.fullscreenElement;
    return !!fs && fs !== document.body && fs !== html;
  }

  // Spotify rebuilds parts of the page all the time (play/pause swaps the player's controls) and
  // the marks go with the old elements. The observer puts them back right after the change, before
  // the screen is drawn again, so the layout never visibly breaks; when nothing is missing it costs
  // a few lookups.
  function marksMissing() {
    var grid = q(SEL.grid);
    if (grid && grid.getAttribute('data-wrap') !== 'grid') return true;
    var bar = q(SEL.playerBar);
    if (bar) {
      var row = bar.querySelector('[data-wrap="np-row"]');
      if (!row) return true;
      for (var i = 0; i < row.children.length; i++) if (!row.children[i].hasAttribute('data-wrap')) return true;
    }
    if (bar && CONNECT_BAR.test((bar.textContent || '').replace(/^[^]*?(?=Playing on)/, '')) && !bar.querySelector('[data-wrap="hide"]')) return true;
    return !!document.body && !document.getElementById(NAV_ID);
  }

  function watchPage() {
    if (!mobile || !document.body) return;
    new MutationObserver(function () {
      // Also on navigation: a playlist picked in the Library closes the sheet, the tab highlight moves.
      if (!videoFullscreen() && (location.pathname !== lastPath || marksMissing())) layout();
    }).observe(document.body, { childList: true, subtree: true });
    document.addEventListener('fullscreenchange', layout);
  }

  var scheduled = false;
  function refresh() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      applyViewport();
      applyStyle();
      layout();
    });
  }

  function setup() {
    applyViewport();
    applyStyle();
    layout();
    // The player is a single-page app that edits <head> while you use it.
    new MutationObserver(refresh).observe(document.head, { childList: true });
    watchPage();
  }

  if (document.head) setup();
  else document.addEventListener('DOMContentLoaded', setup, { once: true });

  // ---- what is playing ----

  function text(selector) {
    var el = q(selector);
    return el ? el.textContent.trim() : '';
  }

  function readState() {
    var ms = navigator.mediaSession;
    var md = ms && ms.metadata;
    var title = (md && md.title) || text(SEL.title);
    var artist = (md && md.artist) || text(SEL.artist);
    var playing = false;
    if (ms && ms.playbackState === 'playing') {
      playing = true;
    } else if (!ms || ms.playbackState !== 'paused') {
      var media = document.querySelectorAll('audio, video');
      for (var i = 0; i < media.length; i++) {
        if (!media[i].paused && !media[i].ended) playing = true;
      }
    }
    return { title: title, artist: artist, playing: playing, art: readArt(md) };
  }

  // The biggest cover Spotify offers (the notification wants a sharp one), or the one on screen.
  function readArt(md) {
    var best = '', bestSize = -1;
    var list = (md && md.artwork) || [];
    for (var i = 0; i < list.length; i++) {
      var size = parseInt(String(list[i].sizes || '').split('x')[0], 10) || 0;
      if (list[i].src && size >= bestSize) { best = list[i].src; bestSize = size; }
    }
    if (!best) {
      var img = q(SEL.cover);
      best = (img && (img.currentSrc || img.src)) || '';
    }
    return best.indexOf('https:') === 0 ? best : '';
  }

  var last = '';
  function report() {
    if (!window.WrapBridge) return;   // not inside the app (e.g. opened in a desktop browser)
    var s = readState();
    var key = s.title + '\u0001' + s.artist + '\u0001' + s.playing + '\u0001' + s.art;
    if (key === last) return;
    last = key;
    window.WrapBridge.nowPlaying(s.title, s.artist, s.playing, s.art);
  }
  // Diagnostics, only in a build made with `build-userscript.py --debug`: a small label showing
  // what the script sees (for places like an installed web app window, where devtools are out of
  // reach).
  function debugLabel() {
    if (!window.__WRAP_DEBUG__ || !document.body) return;
    var d = document.getElementById('__wrap_debug');
    if (!d) {
      d = document.createElement('div');
      d.id = '__wrap_debug';
      d.style.cssText = 'position:fixed;top:0;left:0;z-index:2147483647;background:#ff0;color:#000;font:11px monospace;padding:2px 4px;pointer-events:none;white-space:pre';
      document.body.appendChild(d);
    }
    d.textContent = 'wrap ' + (window.__WRAP_VERSION__ || '') + ' phone=' + phone + ' coarse=' + matchMedia('(pointer: coarse)').matches +
      ' scr=' + screen.width + 'x' + screen.height + ' win=' + innerWidth + 'x' + innerHeight + ' dpr=' + devicePixelRatio +
      '\ndesktopPage=' + !!q(SEL.desktopPage) + ' on=' + html.classList.contains('wrap-mobile') + ' zoom=' + (html.style.zoom || '-') +
      ' standalone=' + matchMedia('(display-mode: standalone)').matches + ' ' + navigator.userAgent.replace(/.*\(([^)]*)\).*/, '$1');
  }

  // The same tick keeps the layout marks in place: Spotify re-renders parts of the page.
  setInterval(function () { applyStyle(); report(); debugLabel(); }, POLL_MS);
  // A slow safety net for what the observer cannot see (the address changing, panels opening).
  setInterval(layout, 2000);

  // ---- called by the app ----

  window.__wrap = {
    playPause: function () { return click(SEL.playPause); },
    next: function () { return click(SEL.next); },
    previous: function () { return click(SEL.previous); },
    // Android back: closes a sheet if one is open. Returns true when it did.
    back: function () {
      if (modalOpen()) { closeModal(); return true; }
      if (libraryOpen) { setLibrary(false); return true; }
      if (lyricsOpen()) { closeLyrics(); return true; }
      if (panelOpen()) { closePanel(); return true; }
      return false;
    },
    state: readState,
    // Goes to another page of the player without reloading it; true when it did something.
    goto: function (path) {
      if (!path || location.pathname === path) return false;
      navigate(path);
      return true;
    },
    sel: SEL
  };
})();
