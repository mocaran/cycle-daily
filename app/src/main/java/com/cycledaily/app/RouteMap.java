package com.cycledaily.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class RouteMap extends WebView {
    record Point(double lon, double lat) { }
    private static final Point CAMPUS = AmapCoordinates.toMap(30.5373773, 114.3615350);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureDetector taps;
    private boolean ready;
    private boolean userMoved;
    private String pendingData;
    private Point pendingCenter = CAMPUS;
    private Point shownFix;
    private float downX, downY;

    RouteMap(Context context) {
        super(context);
        setBackgroundColor(0xffdce8e6);
        setContentDescription("高德地图与今日轨迹");
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }
        });
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if ("https".equals(request.getUrl().getScheme())
                        && "appassets.androidplatform.net".equals(request.getUrl().getHost())
                        && "/assets/amap.html".equals(request.getUrl().getPath())) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8", getContext().getAssets().open("amap.html"));
                    } catch (IOException ignored) { }
                }
                return super.shouldInterceptRequest(view, request);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith("https://appassets.androidplatform.net/assets/amap.html"))
                    evaluateJavascript("window.CycleDailyMapReady === true", value -> {
                        if ("true".equals(value)) markReady();
                    });
            }
        });
        addJavascriptInterface(new Object() {
            @JavascriptInterface public void ready() { handler.post(RouteMap.this::markReady); }
        }, "CycleDailyBridge");
        loadUrl("https://appassets.androidplatform.net/assets/amap.html");
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        if (pendingCenter != null) center(pendingCenter);
        if (pendingData != null) evaluateJavascript("window.renderNative(" + pendingData + ")", null);
        pendingCenter = null;
        pendingData = null;
    }

    void syncZoom() {
        if (ready) evaluateJavascript("window.syncZoomNative()", null);
    }

    private void center(Point point) {
        if (point == null) return;
        if (ready) evaluateJavascript("window.centerNative(" + point.lon() + "," + point.lat() + ")", null);
        else pendingCenter = point;
    }

    void update(List<RouteStore.Segment> segments, Point fix) {
        try {
            JSONObject data = new JSONObject();
            JSONArray paths = new JSONArray();
            List<Point> path = new ArrayList<>();
            String mode = null;
            for (RouteStore.Segment segment : segments) {
                Point a = AmapCoordinates.toMap(segment.startLat, segment.startLon);
                Point b = AmapCoordinates.toMap(segment.endLat, segment.endLon);
                boolean connected = !path.isEmpty() && path.get(path.size() - 1).equals(a)
                        && segment.mode.equals(mode);
                if (!connected) {
                    addPath(paths, path, mode);
                    path = new ArrayList<>();
                    path.add(a);
                    mode = segment.mode;
                }
                path.add(b);
            }
            addPath(paths, path, mode);
            data.put("paths", paths);
            data.put("point", fix == null ? JSONObject.NULL : coordinate(fix));
            String json = data.toString();
            if (ready) evaluateJavascript("window.renderNative(" + json + ")", null);
            else pendingData = json;
            if (fix != null && !fix.equals(shownFix) && !userMoved) center(fix);
            shownFix = fix;
        } catch (JSONException ignored) { }
    }

    private static void addPath(JSONArray paths, List<Point> points, String mode) throws JSONException {
        if (points.size() < 2) return;
        JSONArray path = new JSONArray();
        for (Point point : points) path.put(coordinate(point));
        JSONObject line = new JSONObject();
        line.put("points", path);
        line.put("color", TrackStore.RUN.equals(mode) ? "#ffa86b" : "#c8f17e");
        paths.put(line);
    }

    private static JSONArray coordinate(Point point) throws JSONException {
        JSONArray result = new JSONArray();
        result.put(point.lon()); result.put(point.lat());
        return result;
    }

    static Point currentPoint(Context context) {
        SharedPreferences p = TrackStore.prefs(context);
        String day = TrackStore.day();
        long now = System.currentTimeMillis();
        boolean tracking = day.equals(p.getString("lastFixDay", "")) && p.contains("lastLat")
                && now - p.getLong("lastFix", 0) <= 120000;
        boolean mapFix = day.equals(p.getString("mapFixDay", "")) && p.contains("mapLat")
                && now - p.getLong("mapFixAt", 0) <= 120000;
        if (!tracking && !mapFix) return null;
        boolean useMap = mapFix && (!tracking || p.getLong("mapFixAt", 0) > p.getLong("lastFix", 0));
        double lat = Double.longBitsToDouble(p.getLong(useMap ? "mapLat" : "lastLat", 0));
        double lon = Double.longBitsToDouble(p.getLong(useMap ? "mapLon" : "lastLon", 0));
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
            return null;
        return AmapCoordinates.toMap(lat, lon);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getPointerCount() > 1 || (action == MotionEvent.ACTION_MOVE
                && (Math.abs(event.getX() - downX) > 6 || Math.abs(event.getY() - downY) > 6)))
            userMoved = true;
        taps.onTouchEvent(event);
        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
