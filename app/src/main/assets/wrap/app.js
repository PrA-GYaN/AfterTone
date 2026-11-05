// App only (the built-in extension, not the userscript): tells MainActivity when an ad plays and
// when Spotify says it can't play, through GeckoView's native messaging. MainActivity decides what
// to do (skip the ad by restarting the player, mute it, restart after an error), and after a
// restart this script presses play again. Selectors come from SEL in inject.js.
(function () {
  'use strict';
  if (typeof browser === 'undefined' || !browser.runtime || !browser.runtime.sendNativeMessage) return;

  var APP = 'wrap';   // must match MainActivity.NATIVE_APP
  // Spotify's "can't play" message (feedback.track-not-available / track-exclusive-premium). Shown
  // for a moment in a pop-up; restarting the player usually clears it. English only for now.
  var CANT_PLAY = /can.t play this right now/i;

  function send(msg) {
    return browser.runtime.sendNativeMessage(APP, msg).catch(function (e) {
      console.log('wrap: native message failed ' + e);
      return null;
    });
  }

  function log(text) { send({ type: 'log', text: String(text) }); }

  function sel(name) { return window.__wrap && window.__wrap.sel[name]; }
  function q(name) { var s = sel(name); return s ? document.querySelector(s) : null; }

  // Ads: several independent signals, so one Spotify change does not hide an ad.
  //   player: the ad-only markup of the player (any language)
  //   media:  the media session's title or artist is Spotify's ad label ("Advertisement • 1 of 3")
  //   link:   the now-playing link points at an ad, not a track
  var AD_LABEL = /^(advertisement|anuncio|anúncio|publicidad|werbung|publicité|pubblicità|advertentie|reklam|reklama|mainos|annonse|annons|iklan|quảng cáo)([^a-z0-9].*)?$/i;
  function adSignals() {
    var why = [];
    if (q('adTitle') || q('adSubtitle') || q('adCountdown')) why.push('player');
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    if (md && (AD_LABEL.test(String(md.title || '').trim()) || AD_LABEL.test(String(md.artist || '').trim()))) why.push('media');
    var link = q('title');
    var href = link ? String(link.getAttribute('href') || '') : '';
    if (/(^|\/)ad\/|spotify:ad:/.test(href)) why.push('link');
    return why;
  }
  function adPlaying() { return adSignals().length > 0; }

  // "0:12", "1:05", "-0:12" or "12 s" -> seconds; -1 when there is no time in the text.
  function seconds(text) {
    var t = String(text || '');
    var m = /(\d+):(\d{2})/.exec(t);
    if (m) return +m[1] * 60 + +m[2];
    m = /(\d+)\s*s\b/i.exec(t);
    return m ? +m[1] : -1;
  }

  // How long the current ad still has: Spotify's ad countdown if shown, else the player's own
  // duration minus position (both are the ad's while it plays). -1 when unknown, then the app
  // treats the ad as a long one, as before.
  function adLeft() {
    var c = q('adCountdown');
    var v = c ? seconds(c.textContent) : -1;
    if (v >= 0) return v;
    var p = seconds(clock()), d = seconds((q('duration') || {}).textContent);
    return p >= 0 && d > 0 && d >= p ? d - p : -1;
  }

  // More ads follow in this break? ("Advertisement • 1 of 3": n of m with n < m.) A restart drops
  // the whole break, so a short ad that is not the last of its break is still worth restarting for.
  function adMore() {
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    var sub = q('adSubtitle');
    var text = [md && md.artist, md && md.title, sub && sub.textContent].join(' ');
    var m = /(\d+)\s*(?:of|\/)\s*(\d+)/i.exec(text);
    return !!m && +m[1] < +m[2];
  }

  // Where the play button is, so the app can lay its loading spinner over it during a restart and
  // touch it to resume.
  function playRect() {
    var b = q('playPause');
    if (!b) return null;
    var r = b.getBoundingClientRect();
    return { x: r.left, y: r.top, w: r.width, h: r.height, dpr: window.devicePixelRatio || 1 };
  }

  // The matching message text ('' when none is showing).
  function cantPlayShown() {
    var s = sel('messages');
    if (!s) return '';
    var nodes = document.querySelectorAll(s);
    for (var i = 0; i < nodes.length; i++) {
      var t = nodes[i].textContent;
      if (CANT_PLAY.test(t)) return t.trim().slice(0, 80);
    }
    return '';
  }

  // null: report the state once at start, so the app never keeps an ad state (or its mute) from
  // the page it restarted away from.
  var adOn = null, errorSince = 0, errorSent = false, rectSent = '', leftSent = -2, adFirst = 0;
  // The spare player (a second copy of the page the app keeps ready under the visible one) stays
  // quiet: it reports nothing about ads or errors, only that it is ready.
  var spareMode = false, activated = false;
  // After a swap the engine itself says when audio starts (a media-session play event, which the
  // mirrored "Pause" of the old player cannot fake): the app passes it on.
  var audioStarted = false;
  function check() {
    if (!window.__wrap || spareMode) return;
    var why = adSignals();
    var ad = why.length > 0;
    // An ad has to stay for 0.4 s before it counts: a page being rebuilt (switching apps, a track
    // change) can show ad-like markup for a moment, and ads only ever come between songs.
    if (ad && adOn !== true) {
      if (!adFirst) adFirst = Date.now();
      if (Date.now() - adFirst < 400) ad = adOn === true;   // not yet
    } else if (!ad) adFirst = 0;
    if (ad !== adOn) {
      adOn = ad;
      leftSent = ad ? adLeft() : -2;
      send({ type: 'ad', on: ad, why: why.join('+'), rect: playRect(), left: leftSent, more: ad ? adMore() : false });
    } else if (ad && leftSent < 0) {
      // The ad's length often shows a moment after the ad itself: tell the app once it does.
      var left = adLeft();
      if (left >= 0) { leftSent = left; send({ type: 'adLeft', left: left, more: adMore() }); }
    }
    // "Can't play this right now": counts only if the message is still there after 1.5 s; the app
    // ignores it when music is playing (it can be about another track).
    var err = cantPlayShown();
    if (!err) { errorSince = 0; errorSent = false; }
    else if (!errorSince) errorSince = Date.now();
    else if (!errorSent && Date.now() - errorSince > 1500) {
      errorSent = true;
      send({ type: 'cantPlay', text: err, playing: playingNow() });
    }
    var r = playRect(), key = r ? [r.x, r.y, r.w, r.h].map(Math.round).join(',') : '';
    if (key !== rectSent) { rectSent = key; if (r) send({ type: 'rect', rect: r }); }
  }
  // Four times a second, and at once whenever the player changes (an ad swaps its contents).
  setInterval(check, 250);
  var checkQueued = false;
  new MutationObserver(function () {
    if (checkQueued) return;
    checkQueued = true;
    requestAnimationFrame(function () { checkQueued = false; check(); });
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });

  // Back: the app asks this page to close an open sheet (Library, lyrics, queue / devices) before
  // it goes back or leaves. A port, so the app can ask and the page can say what is open.
  window.__wrap && (window.__wrap.appBack = true);
  var port = null;
  try {
    port = browser.runtime.connectNative(APP);
    port.onMessage.addListener(function (m) {
      if (m && m.type === 'back') port.postMessage({ type: 'backResult', closed: !!(window.__wrap && window.__wrap.back()) });
      if (m && m.type === 'activate') activate(m.path);
      if (m && m.type === 'audioStarted') { audioStarted = true; log('the engine reports audio started'); }
      if (m && m.type === 'goto' && spareMode && window.__wrap && window.__wrap.goto && window.__wrap.goto(m.path)) log('spare: followed the player to ' + m.path);
    });
  } catch (e) { console.log('wrap: no port ' + e); }

  var sheetWasOpen = null;
  new MutationObserver(function () {
    var c = document.documentElement.classList;
    var open = c.contains('wrap-library') || c.contains('wrap-lyrics') || c.contains('wrap-panel') || c.contains('wrap-modal');
    if (open === sheetWasOpen || !port) return;
    sheetWasOpen = open;
    port.postMessage({ type: 'sheet', open: open });
  }).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });

  // Tell the app when the phone layout is on, so it can take its splash screen away without ever
  // showing Spotify's desktop page.
  // Ready = layout on and Spotify's page filled in (its pictures have arrived), so the splash does
  // not give way to an empty page; at most 4 s after the layout is on.
  var layoutSince = 0;
  var readyTimer = setInterval(function () {
    var h = document.documentElement;
    if (!h.classList.contains('wrap-mobile') || !document.getElementById('__wrap_nav')) return;
    if (!layoutSince) layoutSince = Date.now();
    var main = document.getElementById('main-view');
    var filled = main && main.querySelector('img') && q('playPause') && !q('playPause').disabled;
    if (!filled && Date.now() - layoutSince < 4000) return;
    clearInterval(readyTimer);
    // Two frames: the layout is drawn, not just applied.
    requestAnimationFrame(function () { requestAnimationFrame(function () { send({ type: 'ready' }); }); });
  }, 100);

  // After the app restarted the player: press play once Spotify's player is ready, then make sure
  // the music really runs (the elapsed-time clock moves). A click from a script can be refused (no
  // finger on the screen); then ask the app to send Play as a media key, which always counts, and
  // try again a few times.
  function clock() { var c = q('position'); return c ? c.textContent : ''; }
  function playLabelSaysPlaying(b) { return /^pause$/i.test(b.getAttribute('aria-label') || ''); }

  // Playing right now? Answers at once from the play button's label or the page's media session;
  // the elapsed-time clock is only the fallback, sampled over a short window.
  function playingNow() {
    var b = q('playPause');
    return !!((b && playLabelSaysPlaying(b)) || (navigator.mediaSession && navigator.mediaSession.playbackState === 'playing'));
  }

  // Wait up to `ms` for the music to start; checks every 150 ms, so a start is seen within a fraction
  // of a second (the app unmutes as soon as it hears back).
  // Forward movement of the clock: at least a second, at most a few (a jump back to 0:01 when
  // the state resets, or a change of song, is not playing).
  function forward(before, now) {
    var a = seconds(before), n = seconds(now);
    return a >= 0 && n >= a + 1 && n <= a + 8;
  }

  function waitForPlaying(ms, done) {
    var started = Date.now(), before = clock();
    var timer = setInterval(function () {
      var now = clock();
      // After taking over as the spare the label alone is not believed (the page mirrors the old
      // player for a moment): the clock must move forward as well.
      var ok = activated ? (audioStarted || (playingNow() && forward(before, now))) : (playingNow() || (now !== before && now !== ''));
      if (ok) { clearInterval(timer); done(true); return; }
      if (Date.now() - started > ms) { clearInterval(timer); done(false); }
    }, 150);
  }

  // After a swap the song can already be a few seconds in. Spotify's Previous button restarts the
  // song when it is more than 3 s in (and goes to the earlier song below that), so from 3 s on one
  // press brings it back to the start before play is pressed; sooner than that it is left alone.
  // Presses right after a swap are often ignored for the first seconds (as with Play), so it is
  // tried again every 2 s, but only while the song is still 3 s or more in: a late press that has
  // already worked must never be followed by another one (that would go to the earlier song).
  var REWIND_FROM_S = 3, REWIND_TRIES = 4, REWIND_GAP_MS = 2000;
  function rewind(done) {
    var t0 = Date.now(), first = seconds(clock());
    (function press(n) {
      var pos = seconds(clock()), b = q('previous');
      if (!activated || pos < 0 || adPlaying()) { done(); return; }
      if (pos < REWIND_FROM_S) {
        if (n > 1) log('resume: back at ' + pos + ' s after ' + (Date.now() - t0) + ' ms');
        done(); return;
      }
      if (n > REWIND_TRIES || !b || b.disabled) { log('resume: previous did not rewind the song (still at ' + pos + ' s)'); done(); return; }
      var r = b.getBoundingClientRect();
      log('resume: the song is ' + pos + ' s in, pressing previous to start it over (try ' + n + ')');
      send({ type: 'tap', x: r.left + r.width / 2, y: r.top + r.height / 2, dpr: window.devicePixelRatio || 1 });
      setTimeout(function () { press(n + 1); }, REWIND_GAP_MS);
    })(1);
  }

  function resume() {
    var started = Date.now();
    log('resume: waiting for the player');
    var wait = setInterval(function () {
      var b = q('playPause');
      if (Date.now() - started > 40000) { clearInterval(wait); log('resume: player never became ready'); send({ type: 'resumeFailed' }); return; }
      if (!b || b.disabled) return;
      clearInterval(wait);
      log('resume: player ready after ' + (Date.now() - started) + ' ms');
      // A moment for the player to register as this device before pressing.
      setTimeout(function () { rewind(function () { attempt(1); }); }, 300);
    }, 150);
  }

  // Each try: done if the music runs (and it is not an ad). Otherwise touch the play button for real
  // (the app turns it into a finger tap, never refused as autoplay), alternating with a click, and
  // watch for the music to start.
  // Is the clock moving? Sampled over `ms`.
  function movingFor(ms, done) {
    var before = clock();
    setTimeout(function () { done(audioStarted || (playingNow() && forward(before, clock()))); }, ms);
  }

  function attempt(n) {
    dismissListenDialog();
    if (adPlaying()) { log('resume: an ad is playing, the app takes care of it'); return; }
    if (activated && audioStarted) { confirmed(n); return; }
    if (playingNow()) {
      if (!activated) { confirmed(n); return; }
      // A swapped-in page still mirrors the old player ("Pause") for a moment after that player
      // is gone. Wait for the label to flip to Play (then press at once), or for the engine to
      // report audio, or for the clock to really move.
      var t0 = Date.now(), before = clock();
      var flip = setInterval(function () {
        var b2 = q('playPause');
        if (audioStarted || (b2 && playLabelSaysPlaying(b2) && Date.now() - t0 > 1400 && forward(before, clock()))) { clearInterval(flip); confirmed(n); return; }
        if (!b2 || !playLabelSaysPlaying(b2)) { clearInterval(flip); attempt(n); return; }   // says Play now: press
        if (Date.now() - t0 > 3000) { clearInterval(flip); log('resume: the label says playing but nothing moves (try ' + n + ')'); attempt(n + 1); }
      }, 120);
      return;
    }
    if (n > (activated ? 10 : 5)) { log('resume: gave up after ' + (activated ? 10 : 5) + ' tries'); send({ type: 'resumeFailed' }); return; }
    var b = q('playPause');
    if (b && !b.disabled) {
      var r = b.getBoundingClientRect();
      // After a swap only real touches ever worked (scripted clicks never did), so only those.
      if (activated || n % 2 === 1) {
        send({ type: 'tap', x: r.left + r.width / 2, y: r.top + r.height / 2, dpr: window.devicePixelRatio || 1 });
        log('resume: touched play (try ' + n + ')');
      } else {
        b.click();
        send({ type: 'resumeStalled', attempt: n });
        log('resume: clicked play (try ' + n + ')');
      }
    }
    // After taking over as the spare the first try is given less time: the player is already
    // registered, so a press that is going to work works at once.
    // A press can take a second or more to show (a press landing just as playback starts would
    // pause it again), so after a swap the tries are spaced out.
    waitForPlaying(activated ? 2200 : 3000, function (ok) {
      if (ok) confirmed(n); else attempt(n + 1);
    });
  }

  function confirmed(n) {
    if (adPlaying()) { log('resume: an ad started, the app takes care of it'); return; }
    log('resume: playing (try ' + n + ')');
    send({ type: 'resumed' });
  }

  // Spotify asks "You are listening on <another player>" when a second player starts while one is
  // playing, and the question covers the play button. "Listen on this phone" is the answer that
  // moves playback here ("Continue" would keep it on the other player).
  function dismissListenDialog() {
    var btns = document.querySelectorAll('[role="dialog"] button, [role="dialog"] a, [role="dialog"] [role="button"]');
    for (var i = 0; i < btns.length; i++) {
      if (/^listen on this/i.test((btns[i].textContent || '').trim())) {
        btns[i].click();
        log('dismissed the "listening on another device" question');
        return true;
      }
    }
    return false;
  }

  // The spare: report when its player is ready (its play button works), then wait for the app.
  function startSpare() {
    spareMode = true;
    document.documentElement.setAttribute('data-wrap-spare', '1');   // for telling the two pages apart when debugging
    var started = Date.now(), readySince = 0, told = false;
    log('spare: started');
    var timer = setInterval(function () {
      if (!spareMode) { clearInterval(timer); return; }
      var b = q('playPause');
      if (!b || b.disabled) { readySince = 0; return; }
      if (!readySince) readySince = Date.now();
      if (!told && Date.now() - readySince > 700) {   // steady for a moment, not just flickering on
        told = true;
        clearInterval(timer);
        log('spare: player ready after ' + (Date.now() - started) + ' ms');
        send({ type: 'spareReady' });
      }
    }, 150);
  }

  // The app swapped this page in as the player: go to the page that was open, then press play.
  function activate(path) {
    spareMode = false;
    document.documentElement.removeAttribute('data-wrap-spare');
    activated = true;
    adOn = null; adFirst = 0; leftSent = -2; errorSince = 0; errorSent = false; audioStarted = false;
    log('activated, going to ' + path);
    if (window.__wrap && window.__wrap.goto && window.__wrap.goto(path)) {
      // The single-page navigation did not take: load the address instead (slower).
      setTimeout(function () { if (location.pathname !== path) location.assign(path); }, 2000);
    }
    resume();
  }

  send({ type: 'hello' }).then(function (reply) {
    if (reply === 'resume') resume();
    else if (reply === 'spare') startSpare();
  });
})();
