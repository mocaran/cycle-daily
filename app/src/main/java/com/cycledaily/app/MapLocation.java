package com.cycledaily.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

final class MapLocation {
    static void request(Activity activity, Runnable changed) {
        if (Build.VERSION.SDK_INT < 30 || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        LocationManager manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
        for (String provider : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    save(activity, manager.getLastKnownLocation(provider), changed);
                    manager.getCurrentLocation(provider, null, activity.getMainExecutor(),
                            location -> save(activity, location, changed));
                }
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
    }

    private static void save(Activity activity, Location location, Runnable changed) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 100
                || Math.abs(System.currentTimeMillis() - location.getTime()) > 60000) return;
        long previousTime = TrackStore.prefs(activity).getLong("mapFixAt", 0);
        float previousAccuracy = TrackStore.prefs(activity).getFloat("mapAccuracy", Float.MAX_VALUE);
        if (System.currentTimeMillis() - previousTime < 15000 && location.getAccuracy() >= previousAccuracy) return;
        TrackStore.prefs(activity).edit()
                .putString("mapFixDay", TrackStore.day())
                .putLong("mapFixAt", System.currentTimeMillis())
                .putFloat("mapAccuracy", location.getAccuracy())
                .putLong("mapLat", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("mapLon", Double.doubleToRawLongBits(location.getLongitude()))
                .apply();
        changed.run();
    }
}
