package com.cycledaily.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;

public class RouteActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RouteMap map;
    private int shownSegments = -1;
    private RouteMap.Point shownFix;
    private String shownDay = "";
    private long lastMapLocationRequestAt;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long requestAt = android.os.SystemClock.elapsedRealtime();
            if (requestAt - lastMapLocationRequestAt >= 15000) {
                lastMapLocationRequestAt = requestAt;
                MapLocation.request(RouteActivity.this, RouteActivity.this::updateLocation);
            }
            updateLocation();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        map = new RouteMap(this);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        TextView back = new TextView(this);
        back.setText("‹  返回"); back.setTextColor(Color.WHITE); back.setTextSize(17);
        back.setPadding(dp(18), dp(15), dp(18), dp(15));
        back.setBackgroundColor(0xbb101815);
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        backLp.setMargins(dp(14), dp(22), 0, 0);
        root.addView(back, backLp);
        back.setOnClickListener(v -> finish());
        TextView credit = new TextView(this);
        credit.setText("蓝点 · 当前位置  ·  高德地图");
        credit.setTextColor(Color.WHITE); credit.setTextSize(11);
        credit.setPadding(dp(8), dp(6), dp(8), dp(6));
        credit.setBackgroundColor(0xbb101815);
        FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        creditLp.setMargins(0, 0, dp(9), dp(14));
        root.addView(credit, creditLp);
        setContentView(root);
    }
    @Override protected void onResume() {
        super.onResume();
        lastMapLocationRequestAt = android.os.SystemClock.elapsedRealtime();
        MapLocation.request(this, this::updateLocation);
        handler.post(ticker);
    }
    private void updateLocation() {
        String day = TrackStore.day();
        List<RouteStore.Segment> route = RouteStore.read(this, day);
        RouteMap.Point fix = RouteMap.currentPoint(this);
        if (!day.equals(shownDay) || route.size() != shownSegments || !Objects.equals(fix, shownFix)) {
            map.update(route, fix);
            shownDay = day;
            shownSegments = route.size();
            shownFix = fix;
        }
    }
    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
}
