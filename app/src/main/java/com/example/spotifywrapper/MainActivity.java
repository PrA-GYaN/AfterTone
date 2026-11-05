package com.example.spotifywrapper;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;

// One full-screen GeckoView (Firefox's engine) on open.spotify.com in desktop-site mode, the same
// mode as Firefox's "Desktop site". The phone layout is a built-in extension (assets/wrap/: the
// same inject.css / inject.js as the userscript). PlaybackService keeps the app alive and shows
// the media notification, fed by the page's media session. The extension's app.js reports ads and
// "can't play" errors here (native messaging); ads are muted at once and skipped by restarting the
// player, and stay muted when a restart does not get rid of them.
public class MainActivity extends Activity {
    static final String TAG = "SpotifyWrapper";
    static final String HOME = "https://open.spotify.com/";

    // Pages in this list load inside the app; every other http(s) link opens in the browser and
    // other schemes (spotify:, intent:, market:) are ignored. Google, Facebook and Apple are here
    // for their "log in with" flows.
    private static final String[] ALLOWED_HOSTS = {"spotify.com", "scdn.co", "spotifycdn.com", "google.com", "facebook.com", "apple.com"};
    private static final String SPOTIFY_HOST = "spotify.com";
    private static final String EXTENSION_URL = "resource://android/assets/wrap/", EXTENSION_ID = "wrap@spotifywrapper";

    private static final String NATIVE_APP = "wrap";   // must match APP in assets/wrap/app.js
    private static final int REQ_NOTIFICATIONS = 1, REQ_FILE = 2;

    // Restarting the player to get past an error: at most this many in this window, so a track
    // that really cannot play does not loop. Ad restarts have their own limit (MAX_AD_TRIES).
    private static final int MAX_RESTARTS = 4;
    private static final long RESTART_WINDOW_MS = 3 * 60 * 1000L;
    // Ads: muted at once, then after this long (the ad running silently) the player restarts,
    // which drops the break. Up to MAX_AD_TRIES restarts per break; an ad seen again within
    // AD_BREAK_GAP_MS of the last one belongs to the same break.
    private static final long AD_GRACE_MS = 1000;
    private static final int MAX_AD_TRIES = 3;

    // Two players: the visible one and a hidden, fully started spare that takes over when an ad
    // comes (the old one is closed and a new spare is started in the background). Starting a player
    // from scratch takes about 7 s, so the swap is much quicker than a reload; if there is no
    // spare, or the swap does not bring the music back, the app reloads as before.
    private static final boolean USE_SPARE = true;
    private static final long SPARE_MAX_AGE_MS = 25 * 60 * 1000L;   // started again after this long
    private static final long SPARE_AFTER_READY_MS = 3000;           // the spare starts this long after the player is ready
    private static final long SWITCH_TIMEOUT_MS = 12_000;            // music not back this long after a swap: reload
    private static final long AD_BREAK_GAP_MS = 60 * 1000L;
    // Safety: never leave the phone muted longer than this if the ad's end is missed.
    private static final long MAX_MUTE_MS = 3 * 60 * 1000L;
    // Labels Spotify gives ads in the media session (a fallback to app.js's checks).
    private static final String[] AD_LABELS = {"advertisement", "anuncio", "anúncio", "publicidad", "werbung",
            "publicité", "pubblicità", "advertentie", "reklam", "reklama", "mainos", "annonse", "annons", "iklan", "quảng cáo"};

    // Shown when the page cannot load. "Try again" and the network coming back both reload the
    // page that failed. %s is that address.
    private static final String ERROR_HTML = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "background:#121212;color:#fff;font:16px/1.5 sans-serif;text-align:center;padding:0 32px;box-sizing:border-box;"
            + "-webkit-user-select:none;user-select:none}"
            + ".icon{width:72px;height:72px;margin-bottom:24px;opacity:.9}"
            + "h2{margin:0 0 8px;font-size:22px}p{margin:0 0 32px;color:#b3b3b3}"
            + "a{font:600 16px sans-serif;padding:14px 36px;border-radius:28px;background:#1ed760;color:#000;text-decoration:none}"
            + "</style>"
            + "<svg class=icon viewBox='0 0 24 24' fill='none' stroke='#1ed760' stroke-width='2' stroke-linecap='round'>"
            + "<path d='M2 8.8a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0'/><path d='M3 3l18 18'/>"
            + "<circle cx='12' cy='19.5' r='.8' fill='#1ed760'/></svg>"
            + "<h2>Can't reach Spotify</h2><p>Check your connection.<br>This page reloads by itself when you're back online.</p>"
            + "<a href='%s'>Try again</a>";

    // One engine per process, kept across activity restarts (it holds the login and cache).
    private static GeckoRuntime runtime;
    private static WebExtension extension;   // the built-in layout extension, once loaded

    private FrameLayout root;
    private GeckoView view;
    private GeckoSession session;
    // The spare player (hidden under the visible one), once started.
    private GeckoView spareView;
    private GeckoSession spare;
    private WebExtension.Port sparePort;
    private MediaSession spareMedia;
    private boolean spareReady;          // started and its play button is usable
    private long spareCreatedAt, spareBlockedUntil;
    private boolean switching;           // a swap is in progress
    private boolean coverUntilResumed;   // after a swap the cover stays until the music is back
    private final Runnable switchCheck = () -> {
        if (this.playing) return;
        diag("switch: music not back " + SWITCH_TIMEOUT_MS / 1000 + " s after the swap, reloading instead");
        coverUntilResumed = false;
        restartPlayer(true, "ad, swap failed");
    };
    private ProgressBar progress;
    private FrameLayout splash;   // covers the page until the phone layout is on (assets/wrap/app.js "ready")
    private AnimatedVectorDrawable splashWave;
    private boolean fullScreen;
    private String loadingUrl = HOME;
    // The splash never stays longer than this, whatever the page does.
    private static final long SPLASH_MAX_MS = 12_000;
    private final Runnable splashTimeout = this::hideSplash;
    private boolean canGoBack;
    private String retryUrl = HOME;
    private boolean showingError;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WebExtension.Port pagePort;   // app.js's port: Back closes its sheets first
    private boolean sheetOpen;
    private String pageUrl = HOME;   // where the player is, to come back after a restart
    // The spare follows the visible player to the page it is on whenever the song changes (the
    // song itself it already shows: both are players on the same account), so a swap shows no
    // page change.
    private final Runnable followRun = this::followWithSpare;

