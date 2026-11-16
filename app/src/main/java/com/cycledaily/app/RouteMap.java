package com.cycledaily.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

final class RouteMap extends View {
    private static final double EARTH_RADIUS = 6378137;
    private static final double WORLD_WIDTH = 2 * Math.PI * EARTH_RADIUS;
    private static final double HALF_WORLD = WORLD_WIDTH / 2;

    record Point(double x, double y) {
        static Point fromLocation(double latitude, double longitude) {
            double radians = Math.toRadians(Math.max(-85.051129, Math.min(85.051129, latitude)));
            return new Point(EARTH_RADIUS * Math.toRadians(longitude),
                    -EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + radians / 2)));
        }
    }

    private record Line(Point start, Point end, int color) {}

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Line> lines = new ArrayList<>();
    private final ScaleGestureDetector pinch;
    private final GestureDetector drag;
    private final float density;
    private Point position;
    private Point center;
    private float zoom;
    private boolean userMoved;
    private boolean multiTouch;
    private boolean zoomChanged;

    RouteMap(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xff1b2621);
        setContentDescription("离线轨迹");
        syncZoom();
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        drag = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                if (!multiTouch && center != null) {
                    center = new Point(wrap(center.x + dx / scale()), clampY(center.y + dy / scale()));
                    userMoved = true;
                    invalidate();
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                return !multiTouch && performClick();
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                zoomAt(2, event.getX(), event.getY());
                return true;
            }
        });
    }

    void syncZoom() {
        zoom = TrackStore.prefs(getContext()).getFloat("offlineRouteZoom", 2f);
        invalidate();
    }

    void update(List<RouteStore.Segment> segments, Point fix) {
        lines.clear();
        for (RouteStore.Segment segment : segments) {
            lines.add(new Line(Point.fromLocation(segment.startLat, segment.startLon),
                    Point.fromLocation(segment.endLat, segment.endLon),
                    TrackStore.RUN.equals(segment.mode) ? 0xffffa86b : 0xffc8f17e));
        }
        boolean fixChanged = fix != null && !fix.equals(position);
        position = fix;
        if (fixChanged && !userMoved) center = fix;
        if (center == null && !lines.isEmpty()) center = lines.get(lines.size() - 1).end;
        invalidate();
    }

    static Point currentPoint(Context context) {
        SharedPreferences prefs = TrackStore.prefs(context);
        String day = TrackStore.day();
        long now = System.currentTimeMillis();
        boolean tracking = day.equals(prefs.getString("lastFixDay", "")) && prefs.contains("lastLat")
                && now - prefs.getLong("lastFix", 0) <= 120000;
        boolean mapFix = day.equals(prefs.getString("mapFixDay", "")) && prefs.contains("mapLat")
                && now - prefs.getLong("mapFixAt", 0) <= 120000;
        if (!tracking && !mapFix) return null;
        boolean useMap = mapFix && (!tracking || prefs.getLong("mapFixAt", 0) > prefs.getLong("lastFix", 0));
        double lat = Double.longBitsToDouble(prefs.getLong(useMap ? "mapLat" : "lastLat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong(useMap ? "mapLon" : "lastLon", 0));
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
            return null;
        return Point.fromLocation(lat, lon);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getPointerCount() > 1) multiTouch = true;
        pinch.onTouchEvent(event);
        drag.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            if (zoomChanged) {
                TrackStore.prefs(getContext()).edit().putFloat("offlineRouteZoom", zoom).apply();
                zoomChanged = false;
            }
        }
        return true;
    }

    private void zoomAt(float factor, float x, float y) {
        if (center == null) return;
        double oldScale = scale();
        zoom = Math.max(0.025f, Math.min(24f, zoom * factor));
        double shift = 1 / oldScale - 1 / scale();
        center = new Point(wrap(center.x + (x - getWidth() / 2f) * shift),
                clampY(center.y + (y - getHeight() / 2f) * shift));
        userMoved = true;
        zoomChanged = true;
        invalidate();
    }

    private double scale() { return zoom * density; }

    private static double wrap(double x) {
        return x - WORLD_WIDTH * Math.floor((x + HALF_WORLD) / WORLD_WIDTH);
    }

    private static double clampY(double y) { return Math.max(-HALF_WORLD, Math.min(HALF_WORLD, y)); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        double scale = scale();
        drawGrid(canvas, scale);
        if (center == null) return;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4 * density);
        for (Line line : lines) {
            double startX = wrap(line.start.x - center.x);
            double endX = startX + wrap(line.end.x - line.start.x);
            paint.setColor(line.color);
            canvas.drawLine(cx + (float) (startX * scale), cy + (float) ((line.start.y - center.y) * scale),
                    cx + (float) (endX * scale), cy + (float) ((line.end.y - center.y) * scale), paint);
        }
        if (position != null) {
            float x = cx + (float) (wrap(position.x - center.x) * scale);
            float y = cy + (float) ((position.y - center.y) * scale);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, 7 * density, paint);
            paint.setColor(0xff3889ed);
            canvas.drawCircle(x, y, 5 * density, paint);
        }
    }

    private void drawGrid(Canvas canvas, double scale) {
        double spacing = Math.pow(2, Math.ceil(Math.log(32 * density / scale) / Math.log(2))) * scale;
        double xStart = center == null ? 0 : (getWidth() / 2.0 - center.x * scale) % spacing;
        double yStart = center == null ? 0 : (getHeight() / 2.0 - center.y * scale) % spacing;
        paint.setColor(0xff314038);
        paint.setStrokeWidth(density);
        for (double x = xStart; x < getWidth(); x += spacing)
            canvas.drawLine((float) x, 0, (float) x, getHeight(), paint);
        for (double y = yStart; y < getHeight(); y += spacing)
            canvas.drawLine(0, (float) y, getWidth(), (float) y, paint);
    }
}
