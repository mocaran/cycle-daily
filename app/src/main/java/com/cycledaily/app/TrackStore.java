package com.cycledaily.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.File;
import java.util.Map;

final class TrackStore {
    private static final String PREFS = "cycle_daily";
    static final String WALK = "walk";
    static final String RUN = "run";
    static double goalKm(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong("goalKm", Double.doubleToRawLongBits(6.0)));
    }
    static double runFactor(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong("runFactor", Double.doubleToRawLongBits(2.0)));
    }
    static boolean setGoal(Context c, double goalKm, double runFactor) {
        if (!Double.isFinite(goalKm) || goalKm < 0.1 || goalKm > 100
                || !Double.isFinite(runFactor) || runFactor < 0.1 || runFactor > 100) return false;
        return prefs(c).edit().putLong("goalKm", Double.doubleToRawLongBits(goalKm))
                .putLong("runFactor", Double.doubleToRawLongBits(runFactor)).commit();
    }

    static String day() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static double distance(Context c, String day, String mode) {
        return Double.longBitsToDouble(prefs(c).getLong(day + "." + mode, Double.doubleToRawLongBits(0)));
    }
    static void add(Context c, String day, String mode, double meters) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(day + "." + mode, Double.doubleToRawLongBits(distance(c, day, mode) + meters)).apply();
    }
    static boolean deductToday(Context c, String mode, double meters) {
        if (active(c) || meters <= 0) return false;
        String today = day();
        double current = distance(c, today, mode);
        if (meters > current + 0.01) return false;
        if (!RouteStore.trimLatest(c, today, mode, meters)) return false;
        return prefs(c).edit().putLong(today + "." + mode,
                Double.doubleToRawLongBits(Math.max(0, current - meters)))
                .putInt("countedSegments", RouteStore.read(c, today).size()).commit();
    }
    static boolean resetToday(Context c) {
        if (active(c) || !RouteStore.clear(c, day())) return false;
        String today = day();
        return prefs(c).edit().remove(today + "." + WALK).remove(today + "." + RUN)
                .remove(today + ".elapsed").putInt("countedSegments", 0)
                .putLong("lastCountedAt", 0).putFloat("lastSegmentMeters", 0)
                .commit();
    }
    static boolean active(Context c) { return prefs(c).getBoolean("active", false); }
    static String mode(Context c) { return prefs(c).getString("mode", WALK); }
    static long started(Context c) { return prefs(c).getLong("started", 0); }
    static long elapsed(Context c, String day) { return prefs(c).getLong(day + ".elapsed", 0); }
    static long sessionElapsed(Context c) {
        if (!active(c)) return 0;
        long start = started(c);
        return start > 0 ? Math.max(0, SystemClock.elapsedRealtime() - start) : 0;
    }
    static void start(Context c, String mode) {
        pruneOldDays(c);
        prefs(c).edit().putBoolean("active", true).putString("mode", mode)
                .putLong("started", SystemClock.elapsedRealtime()).putString("sessionDay", day())
                .putLong("lastFix", 0).putLong("lastRawFix", 0)
                .putInt("rawFixCount", 0).putInt("validFixCount", 0)
                .putInt("countedSegments", 0).putLong("lastCountedAt", 0)
                .putBoolean("routeWriteFailed", false)
                .remove("lastLat").remove("lastLon")
                .remove("lastStartError")
                .putLong("heartbeat", SystemClock.elapsedRealtime()).apply();
    }
    static void pruneOldDays(Context c) {
        String today = day();
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = p.edit();
        for (Map.Entry<String, ?> item : p.getAll().entrySet()) {
            String key = item.getKey();
            if (key.matches("\\d{4}-\\d{2}-\\d{2}\\..+") && !key.startsWith(today + "."))
                edit.remove(key);
        }
        edit.apply();
        File dir = new File(c.getFilesDir(), "routes");
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && file.getName().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")
                    && !file.getName().equals(today + ".jsonl")) file.delete();
        }
    }
    static void recoverAfterInterruption(Context c) {
        SharedPreferences p = prefs(c);
        long now = SystemClock.elapsedRealtime();
        long heartbeat = p.getLong("heartbeat", 0);
        if (heartbeat > 0 && now - heartbeat <= 10000) return;
        long start = p.getLong("started", 0);
        String sessionDay = p.getString("sessionDay", day());
        long recorded = heartbeat >= start && start > 0 ? heartbeat - start : 0;
        p.edit().putLong(sessionDay + ".elapsed", elapsed(c, sessionDay) + recorded)
                .putLong("started", now).putString("sessionDay", day())
                .putLong("heartbeat", now).putLong("lastFix", 0).putLong("lastRawFix", 0).apply();
    }
    static void mode(Context c, String mode) { prefs(c).edit().putString("mode", mode).apply(); }
    static void stop(Context c) {
        SharedPreferences p = prefs(c);
        String sessionDay = p.getString("sessionDay", day());
        long total = elapsed(c, sessionDay) + sessionElapsed(c);
        p.edit().putLong(sessionDay + ".elapsed", total).putBoolean("active", false).putLong("started", 0).apply();
    }
    static double score(Context c, String day) { return distance(c, day, WALK) + runFactor(c) * distance(c, day, RUN); }
    static String pace(double meters, long milliseconds) {
        if (meters < 10 || milliseconds <= 0) return "--:--";
        long secondsPerKm = Math.round(milliseconds * 1000.0 / meters / 1000.0);
        return String.format(Locale.US, "%02d:%02d", secondsPerKm / 60, secondsPerKm % 60);
    }
    static String duration(long milliseconds) {
        long s = milliseconds / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