    // Restarts and ads.
    private boolean resumeAfterRestart;
    private final long[] restarts = new long[MAX_RESTARTS];
    private int restartIndex;
    private boolean adPlaying, muted;
    private int adTries;                 // restarts for the current ad break
    private long adSeenAt;               // last time an ad was reported
    private boolean holdMute;            // after an ad restart: muted until the music is confirmed
    private boolean restarting;          // between a restart and the new page saying hello
    // An ad with less time left than a restart takes (about 11 s) is quicker to sit out, muted, than
    // to restart for; unless more ads follow in the same break, which a restart would drop too.
    private static final int SHORT_AD_S = 12;
    private int adLeft = -1;             // seconds the ad has left when seen; -1 unknown
    private boolean adMore;              // more ads follow in this break
    private boolean sparable() { return adLeft >= 0 && adLeft < SHORT_AD_S && !adMore; }
    private boolean visible;             // the screen is on and the app is in front
    private final Runnable adRestart = () -> {
        if (!adPlaying) return;
        if (sparable()) {
            diag("ad: only " + adLeft + " s left, waiting it out muted (no restart)");
            if (adTries > 0) adTries--;   // no restart happened: it does not count against the break
            return;
        }
        if (switchToSpare()) return;
        restartPlayer(true, "ad, try " + adTries + " of " + MAX_AD_TRIES);
    };

    // While the player restarts, a still picture of the screen stays up (cover), with a spinner
    // where the play button is, so the restart looks like the next song loading.
    private FrameLayout cover;
    private ImageView coverShot;
    private View coverSpinner;
    private JSONObject playRect;         // the play button, from app.js (CSS px + devicePixelRatio)
    private final Runnable coverTimeout = this::hideCover;
    private final Runnable unmuteSafety = () -> setMuted(false);
    // After a restart that should resume: if the music is still not playing by then, log it and
    // send Play as a media key (app.js normally has it playing within a few seconds).
    private static final long RESUME_CHECK_MS = 25_000;
    private long restartedAt;
    private final Runnable resumeCheck = () -> {
        if (this.playing) { releaseHeldMute(); return; }
        diag("resume: still not playing " + RESUME_CHECK_MS / 1000 + " s after the restart, sending Play");
        playByMediaKey();
        releaseHeldMute();   // never leave the sound off because a resume failed
    };

    private MediaSession media;   // the page's media session (Spotify's player), when there is one
    private boolean playing;

    private GeckoSession.PromptDelegate.FilePrompt filePrompt;   // pending <input type=file>
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;

    // ---- diagnostics: a small log file that survives long enough to explain a past restart ----

    private static final long DIAG_MAX_BYTES = 200 * 1024;

