package com.example.spotifywrapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

// Keeps the app running while the screen is off and shows the media notification (lock-screen and
// headset buttons). It owns no audio: the music plays in the page (GeckoView), and the buttons are
// passed back to the page's media session through Remote. Title, artist, cover and playing state
// come from that media session (MainActivity's MediaSession.Delegate).
public class PlaybackService extends Service {
    // Implemented by MainActivity: drives the page's media session.
    interface Remote {
        void play();
        void pause();
        void next();
        void previous();
    }

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 1;
    private static final String ACTION_PLAY_PAUSE = "com.example.spotifywrapper.PLAY_PAUSE",
            ACTION_NEXT = "com.example.spotifywrapper.NEXT", ACTION_PREVIOUS = "com.example.spotifywrapper.PREVIOUS";
    private static final long WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L;

    private static volatile Remote remote;
    private static volatile PlaybackService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private String title = "", artist = "";
    private Bitmap art;
    private boolean playing;

    static void setRemote(Remote r) { remote = r; }

    // From the page's media session, any thread.
    static void update(String title, String artist, boolean playing) {
        PlaybackService s = instance;
        if (s != null) s.main.post(() -> s.apply(title, artist, playing));
    }

    static void art(Bitmap cover) {
        PlaybackService s = instance;
        if (s != null) s.main.post(() -> { s.art = cover; s.refresh(); });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        getSystemService(NotificationManager.class).createNotificationChannel(channel());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spotifywrapper:playback");
        wakeLock.setReferenceCounted(false);

        session = new MediaSession(this, "SpotifyWrapper");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Remote r = remote; if (r != null) r.play(); }
            @Override public void onPause() { Remote r = remote; if (r != null) r.pause(); }
            @Override public void onSkipToNext() { Remote r = remote; if (r != null) r.next(); }
            @Override public void onSkipToPrevious() { Remote r = remote; if (r != null) r.previous(); }
        });
        session.setActive(true);
        updateSession();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Remote r = remote;
        if (r != null) {
            if (ACTION_PLAY_PAUSE.equals(action)) { if (playing) r.pause(); else r.play(); }
            else if (ACTION_NEXT.equals(action)) r.next();
            else if (ACTION_PREVIOUS.equals(action)) r.previous();
        }

        Notification n = buildNotification();
        // Must be called within a few seconds of startForegroundService().
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, n);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) wakeLock.release();
        session.setActive(false);
        session.release();
        super.onDestroy();
    }

    private NotificationChannel channel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
        c.setDescription(getString(R.string.channel_playback_description));
        c.setShowBadge(false);
        return c;
    }

    private void apply(String newTitle, String newArtist, boolean nowPlaying) {
        if (!newTitle.equals(title)) art = null;   // the old cover would be wrong for the new track
        title = newTitle;
        artist = newArtist;
        playing = nowPlaying;
        refresh();
        // Without this the CPU can sleep with the screen off and the music stutters or stops.
        if (playing && !wakeLock.isHeld()) wakeLock.acquire(WAKE_LOCK_MAX_MS);
        else if (!playing && wakeLock.isHeld()) wakeLock.release();
    }

    private void refresh() {
        updateSession();
        getSystemService(NotificationManager.class).notify(NOTIFICATION, buildNotification());
    }

    private void updateSession() {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setColor(getColor(R.color.wrap_accent))
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(artist)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(1, android.R.drawable.ic_media_previous, getString(R.string.action_previous), ACTION_PREVIOUS))
                .addAction(action(2, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        getString(playing ? R.string.action_pause : R.string.action_play), ACTION_PLAY_PAUSE))
                .addAction(action(3, android.R.drawable.ic_media_next, getString(R.string.action_next), ACTION_NEXT))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private Notification.Action action(int code, int icon, String label, String action) {
        PendingIntent pi = PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }
}
