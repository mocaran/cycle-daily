package com.cycledaily.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public class TrackingService extends Service implements LocationListener {
    static final String START = "com.cycledaily.START";
    static final String MODE = "com.cycledaily.MODE";
    static final String STOP = "com.cycledaily.STOP";
    static final String UPDATE = "com.cycledaily.UPDATE";
    private static final String CHANNEL = "tracking";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private Location previous;
    private boolean listening;
    private long lastGoodFused;
    private long lastGoodGps;
    private String selectedSource;
    private long lastNotification;
    private int registeredSources;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (TrackStore.active(TrackingService.this)) {
                rollDay();
                TrackStore.prefs(TrackingService.this).edit()
                        .putLong("heartbeat", SystemClock.elapsedRealtime()).apply();
                if (SystemClock.elapsedRealtime() - lastNotification > 5000) {
                    updateNotification();
                    lastNotification = SystemClock.elapsedRealtime();
                }
                broadcast();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "运动记录", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("锁屏期间持续记录距离和用时");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if (STOP.equals(action)) {
            if (TrackStore.active(this)) TrackStore.stop(this);
            stopListening();
            broadcast();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (MODE.equals(action)) {
            String mode = intent.getStringExtra("mode");
            if (TrackStore.active(this) && (TrackStore.WALK.equals(mode) || TrackStore.RUN.equals(mode))) {
                TrackStore.mode(this, mode);
                previous = null;
                selectedSource = null;
                updateNotification();
                broadcast();
            }
            return START_STICKY;
        }
        if (!TrackStore.active(this)) {
            String mode = intent == null ? TrackStore.WALK : intent.getStringExtra("mode");
            TrackStore.start(this, TrackStore.RUN.equals(mode) ? TrackStore.RUN : TrackStore.WALK);
        } else {
            TrackStore.recoverAfterInterruption(this);
        }
        try { startForeground(1, notification()); }
        catch (RuntimeException e) {
            TrackStore.prefs(this).edit().putString("lastStartError", "无法启动后台计时服务，请检查系统权限").apply();
            TrackStore.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        startListening();
        if (registeredSources == 0) {
            TrackStore.prefs(this).edit().putString("lastStartError", "定位源启动失败，请检查精确位置权限与定位开关").apply();
            TrackStore.stop(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_STICKY;
    }

    private void startListening() {
        if (listening) return;
        registeredSources = 0;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            TrackStore.prefs(this).edit().putString("lastStartError", "未获得精确位置权限，无法计距").apply();
            stopSelf();
            return;
        }
        listening = true;
        if (locationManager.getProviders(true).contains(LocationManager.FUSED_PROVIDER)) {
            try {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000, 0, this, Looper.getMainLooper());
                registeredSources++;
            } catch (RuntimeException ignored) { }
        }
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
                registeredSources++;
            }
            catch (RuntimeException ignored) { }
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, this, Looper.getMainLooper());
                registeredSources++;
            }
            catch (RuntimeException ignored) { }
        }
    }

    private void stopListening() {
        handler.removeCallbacks(ticker);
        if (listening) locationManager.removeUpdates(this);
        listening = false;
        registeredSources = 0;
        lastGoodFused = 0;
        lastGoodGps = 0;
        selectedSource = null;
        previous = null;
    }

    @Override public void onLocationChanged(Location current) {
        if (!TrackStore.active(this)) return;
        long age = SystemClock.elapsedRealtimeNanos() - current.getElapsedRealtimeNanos();
        if (age < 0 || age > 5_000_000_000L) return;
        TrackStore.prefs(this).edit().putLong("lastRawFix", System.currentTimeMillis())
                .putFloat("lastRawAccuracy", current.hasAccuracy() ? current.getAccuracy() : -1)
                .putString("lastProvider", current.getProvider())
                .putInt("rawFixCount", TrackStore.prefs(this).getInt("rawFixCount", 0) + 1).apply();
        if (!current.hasAccuracy() || current.getAccuracy() > 35) return;
        long now = SystemClock.elapsedRealtime();
        String source = current.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(source)) lastGoodGps = now;
        if (LocationManager.FUSED_PROVIDER.equals(source)) lastGoodFused = now;
        if (LocationManager.FUSED_PROVIDER.equals(source) && lastGoodGps > 0 && now - lastGoodGps < 10000) return;
        if (LocationManager.NETWORK_PROVIDER.equals(source)
                && ((lastGoodGps > 0 && now - lastGoodGps < 10000)
                || (lastGoodFused > 0 && now - lastGoodFused < 10000))) return;
        if (!source.equals(selectedSource)) {
            previous = null;
            selectedSource = source;
        }
        TrackStore.prefs(this).edit().putLong("lastFix", System.currentTimeMillis())
                .putString("lastFixDay", TrackStore.day())
                .putFloat("lastAccuracy", current.getAccuracy())
                .putLong("lastLat", Double.doubleToRawLongBits(current.getLatitude()))
                .putLong("lastLon", Double.doubleToRawLongBits(current.getLongitude()))
                .putInt("validFixCount", TrackStore.prefs(this).getInt("validFixCount", 0) + 1).apply();
        rollDay();
        if (previous != null) {
            long dt = current.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos();
            double seconds = dt / 1_000_000_000.0;
            float meters = previous.distanceTo(current);
            if (seconds <= 0) return;
            // Shorter, accuracy-dependent segments preserve turns in walking routes.
            float threshold = Math.max(3, Math.min(10, Math.max(previous.getAccuracy(), current.getAccuracy()) * 0.55f));
            if (seconds < 30 && meters >= threshold && meters / seconds < 8.5) {
                if (!RouteStore.append(this, TrackStore.day(), previous, current, TrackStore.mode(this)))
                    TrackStore.prefs(this).edit().putBoolean("routeWriteFailed", true).apply();
                TrackStore.add(this, TrackStore.day(), TrackStore.mode(this), meters);
                TrackStore.prefs(this).edit()
                        .putInt("countedSegments", TrackStore.prefs(this).getInt("countedSegments", 0) + 1)
                        .putFloat("lastSegmentMeters", meters)
                        .putLong("lastCountedAt", System.currentTimeMillis()).apply();
                updateNotification();
                broadcast();
                previous = current;
            } else if (seconds >= 30 || meters / seconds >= 8.5) {
                previous = current;
            }
        } else previous = current;
    }

    private void rollDay() {
        String today = TrackStore.day();
        String sessionDay = TrackStore.prefs(this).getString("sessionDay", today);
        if (!today.equals(sessionDay)) {
            long elapsed = TrackStore.sessionElapsed(this);
            TrackStore.prefs(this).edit()
                    .putLong(sessionDay + ".elapsed", TrackStore.elapsed(this, sessionDay) + elapsed)
                    .putLong("started", SystemClock.elapsedRealtime())
                    .putString("sessionDay", today).apply();
            previous = null;
            TrackStore.pruneOldDays(this);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String day = TrackStore.day();
        double score = TrackStore.score(this, day) / 1000.0;
        String mode = TrackStore.RUN.equals(TrackStore.mode(this)) ? "跑步" : "步行";
        int segments = TrackStore.prefs(this).getInt("countedSegments", 0);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setColor(Color.rgb(172, 237, 105))
                .setContentTitle("Cycle Daily · " + mode + "计时中")
                .setContentText(segments == 0 ? "尚无定位计距 · 点击查看定位状态"
                        : String.format(java.util.Locale.CHINA, "定位已计算 %d 段 · 今日 %.2f / %.2f km",
                                segments, score, TrackStore.goalKm(this)))
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void updateNotification() { getSystemService(NotificationManager.class).notify(1, notification()); }
    private void broadcast() { sendBroadcast(new Intent(UPDATE).setPackage(getPackageName())); }
    @Override public void onDestroy() { stopListening(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