    // Logcat keeps only a few minutes; this file keeps the last stretch of decisions (restarts and
    // their reasons, ads, errors, the screen's lifecycle). Debug builds: `adb shell run-as
    // io.github.pragyan.aftertone cat files/aftertone.log`.
    private void diag(String line) {
        Log.i(TAG, line);
        try {
            java.io.File f = new java.io.File(getFilesDir(), "aftertone.log");
            if (f.length() > DIAG_MAX_BYTES) {
                java.io.File old = new java.io.File(getFilesDir(), "aftertone.old.log");
                old.delete();
                f.renameTo(old);
            }
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new java.util.Date())
                        + " " + line + "\n");
            }
        } catch (java.io.IOException | RuntimeException ignored) {}
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        diag("lifecycle: onStart");
    }
    @Override protected void onResume() { super.onResume(); diag("lifecycle: onResume, playing=" + playing); }
    @Override protected void onStop() { super.onStop(); visible = false; diag("lifecycle: onStop, playing=" + playing); }
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        diag("lifecycle: configuration changed (handled, no reload)");
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        diag("lifecycle: onTrimMemory level " + level);
        // The phone is short of memory: the spare (a whole second page) goes first.
        if (level >= 10 && level < 20 && spare != null) {
            dropSpare("low memory");
            spareBlockedUntil = SystemClock.elapsedRealtime() + 10 * 60 * 1000L;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        diag("lifecycle: onCreate (saved state " + (state != null ? "yes: the screen was recreated" : "no: fresh start") + ")");
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#121212"));
        view = new GeckoView(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.wrap_accent)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3 * getResources().getDisplayMetrics().density), Gravity.TOP));
        cover = buildCover();
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets();
        showSplash();

        // The engine starts after the first frame (the splash) is on screen: starting it takes a
        // moment on the main thread, and the first thing you see should be the app, not a blank
        // window.
        Choreographer.getInstance().postFrameCallback(t -> root.post(() -> startEngine(debuggable)));

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);

        PlaybackService.setRemote(remote);
        startForegroundService(new Intent(this, PlaybackService.class));
        watchNetwork();
    }

    private void startEngine(boolean debuggable) {
        if (isDestroyed()) return;
        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext(), new GeckoRuntimeSettings.Builder()
                    // Spotify logs a lot; copying it all to logcat costs time on every message.
                    .consoleOutput(false)
                    .remoteDebuggingEnabled(debuggable)
                    .build());
            runtime.getWebExtensionController().ensureBuiltIn(EXTENSION_URL, EXTENSION_ID)
                    .accept(ext -> {
                        diag("layout extension ready");
                        extension = ext;
                        runOnUiThread(this::listenToExtension);
                    }, e -> Log.e(TAG, "layout extension failed", e));
        }


        session = makeSession();
        view.setSession(session);
        // GeckoView paints white until the page's first frame; keep it the app's dark instead.
        view.coverUntilFirstPaint(getColor(R.color.wrap_bg));
        listenToExtension();

        session.loadUri(startUrl(getIntent()));
    }

    // A session with the app's settings and delegates, already open (the visible player and the
    // spare are made the same way).
    private GeckoSession makeSession() {
        GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder()
                // Desktop identity, as in Firefox's "Desktop site" (that is what Spotify judges), but
                // the phone's own viewport: the page is laid out at the phone's width and pixel
                // density and obeys its no-zoom viewport tag. The desktop viewport (980px wide,
                // zoomed to fit) blurred pictures, misplaced pop-up menus, allowed pinch-zoom and
                // zoomed into the search box.
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());
        s.setNavigationDelegate(navigation);
        s.setProgressDelegate(progressDelegate);
        s.setContentDelegate(content);
        s.setPermissionDelegate(permissions);
        s.setPromptDelegate(prompts);
        s.setMediaSessionDelegate(mediaDelegate);
        s.open(runtime);
        return s;
    }

    // ---- page ----

    private final GeckoSession.NavigationDelegate navigation = new GeckoSession.NavigationDelegate() {
        @Override public void onCanGoBack(GeckoSession s, boolean value) { if (s == session) canGoBack = value; }

        @Override public void onLocationChange(GeckoSession s, String url, java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean userGesture) {
            if (s == session && url != null && url.startsWith("https://open.spotify.com/")) pageUrl = url;
        }

        @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            Uri u = Uri.parse(request.uri);
            String scheme = u.getScheme();
            if ("data".equals(scheme) || "about".equals(scheme) || "resource".equals(scheme)) return GeckoResult.allow();
            boolean http = "https".equals(scheme) || "http".equals(scheme);
            if (http && isAllowedHost(u.getHost())) {
                if (request.target == TARGET_WINDOW_NEW) {   // no tabs here: open it in place
                    s.loadUri(request.uri);
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();
            }
            if (http) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) {}
            }
            return GeckoResult.deny();   // blocked (other schemes) or sent to the browser
        }

        @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
            if (s != session) {   // the spare could not load: drop it, a new one is started later
                root.post(() -> dropSpare("it could not load"));
            }
            Uri u = uri == null ? null : Uri.parse(uri);
            if (s != session) return GeckoResult.fromValue("about:blank");
            retryUrl = u != null && "https".equals(u.getScheme()) && isAllowedHost(u.getHost()) ? uri : HOME;
            showingError = true;
            String html = String.format(Locale.ROOT, ERROR_HTML, retryUrl.replace("'", "%27"));
            return GeckoResult.fromValue("data:text/html;base64,"
                    + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
    };

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override public void onPageStart(GeckoSession s, String url) {
            if (s != session) return;
            if (url != null && url.startsWith("https:")) showingError = false;
            if (url != null) loadingUrl = url;
        }

        // Pages without the phone layout (log-in, the offline page) end the splash when loaded;
        // Spotify's player ends it when app.js says the layout is on.
        @Override public void onPageStop(GeckoSession s, boolean success) {
            if (s != session) return;
            if (loadingUrl.startsWith("about:")) return;   // the engine's own blank page before Spotify
            if (!success || !loadingUrl.startsWith("https://open.spotify.com/")) hideSplash();
        }

        @Override public void onProgressChange(GeckoSession s, int percent) {
            if (s != session) return;
            progress.setProgress(percent);
            progress.setVisibility(percent < 100 ? View.VISIBLE : View.GONE);
        }
    };

    private final GeckoSession.ContentDelegate content = new GeckoSession.ContentDelegate() {
        // The page's content process crashed or was killed by Android (memory).
        @Override public void onCrash(GeckoSession s) { gone(s); }
        @Override public void onKill(GeckoSession s) { gone(s); }

        private void gone(GeckoSession s) {
            if (s == session) restart();
            else if (s == spare) root.post(() -> dropSpare("its process was killed"));
        }

        // Spotify's full-screen button: the whole screen, without Android's bars.
        @Override public void onFullScreen(GeckoSession s, boolean on) {
            if (s != session) return;
            fullScreen = on;
            hideSystemBars(on);
        }
    };

    private void hideSystemBars(boolean on) {
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (on) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(WindowInsets.Type.systemBars());
        }
    }

    // ---- splash ----

    // The Aftertone tile with a glow behind it and the name below; the wave draws itself and rings
    // out while the player loads.
    private FrameLayout buildSplash() {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(getColor(R.color.wrap_bg));
        f.setClickable(true);   // the page underneath is not ready for taps yet

        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{0x401ED760, 0x00121212});
        glow.setGradientRadius(170 * dp);
        View halo = new View(this);
        halo.setBackground(glow);
        f.addView(halo, new FrameLayout.LayoutParams(Math.round(340 * dp), Math.round(340 * dp), Gravity.CENTER));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        Drawable d = getDrawable(R.drawable.splash_logo_anim);
        logo.setImageDrawable(d);
        logo.setElevation(12 * dp);
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 30f / 108f);
            }
        });
        column.addView(logo, new LinearLayout.LayoutParams(Math.round(112 * dp), Math.round(112 * dp)));
        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(28);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(24 * dp);
        column.addView(name, lp);
        f.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        if (d instanceof AnimatedVectorDrawable) {
            splashWave = (AnimatedVectorDrawable) d;
            splashWave.registerAnimationCallback(new Animatable2.AnimationCallback() {
                @Override public void onAnimationEnd(Drawable drawable) {
                    if (splash.getVisibility() == View.VISIBLE) f.postDelayed(splashWave::start, 150);
                }
            });
        }
        return f;
    }

    private void showSplash() {
        root.removeCallbacks(splashTimeout);
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        ViewGroup column = (ViewGroup) splash.getChildAt(1);
        View logo = column.getChildAt(0), name = column.getChildAt(1);
        logo.setScaleX(0.8f); logo.setScaleY(0.8f); logo.setAlpha(0f);
        name.setAlpha(0f); name.setTranslationY(16 * getResources().getDisplayMetrics().density);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(new DecelerateInterpolator(2f)).start();
        name.animate().alpha(1f).translationY(0).setStartDelay(180).setDuration(400).setInterpolator(new DecelerateInterpolator(2f)).start();
        if (splashWave != null) { splashWave.reset(); splashWave.start(); }
        root.postDelayed(splashTimeout, SPLASH_MAX_MS);
    }

    private void hideSplash() {
        root.removeCallbacks(splashTimeout);
        if (splash.getVisibility() != View.VISIBLE || splash.getAlpha() < 1f) return;
        splash.animate().alpha(0f).setStartDelay(0).setDuration(280).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    splash.setVisibility(View.GONE);
                    if (splashWave != null) splashWave.stop();
                }).start();
    }

    private void restart() {
        diag("engine: content process crashed or was killed");
        restartPlayer(playing, "crash");
    }

    // ---- messages from the page (assets/wrap/app.js) ----

    private void listenToExtension() {
        listenToExtension(session);
        if (spare != null) listenToExtension(spare);
    }

    // Sets up the message link of one player. Messages from the spare are only about the spare
    // itself (started, ready); everything else is the visible player's.
    private void listenToExtension(GeckoSession owner) {
        if (extension == null || owner == null) return;
        // The engine only shows an extension the traffic of pages registered to it as tabs: without
        // this its request filter (ad and tracker blocking) never sees the page's requests.
        owner.getWebExtensionController().setTabDelegate(extension, new WebExtension.SessionTabDelegate() {});
        runtime.getWebExtensionController().setTabActive(owner, owner == session);
        owner.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
            @Override public GeckoResult<Object> onMessage(String app, Object message, WebExtension.MessageSender sender) {
                if (!(message instanceof JSONObject)) return GeckoResult.fromValue("ok");
                JSONObject m = (JSONObject) message;
                if (owner != session) return GeckoResult.fromValue(onSpareMessage(owner, m));
                switch (m.optString("type")) {
                    case "hello": {   // a fresh page: should it press play (after a restart)?
                        // A plain string: GeckoView refuses an object as a reply here.
                        String reply = resumeAfterRestart ? "resume" : "stay";
                        diag("page loaded, reply " + reply);
                        restarting = false;
                        resumeAfterRestart = false;
                        return GeckoResult.fromValue(reply);
                    }
                    case "ready":   // the phone layout is on
                        hideSplash();
                        if (!coverUntilResumed) hideCover();
                        if (USE_SPARE) root.postDelayed(MainActivity.this::ensureSpare, SPARE_AFTER_READY_MS);
                        break;
                    case "rect": playRect = m.optJSONObject("rect"); break;
                    case "tap":   // a real touch on the play button: never refused as autoplay
                        tapPage(m.optDouble("x"), m.optDouble("y"), m.optDouble("dpr", getResources().getDisplayMetrics().density));
                        break;
                    case "log": diag("page: " + m.optString("text")); break;
                    case "resumeStalled":   // a click did not start the music: add a media-key Play
                        diag("resume: click #" + m.optInt("attempt") + " did not start playback, sending Play");
                        playByMediaKey();
                        break;
                    case "resumed":
                        diag("resume: playing again " + (SystemClock.elapsedRealtime() - restartedAt) + " ms after the restart");
                        root.removeCallbacks(resumeCheck);
                        root.removeCallbacks(switchCheck);
                        releaseHeldMute();
                        coverUntilResumed = false;
                        hideCover();
                        if (USE_SPARE) root.postDelayed(MainActivity.this::ensureSpare, SPARE_AFTER_READY_MS);
                        break;
                    case "resumeFailed":
                        diag("resume: the page could not start playback");
                        playByMediaKey();
                        releaseHeldMute();
                        coverUntilResumed = false;
                        hideCover();
                        break;
                    case "ad":
                        if (m.has("rect")) playRect = m.optJSONObject("rect");
                        if (m.optBoolean("on")) {
                            adLeft = m.optInt("left", -1);
                            adMore = m.optBoolean("more");
                            diag("ad seen (" + m.optString("why") + "), " + (adLeft >= 0 ? adLeft + " s left" : "length unknown") + (adMore ? ", more ads follow" : ""));
                        }
                        onAd(m.optBoolean("on"));
                        break;
                    case "adLeft":   // the ad's length showed up after the ad itself
                        adLeft = m.optInt("left", -1);
                        adMore = m.optBoolean("more");
                        diag("ad length: " + adLeft + " s left" + (adMore ? ", more ads follow" : ""));
                        if (adPlaying && sparable() && adTries > 0) {
                            root.removeCallbacks(adRestart);
                            adTries--;   // this restart never happened
                            diag("ad: short, restart cancelled; muted until it ends");
                        }
                        break;
                    case "cantPlay":
                        if (m.optBoolean("playing")) {
                            // The message can refer to another track while this one keeps playing.
                            diag("can't play: message seen, but music is playing, ignored (" + m.optString("text") + ")");
                            break;
                        }
                        diag("can't play: message seen, nothing playing (" + m.optString("text") + ")");
                        restartPlayer(true, "cantPlay");
                        break;
                }
                return GeckoResult.fromValue("ok");
            }

            @Override public void onConnect(WebExtension.Port port) {
                if (owner == session) { pagePort = port; sheetOpen = false; }
                else sparePort = port;
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object message, WebExtension.Port p) {
                        if (p != pagePort || !(message instanceof JSONObject)) return;
                        JSONObject m = (JSONObject) message;
                        if ("sheet".equals(m.optString("type"))) sheetOpen = m.optBoolean("open");
                        else if ("backResult".equals(m.optString("type")) && !m.optBoolean("closed")) goBackOrLeave();
                    }

                    @Override public void onDisconnect(WebExtension.Port p) {
                        if (pagePort == p) { pagePort = null; sheetOpen = false; }
                        if (sparePort == p) sparePort = null;
                    }
                });
            }
        }, NATIVE_APP);
    }

    // ---- the spare player ----

    private final Runnable spareTick = this::ensureSpare;

    // Starts the spare (a second player, hidden under the visible one and the same size, so it
    // starts and draws like a normal page) if there is none; renews an old one.
    private void ensureSpare() {
        if (!USE_SPARE || isDestroyed() || runtime == null || extension == null || session == null) return;
        long now = SystemClock.elapsedRealtime();
        if (spare != null) {
            if (now - spareCreatedAt <= SPARE_MAX_AGE_MS || switching) return;
            dropSpare("it is old");
        }
        if (switching || restarting || now < spareBlockedUntil) return;
        GeckoSession s = makeSession();
        GeckoView v = new GeckoView(this);
        hideSpareView(v);
        root.addView(v, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.setSession(s);
        v.coverUntilFirstPaint(getColor(R.color.wrap_bg));
        spare = s;
        spareView = v;
        spareReady = false;
        spareMedia = null;
        spareCreatedAt = now;
        diag("spare: starting");
        listenToExtension(s);
        s.loadUri(HOME);
        root.removeCallbacks(spareTick);
        root.postDelayed(spareTick, SPARE_MAX_AGE_MS + 1000);   // renewed when it gets old
    }

    // The spare is a full-size page that must never be seen or touched. Two views that overlap
    // are not reliably stacked in the order they were added (the engine draws into a surface of
    // its own), and the hidden one ended up drawn over the real one, so it is moved entirely off the
    // screen instead, where Android clips it away, and made untouchable.
    private void hideSpareView(GeckoView v) {
        v.setTranslationX(Math.max(root.getWidth(), root.getHeight()) * 2f + 2000f);
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setClickable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void showPromotedView(GeckoView v) {
        v.setTranslationX(0f);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void dropSpare(String why) {
        root.removeCallbacks(spareTick);
        if (spare == null) return;
        diag("spare: dropped (" + why + ")");
        GeckoSession s = spare;
        GeckoView v = spareView;
        spare = null;
        spareView = null;
        spareReady = false;
        sparePort = null;
        spareMedia = null;
        root.removeView(v);
        v.releaseSession();
        s.close();
    }

    // What the spare's page may tell the app: that it has started, and that its player is ready.
    private Object onSpareMessage(GeckoSession owner, JSONObject m) {
        switch (m.optString("type")) {
            case "hello":
                diag("spare: page loaded");
                return "spare";
            case "spareReady":
                if (owner == spare && !spareReady) {
                    spareReady = true;
                    diag("spare: ready, " + (SystemClock.elapsedRealtime() - spareCreatedAt) + " ms after it was started");
                    followWithSpare();
                }
                break;
            case "log":
                diag("spare page: " + m.optString("text"));
                break;
        }
        return "ok";
    }

    private void followWithSpare() {
        if (spare == null || !spareReady || sparePort == null || switching) return;
        try {
            JSONObject go = new JSONObject();
            go.put("type", "goto");
            go.put("path", pathOf(pageUrl));
            sparePort.postMessage(go);
        } catch (JSONException | RuntimeException e) {
            diag("spare: could not follow (" + e + ")");
        }
    }

    // An ad is on: the spare takes over and the old player (with the ad) is closed. Returns false
    // when there is no ready spare, and the caller reloads instead.
    private boolean switchToSpare() {
        if (!USE_SPARE || switching || restarting) return false;
        if (spare == null || !spareReady || sparePort == null) {
            diag("switch: no spare ready (" + (spare == null ? "none" : !spareReady ? "still starting" : "no link") + "), reloading");
            return false;
        }
        switching = true;
        restartedAt = SystemClock.elapsedRealtime();
        diag("switch: ad -> spare player (the spare is " + (restartedAt - spareCreatedAt) / 1000 + " s old)");
        media = null;
        adPlaying = false;
        holdMute = muted;   // the sound stays off until the music is confirmed
        root.removeCallbacks(adRestart);
        root.removeCallbacks(resumeCheck);
        root.postDelayed(resumeCheck, RESUME_CHECK_MS);
        coverUntilResumed = true;
        // The frozen picture and spinner cover the swap, as they do a reload.
        capture(shot -> {
            if (shot != null) showCover(shot); else if (visible) showSplash();
            doSwitch();
        });
        return true;
    }

    private void doSwitch() {
        if (spare == null || sparePort == null) {   // lost while the picture was taken
            switching = false;
            coverUntilResumed = false;
            diag("switch: the spare went away, reloading");
            restartPlayer(true, "ad, spare lost");
            return;
        }
        GeckoSession oldSession = session;
        GeckoView oldView = view;
        session = spare;
        view = spareView;
        pagePort = sparePort;
        media = spareMedia;
        spare = null;
        spareView = null;
        sparePort = null;
        spareMedia = null;
        spareReady = false;
        sheetOpen = false;
        canGoBack = false;
        runtime.getWebExtensionController().setTabActive(session, true);
        // With the screen off the engine would treat this page as a background tab and freeze its
        // timers (so it could never press play); a player must stay active.
        session.setActive(true);
        showPromotedView(view);
        // The player with the ad goes; Spotify drops its device as the page unloads.
        root.removeView(oldView);
        oldView.releaseSession();
        oldSession.close();
        // The spare's page takes over: goes to the page you were on, presses play.
        try {
            JSONObject go = new JSONObject();
            go.put("type", "activate");
            go.put("path", pathOf(pageUrl));
            pagePort.postMessage(go);
        } catch (JSONException | RuntimeException e) {
            diag("switch: could not reach the spare's page (" + e + "), reloading");
            switching = false;
            coverUntilResumed = false;
            restartPlayer(true, "ad, no link");
            return;
        }
        switching = false;
        root.removeCallbacks(switchCheck);
        root.postDelayed(switchCheck, SWITCH_TIMEOUT_MS);
        diag("switch: done after " + (SystemClock.elapsedRealtime() - restartedAt) + " ms, the spare is the player now");
    }

    private static String pathOf(String url) {
        String p = Uri.parse(url).getEncodedPath();
        return p == null || p.isEmpty() ? "/" : p;
    }

    // The player's restart: the same page loaded afresh in the same engine (login and settings
    // kept; Spotify unregisters the old player as the page unloads), behind a still picture of the
    // screen. app.js on the new page presses play again if music was meant to play. A crashed
    // engine gets a new session instead. Returns false when it has restarted too often lately.
    private boolean restartPlayer(boolean resume, String why) {
        boolean ad = why.startsWith("ad");
        boolean crash = "crash".equals(why);
        if ((restarting || switching) && !crash) {
            diag("already restarting, not again for " + why);
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (!ad) {
            if (restarts[restartIndex] != 0 && now - restarts[restartIndex] < RESTART_WINDOW_MS) {
                diag("not restarting (" + why + "): " + MAX_RESTARTS + " restarts in the last few minutes");
                if ("cantPlay".equals(why)) Toast.makeText(this, R.string.cant_play, Toast.LENGTH_LONG).show();
                return false;
            }
            restarts[restartIndex] = now;
            restartIndex = (restartIndex + 1) % MAX_RESTARTS;
        }
        diag("restart: " + why + " (playing=" + playing + ", adPlaying=" + adPlaying + ", ad left=" + adLeft + " s)");
        restarting = true;
        resumeAfterRestart = resume;
        restartedAt = now;
        media = null;
        root.removeCallbacks(adRestart);
        // The new page reports its own ad state at once. After an ad restart the sound stays off
        // until the music is confirmed playing, so a break that survives the restart stays silent.
        adPlaying = false;
        holdMute = ad && muted;
        if (!holdMute) setMuted(false);
        root.removeCallbacks(resumeCheck);
        if (resume) root.postDelayed(resumeCheck, RESUME_CHECK_MS);

        if (crash) {   // the content process is gone: no picture to keep, a new session
            showSplash();
            session.close();
            session.open(runtime);
            view.coverUntilFirstPaint(getColor(R.color.wrap_bg));
            listenToExtension();
            session.loadUri(pageUrl);
            return true;
        }
        capture(shot -> {
            if (shot != null) showCover(shot); else if (visible) showSplash();
            session.loadUri(pageUrl);
        });
        return true;
    }

    // A picture of the screen for the cover, or null when there is none: the engine only delivers
    // one while the app is on screen, so with the screen off (or no answer within a moment) the
    // caller goes on without it instead of waiting.
    private void capture(java.util.function.Consumer<Bitmap> then) {
        if (!visible) { then.accept(null); return; }
        final boolean[] done = {false};
        Runnable giveUp = () -> {
            if (done[0]) return;
            done[0] = true;
            diag("no screen picture within " + CAPTURE_TIMEOUT_MS + " ms, going on without");
            then.accept(null);
        };
        root.postDelayed(giveUp, CAPTURE_TIMEOUT_MS);
        view.capturePixels().accept(shot -> runOnUiThread(() -> {
            if (done[0]) return;
            done[0] = true;
            root.removeCallbacks(giveUp);
            then.accept(shot);
        }), e -> runOnUiThread(() -> {
            if (done[0]) return;
            done[0] = true;
            root.removeCallbacks(giveUp);
            Log.w(TAG, "no screen picture for the cover", e);
            then.accept(null);
        }));
    }
    private static final long CAPTURE_TIMEOUT_MS = 800;

    private void releaseHeldMute() {
        if (!holdMute) return;
        holdMute = false;
        if (!adPlaying) setMuted(false);
    }

    // An ad started or ended (from app.js, which checks several signals, or the media-session
    // label). Every ad is muted at once and, after AD_GRACE_MS, skipped by a restart.
    private void onAd(boolean on) {
        if (on == adPlaying) return;
        adPlaying = on;
        if (!on) {
            diag("ad over");
            adLeft = -1;
            adMore = false;
            root.removeCallbacks(adRestart);
            if (!holdMute) setMuted(false);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - adSeenAt > AD_BREAK_GAP_MS) adTries = 0;   // a new break
        adSeenAt = now;
        setMuted(true);
        if (adTries < MAX_AD_TRIES) {
            adTries++;
            diag("ad: muted, restart in " + AD_GRACE_MS + " ms (try " + adTries + ")");
            root.removeCallbacks(adRestart);
            root.postDelayed(adRestart, AD_GRACE_MS);
        } else {
            diag("ad: " + MAX_AD_TRIES + " restarts did not remove this break, muted until it ends");
        }
    }

    // ---- restart cover ----

    private FrameLayout buildCover() {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(this);
        f.setVisibility(View.GONE);
        f.setClickable(true);   // the page underneath is reloading
        coverShot = new ImageView(this);
        coverShot.setScaleType(ImageView.ScaleType.FIT_XY);
        f.addView(coverShot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A spinner in a dark disc, laid over the play button: the "next song is loading" look.
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF0181818);
        disc.setBackground(bg);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.wrap_accent)));
        int pad = Math.round(10 * dp);
        disc.addView(spin, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        disc.setPadding(pad, pad, pad, pad);
        coverSpinner = disc;
        f.addView(disc, new FrameLayout.LayoutParams(Math.round(56 * dp), Math.round(56 * dp)));
        return f;
    }

    private void showCover(Bitmap shot) {
        root.removeCallbacks(coverTimeout);
        cover.animate().cancel();
        coverShot.setImageBitmap(shot);
        placeSpinner();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        root.postDelayed(coverTimeout, SPLASH_MAX_MS);
    }

    // Centre the spinner on the play button (app.js reports where it is), else near the bottom.
    private void placeSpinner() {
        ViewGroup.LayoutParams lp = coverSpinner.getLayoutParams();
        float cx = cover.getWidth() / 2f, cy = cover.getHeight() * 0.85f;
        if (playRect != null) {
            double dpr = playRect.optDouble("dpr", getResources().getDisplayMetrics().density);
            cx = (float) ((playRect.optDouble("x") + playRect.optDouble("w") / 2) * dpr);
            cy = (float) ((playRect.optDouble("y") + playRect.optDouble("h") / 2) * dpr);
        }
        coverSpinner.setTranslationX(cx - lp.width / 2f);
        coverSpinner.setTranslationY(cy - lp.height / 2f);
    }

    private void hideCover() {
        root.removeCallbacks(coverTimeout);
        if (cover.getVisibility() != View.VISIBLE) return;
        cover.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            cover.setVisibility(View.GONE);
            coverShot.setImageBitmap(null);
        }).start();
    }

    // A real touch at a point of the page (CSS px): the engine takes it as your finger, so Spotify's
    // play button starts the music even where a scripted click would be refused as autoplay.
    private void tapPage(double cssX, double cssY, double dpr) {
        float x = (float) (cssX * dpr), y = (float) (cssY * dpr);
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(down);
        down.recycle();
        root.postDelayed(() -> {
            long u = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(t, u, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(up);
            up.recycle();
        }, 60);
        diag("resume: tapped play at " + Math.round(cssX) + "," + Math.round(cssY));
    }

    // Play through the page's media session: counts as a media key (headset button), which the
    // engine never treats as unwanted autoplay, unlike a scripted click.
    private void playByMediaKey() {
        if (media != null) media.play();
        else diag("resume: no media session yet, cannot send Play");
    }

    // Mutes the phone's music volume for the length of an ad. Spotify's
    // own volume is left alone: muting the player itself makes Spotify pause the ad.
    private void setMuted(boolean on) {
        root.removeCallbacks(unmuteSafety);
        if (on == muted) return;
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            muted = on;
            diag(on ? "music volume muted for the ad" : "music volume back");
            if (on) root.postDelayed(unmuteSafety, MAX_MUTE_MS);
        } catch (SecurityException e) {   // Do Not Disturb can forbid it
            Log.w(TAG, "cannot mute the ad", e);
        }
    }

    // Spotify plays DRM-protected audio (Widevine) and starts music by itself after a tap.
    private final GeckoSession.PermissionDelegate permissions = new GeckoSession.PermissionDelegate() {
        @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
            Uri u = Uri.parse(perm.uri);
            boolean spotify = isSpotifyHost(u.getHost());
            boolean allow = spotify && (perm.permission == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
                    || perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE
                    || perm.permission == PERMISSION_PERSISTENT_STORAGE
                    || perm.permission == PERMISSION_STORAGE_ACCESS);
            return GeckoResult.fromValue(allow ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }
    };

    // Spotify lets you pick a picture for a playlist or your profile, and its settings use
    // drop-down lists (<select>): the engine leaves showing those to the app.
    private final GeckoSession.PromptDelegate prompts = new GeckoSession.PromptDelegate() {
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt prompt) {
            java.util.List<ChoicePrompt.Choice> flat = new java.util.ArrayList<>();
            flatten(prompt.choices, flat);
            String[] labels = new String[flat.size()];
            boolean[] checked = new boolean[flat.size()];
            int selected = -1;
            for (int i = 0; i < flat.size(); i++) {
                labels[i] = flat.get(i).label;
                checked[i] = flat.get(i).selected;
                if (checked[i] && selected < 0) selected = i;
            }
            GeckoResult<PromptResponse> result = new GeckoResult<>();
            boolean[] answered = {false};   // a dialog answers once: a choice, OK, or dismissed
            android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            if (prompt.message != null && !prompt.message.isEmpty()) dialog.setTitle(prompt.message);
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                dialog.setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            java.util.List<String> ids = new java.util.ArrayList<>();
                            for (int i = 0; i < checked.length; i++) if (checked[i]) ids.add(flat.get(i).id);
                            answered[0] = true;
                            result.complete(prompt.confirm(ids.toArray(new String[0])));
                        });
            } else {
                dialog.setSingleChoiceItems(labels, selected, (d, which) -> {
                    answered[0] = true;
                    result.complete(prompt.confirm(flat.get(which)));
                    d.dismiss();
                });
            }
            dialog.setOnDismissListener(d -> {
                if (answered[0]) return;
                answered[0] = true;
                result.complete(prompt.dismiss());
            }).show();
            return result;
        }

        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
            if (fileResult != null) fileResult.complete(filePrompt.dismiss());
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            if (prompt.type == FilePrompt.Type.MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            filePrompt = prompt;
            fileResult = new GeckoResult<>();
            try {
                startActivityForResult(pick, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileResult.complete(prompt.dismiss());
            }
            return fileResult;
        }
    };

    // Option groups become their options; separators and disabled options are left out.
    private static void flatten(GeckoSession.PromptDelegate.ChoicePrompt.Choice[] choices, java.util.List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> out) {
        if (choices == null) return;
        for (GeckoSession.PromptDelegate.ChoicePrompt.Choice c : choices) {
            if (c.items != null) flatten(c.items, out);
            else if (!c.separator && !c.disabled) out.add(c);
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQ_FILE) { super.onActivityResult(request, result, data); return; }
        if (fileResult == null) return;
        Uri picked = result == RESULT_OK && data != null ? data.getData() : null;
        fileResult.complete(picked != null ? filePrompt.confirm(this, picked) : filePrompt.dismiss());
        fileResult = null;
        filePrompt = null;
    }

    // ---- what is playing: the page's media session feeds the notification ----

    private String title = "", artist = "";

    private final MediaSession.Delegate mediaDelegate = new MediaSession.Delegate() {
        @Override public void onActivated(GeckoSession s, MediaSession m) {
            if (s == session) media = m;
            else if (s == spare) spareMedia = m;   // kept for when the spare takes over
        }

        @Override public void onDeactivated(GeckoSession s, MediaSession m) {
            if (s != session) return;
            if (media == m) media = null;
            playing = false;
            PlaybackService.update(title, artist, false);
        }

        @Override public void onMetadata(GeckoSession s, MediaSession m, MediaSession.Metadata meta) {
            if (s != session) return;
            media = m;
            if (!clip(meta.title).equals(title)) {   // the song changed
                root.removeCallbacks(followRun);
                root.postDelayed(followRun, 1500);
            }
            title = clip(meta.title);
            artist = clip(meta.artist);
            PlaybackService.update(title, artist, playing);
            // Fallback ad signal; app.js usually reports the ad first from the player's markup.
            boolean labelled = isAdLabel(title) || isAdLabel(artist);
            if (labelled && !adPlaying) onAd(true);
            if (meta.artwork != null) meta.artwork.getBitmap(512).accept(PlaybackService::art, e -> {});
        }

        @Override public void onPlay(GeckoSession s, MediaSession m) {
            if (s != session) return;
            media = m;
            playing = true;
            PlaybackService.update(title, artist, true);
            // After a swap the page cannot tell its own audio from the old player's mirrored state:
            // tell it when the engine really starts audio.
            if (coverUntilResumed && pagePort != null) {
                try {
                    JSONObject started = new JSONObject();
                    started.put("type", "audioStarted");
                    pagePort.postMessage(started);
                } catch (JSONException | RuntimeException ignored) {}
            }
            // The engine says audio started: after a restart that is the quickest proof the song is
            // back, so the sound comes on now rather than when the page confirms.
            // A short wait first: if an ad survived the restart, its audio starts too, and the page's
            // ad report (checked four times a second) must get the chance to arrive.
            if (holdMute && !adPlaying) root.postDelayed(() -> {
                if (!holdMute || adPlaying || isAdLabel(title) || isAdLabel(artist)) return;
                diag("resume: audio started " + (SystemClock.elapsedRealtime() - restartedAt) + " ms after the restart");
                releaseHeldMute();
            }, 400);
        }

        @Override public void onPause(GeckoSession s, MediaSession m) {
            if (s != session) return;
            playing = false;
            PlaybackService.update(title, artist, false);
        }

        @Override public void onStop(GeckoSession s, MediaSession m) {
            if (s != session) return;
            playing = false;
            PlaybackService.update(title, artist, false);
        }
    };

    // "Advertisement" alone or followed by a separator ("Advertisement • 1 of 3").
    private static boolean isAdLabel(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (String label : AD_LABELS) {
            if (t.equals(label)) return true;
            if (t.startsWith(label) && t.length() > label.length() && !Character.isLetterOrDigit(t.charAt(label.length()))) return true;
        }
        return false;
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }

    // The notification and headset buttons drive the page's media session.
    private final PlaybackService.Remote remote = new PlaybackService.Remote() {
        @Override public void play() { runOnUiThread(() -> { if (media != null) media.play(); }); }
        @Override public void pause() { runOnUiThread(() -> { if (media != null) media.pause(); }); }
        @Override public void next() { runOnUiThread(() -> { if (media != null) media.nextTrack(); }); }
        @Override public void previous() { runOnUiThread(() -> { if (media != null) media.previousTrack(); }); }
    };

    // ---- hosts and links ----

    private static boolean isAllowedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : ALLOWED_HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        return false;
    }

    private static boolean isSpotifyHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(SPOTIFY_HOST) || host.endsWith("." + SPOTIFY_HOST);
    }

    // The page to open: a tapped open.spotify.com link, or the home page.
    private static String startUrl(Intent i) {
        Uri d = i == null || !Intent.ACTION_VIEW.equals(i.getAction()) ? null : i.getData();
        if (d != null && "https".equals(d.getScheme()) && "open.spotify.com".equals(d.getHost())) return d.toString();
        return HOME;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Only a link opens a page. Tapping the notification must not reload the player.
        setIntent(intent);
        if (session != null && Intent.ACTION_VIEW.equals(intent.getAction())) session.loadUri(startUrl(intent));
    }

    // ---- network ----

    // When the connection comes back while the error page is showing, reload by itself.
    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                // A moment for DNS to settle, otherwise the reload fails again.
                root.postDelayed(() -> {
                    if (showingError && !isDestroyed() && session != null) session.loadUri(retryUrl);
                }, 700);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot watch the network", e);
            networkCallback = null;
        }
    }

    // ---- window ----

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // Back goes back in the player (and closes the Library sheet, which is a history step);
        // at the start it hides the app and the music keeps playing.
        if (session == null) moveTaskToBack(true);
        else if (fullScreen) session.exitFullScreen();
        else if (sheetOpen && pagePort != null) {
            // The page closes its sheet and answers; if it had none after all, go on as usual.
            try {
                JSONObject back = new JSONObject();
                back.put("type", "back");
                pagePort.postMessage(back);
            } catch (org.json.JSONException e) {
                goBackOrLeave();
            }
        } else goBackOrLeave();
    }

    private void goBackOrLeave() {
        if (canGoBack) session.goBack(); else moveTaskToBack(true);
    }

    // The session stays active in the background on purpose: setActive(false) would let Gecko
    // throttle the page, and the player with it.

    @Override protected void onDestroy() {
        diag("lifecycle: onDestroy (finishing=" + isFinishing() + ", config change=" + isChangingConfigurations() + ")");
        setMuted(false);
        if (networkCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) {}
        }
        PlaybackService.setRemote(null);
        stopService(new Intent(this, PlaybackService.class));
        dropSpare("closing");
        view.releaseSession();
        if (session != null) session.close();
        super.onDestroy();
    }
}
