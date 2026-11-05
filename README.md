# Aftertone

**Spotify's web player, as a phone app.** Aftertone opens [open.spotify.com](https://open.spotify.com) in its
own full-screen Android app and lays it out like a mobile music app: a single column, a floating mini
player, a bottom tab bar, and full-screen sheets for your library, lyrics and queue. Music keeps
playing with the screen off, with a media notification and lock-screen and headset controls.

> **Android only.** Aftertone is an Android app (arm64, Android 8.0+). There is no iPhone version, and
> none is planned. If you are on an iPhone, you are probably doing well for yourself: please just
> buy a Spotify Premium subscription and enjoy the official app.

> **Prototype.** Aftertone is still an early prototype, so expect rough edges. If something goes
> wrong, please [open an issue](../../issues/new) and describe it properly, so it can be fixed:
>
> - **Title:** a short summary of the problem.
> - **Screen:** where in the app it happened (Home, Search, Your Library, the player, lyrics, queue, settings...).
> - **Steps to reproduce:** what you did, in order, until the problem showed up, and what you expected instead.
>
> Helpful extras: your phone model and Android version, the app version, and whether it happened
> with the screen on or off. Ideas and suggestions are welcome too: open an issue with
> **[Recommendation]** at the start of the title.

> **Unofficial.** Aftertone is an independent project. It is not affiliated with, endorsed by or
> connected to Spotify. "Spotify" is a trademark of Spotify AB. You need your own Spotify account.

## Features

- **Phone layout for the web player:** search and your profile on top, one column of content, a
  mini player card above a Home / Search / Downloads / Your Library tab bar.
- **Full-screen sheets:** Your Library, lyrics, and the queue / devices panel open full screen;
  Back closes them.
- **Sharp and stable:** laid out at your phone's real width and pixel density, no accidental
  zooming, no desktop hover tooltips popping up on taps, no text selection on long-press.
- **Background playback:** a foreground service keeps music going with the screen off and shows a
  notification with cover art; lock-screen and headset buttons work.
- **Ad handling:** ads are detected from several signals, muted immediately and skipped by
  restarting the player in the background. The screen stays as it was, with a loading spinner on
  the play button, and the song picks up again.
- **Self-recovery:** when Spotify says *"can't play this right now"*, the player restarts out of
  sight and resumes.
- **Small touches:** animated splash screen, offline page that reloads when the connection returns,
  Android-style drop-down lists in settings, a file picker for playlist covers, and a *"Downloads
  are coming soon"* note in place of the download buttons.

## How it works

