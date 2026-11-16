package com.cycledaily.app;

final class AmapCoordinates {
    private static final double PI = Math.PI, A = 6378245.0, EE = 0.006693421622965943;
    private AmapCoordinates() { }

    static RouteMap.Point toMap(double lat, double lon) {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271)
            return new RouteMap.Point(lon, lat);
        double dLat = latitudeOffset(lon - 105.0, lat - 35.0);
        double dLon = longitudeOffset(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrt = Math.sqrt(magic);
        dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrt) * PI);
        dLon = dLon * 180.0 / (A / sqrt * Math.cos(radLat) * PI);
        return new RouteMap.Point(lon + dLon, lat + dLat);
    }

    private static double latitudeOffset(double x, double y) {
        double value = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x));
        value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
        value += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
        return value + (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
    }

    private static double longitudeOffset(double x, double y) {
        double value = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x));
        value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
        value += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
        return value + (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
    }
}