Aftertone is a thin Android app around **[GeckoView](https://mozilla.github.io/geckoview/)**, the
engine Firefox for Android is built on. The page runs with Firefox's desktop identity (the same as
Firefox's *Desktop site* option), so Spotify serves its regular web player. A built-in browser
extension then restyles that page for a phone and talks to the app.

```
┌─────────────────────── Aftertone (Android app) ────────────────────────┐
│                                                                        │
│  MainActivity                         GeckoView: open.spotify.com      │
│  splash, Back, ads,     ◄─ native ─►  built-in extension               │
│  restarts, prompts         messaging    inject.css / inject.js (layout)│
│        │                                app.js (ads, errors, resume)   │
│        │                                block.js (ad hosts)            │
│        ▼                                          │                    │
│  PlaybackService      ◄── media session ──────────┘                    │
│  notification, lock screen,  (title, cover, play/pause)                │
│  headset buttons                                                       │
└────────────────────────────────────────────────────────────────────────┘
```

Why GeckoView and not Android's WebView: a WebView announces itself to websites as an embedded
Android app, and Spotify then applies its phone rules to the web player (on Free accounts, for
example, shuffle is locked). Aftertone does not hide or alter what the engine reports; it simply
uses the engine Firefox uses.

### Ads

1. **Detecting:** several independent signals, checked four times a second and whenever the player
   changes: the player's ad-only markup, the media session labelled as an ad, and the now-playing
   link pointing at an ad.
2. **Muting:** the phone's music volume is muted at once.
3. **Skipping:** after a second of the ad playing silently, the player is replaced, which should
   drop the ad break. The app keeps a **spare player** (a second copy of the page, fully started and
   hidden under the visible one); on an ad the spare takes over, the old player is closed and a new
   spare is started in the background. The spare is a second player on the same account, so it shows the same song and queue, and the app keeps it on the same page as the visible player (updated when the song changes), so a swap shows no page change. It cannot hold a different song cued in pause while the first one plays: Spotify allows one active player per account. With no ready spare, or if the swap does not bring the music
   back within 12 s, the player is reloaded instead. Up to three tries per break; if a break
   survives, it stays muted until it ends. The spare costs about one more page of memory and is
   dropped when the phone is short of memory; set `USE_SPARE = false` in `MainActivity.java` to turn
   it off. An ad with only a few seconds left (under 12 s, and the last of its break) is simply
   waited out muted, since a restart takes longer than that.
4. **Blocking:** video-ad and ad-asset hosts, plus analytics, tracking and error-reporting hosts,
   are blocked inside the engine.

### Swap flow and timings

What happens from the moment an ad is seen (times measured on a phone, release-like debug build;
the spare is ready about 6-9 s after it is started, long before any ad needs it):

| Step | What happens | Time |
|---|---|---|
| 0 | Ad seen (ad markup, media-session label or link); music volume muted at once | 0 ms |
| 1 | Grace period, so a flicker of the page is not taken for an ad | 1000 ms |
| 2 | **Swap:** the spare's view becomes the player, the old player is closed (Spotify drops its device as that page unloads). No screenshot is needed, so it also works with the screen off; with the screen on, a screenshot of the old screen is kept as a cover for the next steps | 10-40 ms |
| 3 | Spare's page is told to take over: goes to the page you were on | about 0.3 s |
| 4 | Play button of the new player is enabled ("player ready") | about 0.3 s later |
| 5 | **Rewind:** if the song is 3 s or more in, Previous is pressed (retried every 2 s while the clock stays at 3 s or more) so the song starts from its beginning; below 3 s nothing is pressed, because Previous would then go to the earlier song | 0 s, or about 2 s |
| 6 | Play is pressed with a real touch (presses in the first seconds after a swap are often ignored, so it is retried) | about 2-3 s per try |
| 7 | The engine reports audio started; music volume is turned back on | about 6-7 s after step 2 |
| 8 | A new spare is started in the background | 3 s later; ready after 6-9 s |

If there is no ready spare, the page is reloaded instead (frozen screenshot and spinner while it
loads), which takes roughly twice as long. If the music is not back 12 s after a swap, the player
is reloaded as well.

With the **screen off** an ad is handled the same way: the swap needs no screenshot, and the
swapped-in page is kept active so it can press play. (An earlier version waited for a screenshot
that never arrived while locked, so the swap only happened once the phone was unlocked, cutting the
music.)

After any restart the app presses play again with a real touch on the play button and checks that
the music is actually running before turning the sound back on.

## Requirements

- An **arm64 Android phone** with **Android 8.0** or newer (Android 13+ asks for notification
  permission on first start).
- A Spotify account.
- About **200 MB** of storage (the APK carries the GeckoView engine).

## Install

1. Open the **[Releases](../../releases/latest)** page of this repository and download the
   `.apk` file of the latest release.
2. Open the downloaded file on your phone. If Android asks, allow installing apps from your
   browser or file manager.
3. Open **Aftertone** and log in to Spotify once.

To update, download the newer release and install it over the old one; your login is kept.

## Build from source

Prefer to build it yourself? You can also build the APK from source.

**You need:** JDK 17 or 21, and the Android SDK with `platforms;android-37.2` and
`build-tools;37.0.0`. Android Studio is not required.

```sh
git clone <this repository>
cd <repository folder>
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy it to the phone and open it.

**Signed release build** (the kind attached to Releases): create `keystore.properties` in the
project root (it is git-ignored) pointing at your own signing key:

```properties
storeFile=/path/to/your-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew releaseApk` writes `dist/Aftertone-<version>.apk`. An APK signed with a different
key cannot be installed over this one; uninstall first.

The first build downloads Gradle, the Android Gradle Plugin and GeckoView (about 250 MB) and takes a
while; later builds take seconds.

**First start:** log in to Spotify once. The app keeps its own login, separate from your browser.

## Use it in Firefox instead (userscript)

The phone layout also works without the app, in **Firefox for Android** with **Desktop site** turned
on, through a userscript manager such as Violentmonkey or Tampermonkey:

```sh
python3 tools/build-userscript.py    # writes dist/spotify-mobile.user.js
```

Open the generated file in Firefox and install it. The userscript only restyles the page; ad
handling, background playback and the other app features need the app. Firefox does not run add-ons
in installed web apps ("Add to home screen"), only in normal tabs.

## Development

```
app/src/main/
  java/com/example/spotifywrapper/
    MainActivity.java       GeckoView session, splash, Back, ads, restarts, prompts
    PlaybackService.java    foreground service, media notification, lock screen
  assets/wrap/              built-in extension (manifest.json)
    inject.css, inject.js   phone layout (shared with the userscript)
    app.js                  ads, errors and restarts <-> MainActivity
    block.js                ad hosts
    load-css.js             hands inject.css to inject.js
  res/                      icon, splash, theme
tools/build-userscript.py   builds the userscript from inject.css / inject.js
```

- **Spotify changes its page often.** Every selector the layout relies on lives in the `SEL` table
  at the top of `inject.js`; when something breaks after a Spotify update, that is the place to
  look. Setting `LAYOUT = 'desktop'` there turns the phone layout off as a fallback.
- **Logs:** `adb logcat -s SpotifyWrapper` shows every ad, restart and resume decision. Debug
  builds also keep them in a file that outlives logcat's short buffer, together with the screen's
  lifecycle and every restart's reason:
  `adb shell run-as io.github.pragyan.aftertone cat files/aftertone.log`.
- **Inspecting the live page:** debug builds enable Firefox remote debugging. Forward the socket
  with `adb forward tcp:6000 localabstract:io.github.pragyan.aftertone/firefox-debugger-socket` and
  connect with Firefox's `about:debugging` or any Remote Debugging Protocol client.

## Known limitations

- **Unofficial and fragile:** a Spotify update can break parts of the layout until `SEL` is updated.
- **Terms of use:** Spotify's terms do not allow modifying its client or skipping and blocking ads.
  Using Aftertone may put your account at risk; use it at your own risk.
- **Swiping the app away stops the music**, as the player lives in the app's main screen.
- **English labels:** a few features (the *"can't play"* recovery, some top-bar and download-button
  matching) look for English text in Spotify's interface.
- **Downloads** for offline listening are not available yet.
- **arm64 only**, to keep the APK size down.
