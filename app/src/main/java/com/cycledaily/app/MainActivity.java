package com.cycledaily.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends Activity {
    private static final int BG = 0xff101815, PANEL = 0xff1b2621, MUTED = 0xff93a99b, LIME = 0xffc8f17e;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String selectedMode = TrackStore.WALK;
    private ProgressRing ring;
    private TextView progress, remaining, walkValue, runValue, paceValue, timeValue, status;
    private Button walkButton, runButton, mainButton;
    private Button deductButton, resetButton;
    private EditText deductInput;
    private EditText goalInput, factorInput;
    private TextView correctionMode;
    private FrameLayout mapContainer;
    private TextView mapIssue;
    private LinearLayout rootView;
    private RouteMap mapView;
    private int renderedSegments = -1;
    private String renderedDay = "";
    private RouteMap.Point renderedFix;
    private boolean activityResumed;
    private long lastMapLocationRequestAt;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TrackStore.pruneOldDays(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        selectedMode = TrackStore.mode(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        rootView = root;
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        root.setFocusableInTouchMode(true);
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams heroLp = lp(-1, -2); heroLp.topMargin = dp(12);
        root.addView(hero, heroLp);
        progress = label("0.00 / 6.00 km", 29, Color.WHITE, true);
        progress.setGravity(Gravity.CENTER);
        hero.addView(progress);
        ring = new ProgressRing();
        LinearLayout.LayoutParams ringLp = lp(dp(190), dp(190)); ringLp.topMargin = dp(6);
        hero.addView(ring, ringLp);
        remaining = label("还差 6.00 km", 13, MUTED, false);
        remaining.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams remLp = lp(-1, -2); remLp.topMargin = dp(5);
        hero.addView(remaining, remLp);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modesLp = lp(-1, dp(54)); modesLp.topMargin = dp(18);
        root.addView(modes, modesLp);
        walkButton = modeButton("步行  ×1");
        runButton = modeButton("跑步  ×2");
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -1, 1); wl.rightMargin = dp(6);
        modes.addView(walkButton, wl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1); rl.leftMargin = dp(6);
        modes.addView(runButton, rl);
        walkButton.setOnClickListener(v -> selectMode(TrackStore.WALK));
        runButton.setOnClickListener(v -> selectMode(TrackStore.RUN));

        mainButton = new Button(this);
        mainButton.setAllCaps(false);
        mainButton.setTextSize(17);
        mainButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mainButton.setBackground(rounded(LIME, 18));
        LinearLayout.LayoutParams buttonLp = lp(-1, dp(60)); buttonLp.topMargin = dp(18);
        root.addView(mainButton, buttonLp);
        mainButton.setOnClickListener(v -> toggle());
        status = label("", 12, MUTED, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = lp(-1, -2); statusLp.topMargin = dp(12);
        root.addView(status, statusLp);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metricsLp = lp(-1, -2); metricsLp.topMargin = dp(28);
        root.addView(metrics, metricsLp);
        walkValue = metric(metrics, "步行距离", "0.00", "km", true);
        runValue = metric(metrics, "跑步距离", "0.00", "km", false);
        LinearLayout metrics2 = new LinearLayout(this);
        metrics2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metrics2Lp = lp(-1, -2); metrics2Lp.topMargin = dp(12);
        root.addView(metrics2, metrics2Lp);
        paceValue = metric(metrics2, "平均配速", "--:--", "/km", true);
        timeValue = metric(metrics2, "运动时间", "00:00:00", "", false);

        LinearLayout correction = column();
        LinearLayout.LayoutParams correctionLp = lp(-1, -2); correctionLp.topMargin = dp(25);
        root.addView(correction, correctionLp);
        correctionMode = label("纠错 · 扣除步行里程", 13, MUTED, false);
        correction.addView(correctionMode);
        deductInput = new EditText(this);
        deductInput.setSingleLine(true);
        deductInput.setTextColor(Color.WHITE);
        deductInput.setHintTextColor(MUTED);
        deductInput.setHint("输入要扣除的公里数");
        deductInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        correction.addView(deductInput, lp(-1, dp(54)));
        deductButton = modeButton("扣除里程");
        deductButton.setTextColor(BG);
        deductButton.setBackground(rounded(LIME, 14));
        LinearLayout.LayoutParams deductLp = lp(-1, dp(48)); deductLp.topMargin = dp(8);
        correction.addView(deductButton, deductLp);
        deductButton.setOnClickListener(v -> confirmDeduct());
        resetButton = modeButton("重置今天进度");
        resetButton.setTextColor(Color.WHITE);
        resetButton.setBackground(rounded(PANEL, 14));
        LinearLayout.LayoutParams resetLp = lp(-1, dp(48)); resetLp.topMargin = dp(10);
        correction.addView(resetButton, resetLp);
        resetButton.setOnClickListener(v -> confirmReset());

        LinearLayout settings = column();
        LinearLayout.LayoutParams settingsLp = lp(-1, -2); settingsLp.topMargin = dp(28);
        root.addView(settings, settingsLp);
        settings.addView(label("每日目标设置", 16, Color.WHITE, true));
        TextView goalLabel = label("每日目标里程（km）", 13, MUTED, false);
        LinearLayout.LayoutParams goalLabelLp = lp(-1, -2); goalLabelLp.topMargin = dp(12);
        settings.addView(goalLabel, goalLabelLp);
        goalInput = settingInput(String.format(Locale.US, "%.2f", TrackStore.goalKm(this)));
        settings.addView(goalInput, lp(-1, dp(50)));
        TextView factorLabel = label("跑步 1 km 折算步行里程（km）", 13, MUTED, false);
        LinearLayout.LayoutParams factorLabelLp = lp(-1, -2); factorLabelLp.topMargin = dp(10);
        settings.addView(factorLabel, factorLabelLp);
        factorInput = settingInput(String.format(Locale.US, "%.2f", TrackStore.runFactor(this)));
        settings.addView(factorInput, lp(-1, dp(50)));
        Button saveGoalButton = modeButton("保存目标设置");
        saveGoalButton.setTextColor(BG);
        saveGoalButton.setBackground(rounded(LIME, 14));
        LinearLayout.LayoutParams saveGoalLp = lp(-1, dp(48)); saveGoalLp.topMargin = dp(10);
        settings.addView(saveGoalButton, saveGoalLp);
        saveGoalButton.setOnClickListener(v -> saveGoal());

        mapContainer = new FrameLayout(this);
        LinearLayout.LayoutParams mapLp = lp(-1, dp(260));
        root.addView(mapContainer, mapLp);
        createMap();
        // Preview map, mode selector, distance, then circular progress.
        root.removeView(mapContainer);
        root.addView(mapContainer, 0, mapLp);
        root.removeView(modes);
        root.addView(modes, 1, modesLp);
        View firstScreenSpacer = new View(this);
        root.addView(firstScreenSpacer, 5, lp(-1, dp(48)));
        root.post(() -> {
            // Use extra screen height for the map while keeping a similar gap below Start.
            int availableBelowButton = scroll.getHeight() - root.getPaddingTop() - mainButton.getBottom();
            int extraMapHeight = Math.min(dp(60), Math.max(0, availableBelowButton - dp(38)));
            mapLp.height = dp(260) + extraMapHeight;
            mapContainer.setLayoutParams(mapLp);
            firstScreenSpacer.getLayoutParams().height = Math.max(dp(48), availableBelowButton - extraMapHeight);
            firstScreenSpacer.requestLayout();
        });
        root.requestFocus();
        refresh();
    }

    private TextView metric(LinearLayout row, String name, String value, String unit, boolean left) {
        LinearLayout card = column();
        card.setPadding(dp(6), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, dp(72), 1);
        if (left) cardLp.rightMargin = dp(6); else cardLp.leftMargin = dp(6);
        row.addView(card, cardLp);
        card.addView(label(name, 12, MUTED, false));
        TextView number = label(value + " " + unit, 19, Color.WHITE, true);
        LinearLayout.LayoutParams nLp = lp(-1, -2); nLp.topMargin = dp(10);
        card.addView(number, nLp);
        return number;
    }

    private EditText settingInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value);
        return input;
    }

    private void saveGoal() {
        double target, factor;
        try {
            target = Double.parseDouble(goalInput.getText().toString().trim());
            factor = Double.parseDouble(factorInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (target < 0.1 || target > 100 || !Double.isFinite(target)) {
            goalInput.setError("目标须在 0.1～100 km 之间"); return;
        }
        if (factor < 0.1 || factor > 100 || !Double.isFinite(factor)) {
            factorInput.setError("折算倍数须在 0.1～100 之间"); return;
        }
        if (!TrackStore.setGoal(this, target, factor)) {
            Toast.makeText(this, "设置保存失败", Toast.LENGTH_SHORT).show(); return;
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(factorInput.getWindowToken(), 0);
        factorInput.clearFocus(); goalInput.clearFocus(); rootView.requestFocus();
        refresh();
        Toast.makeText(this, "目标设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void selectMode(String mode) {
        selectedMode = mode;
        if (TrackStore.active(this)) {
            Intent i = new Intent(this, TrackingService.class).setAction(TrackingService.MODE).putExtra("mode", mode);
            startService(i);
        }
        refresh();
    }

    private void confirmDeduct() {
        if (TrackStore.active(this)) { Toast.makeText(this, "请先结束本次记录", Toast.LENGTH_SHORT).show(); return; }
        String raw = deductInput.getText().toString().trim();
        double km;
        try { km = Double.parseDouble(raw); }
        catch (NumberFormatException e) { deductInput.setError("请输入公里数"); return; }
        if (!Double.isFinite(km) || km <= 0) { deductInput.setError("请输入大于 0 的公里数"); return; }
        String mode = selectedMode;
        double meters = km * 1000;
        double available = TrackStore.distance(this, TrackStore.day(), mode);
        if (meters > available + 0.01) {
            deductInput.setError(String.format(Locale.CHINA, "最多可扣除 %.3f km", available / 1000));
            return;
        }
        String name = TrackStore.RUN.equals(mode) ? "跑步" : "步行";
        new AlertDialog.Builder(this).setTitle("确认扣除里程")
                .setMessage(String.format(Locale.CHINA, "从今天的%s里程扣除 %.3f km？对应的最新轨迹也会删除。", name, km))
                .setNegativeButton("取消", null)
                .setPositiveButton("扣除", (dialog, which) -> {
                    if (TrackStore.deductToday(this, mode, meters)) {
                        deductInput.setText(""); renderedSegments = -1; refresh();
                    } else Toast.makeText(this, "扣除失败，请结束记录后重试", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void confirmReset() {
        if (TrackStore.active(this)) { Toast.makeText(this, "请先结束本次记录", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("重置今天进度")
                .setMessage("清空今天的步行、跑步里程、运动时间和轨迹？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> {
                    if (TrackStore.resetToday(this)) {
                        deductInput.setText(""); renderedSegments = -1; refresh();
                    } else Toast.makeText(this, "重置失败，请重试", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void toggle() {
        if (TrackStore.active(this)) {
            startService(new Intent(this, TrackingService.class).setAction(TrackingService.STOP));
            handler.postDelayed(this::refresh, 150);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Toast.makeText(this, "请先开启手机定位", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        try {
            startForegroundService(new Intent(this, TrackingService.class).setAction(TrackingService.START).putExtra("mode", selectedMode));
            handler.postDelayed(this::refresh, 150);
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法开始记录，请检查定位权限", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                MapLocation.request(this, this::refresh);
                toggle();
            }
            else Toast.makeText(this, "记录路程需要精确位置权限", Toast.LENGTH_LONG).show();
        }
        if (code == 3 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            lastMapLocationRequestAt = android.os.SystemClock.elapsedRealtime();
            MapLocation.request(this, this::refresh);
        }
    }

    private void refresh() {
        if (progress == null) return;
        long requestAt = android.os.SystemClock.elapsedRealtime();
        if (activityResumed && requestAt - lastMapLocationRequestAt >= 15000) {
            lastMapLocationRequestAt = requestAt;
            MapLocation.request(this, this::refresh);
        }
        String day = TrackStore.day();
        boolean active = TrackStore.active(this);
        if (active) selectedMode = TrackStore.mode(this);
        double walk = TrackStore.distance(this, day, TrackStore.WALK);
        double run = TrackStore.distance(this, day, TrackStore.RUN);
        double score = TrackStore.score(this, day);
        double target = TrackStore.goalKm(this) * 1000;
        long elapsed = TrackStore.elapsed(this, day);
        if (active && day.equals(TrackStore.prefs(this).getString("sessionDay", day))) elapsed += TrackStore.sessionElapsed(this);
        ring.setProgress((float)Math.min(1, score / target));
        progress.setText(String.format(Locale.CHINA, "%.2f / %.2f km", score / 1000, target / 1000));
        remaining.setText(score >= target ? "今日目标已完成 ✓" : String.format(Locale.CHINA, "还差 %.2f km", (target - score) / 1000));
        walkValue.setText(String.format(Locale.CHINA, "%.2f km", walk / 1000));
        runValue.setText(String.format(Locale.CHINA, "%.2f km", run / 1000));
        paceValue.setText(TrackStore.pace(walk + run, elapsed) + " /km");
        timeValue.setText(TrackStore.duration(elapsed));
        mainButton.setText(active ? "结束本次记录" : "开始记录");
        mainButton.setTextColor(active ? Color.WHITE : BG);
        mainButton.setBackground(rounded(active ? 0xff385547 : LIME, 18));
        long rawFix = TrackStore.prefs(this).getLong("lastRawFix", 0);
        float rawAccuracy = TrackStore.prefs(this).getFloat("lastRawAccuracy", -1);
        long heartbeat = TrackStore.prefs(this).getLong("heartbeat", 0);
        int rawCount = TrackStore.prefs(this).getInt("rawFixCount", 0);
        int validCount = TrackStore.prefs(this).getInt("validFixCount", 0);
        int segments = TrackStore.prefs(this).getInt("countedSegments", 0);
        if (!active) {
            String error = TrackStore.prefs(this).getString("lastStartError", "");
            status.setText(error);
            status.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        }
        else if (heartbeat == 0 || android.os.SystemClock.elapsedRealtime() - heartbeat > 5000)
            status.setText("计时服务未响应 · 当前没有可靠的路程记录");
        else if (rawFix == 0)
            status.setText("计时已启动 · 尚未收到手机定位");
        else if (System.currentTimeMillis() - rawFix > 20000)
            status.setText("计时已启动 · 定位回调中断，请检查定位开关");
        else if (rawAccuracy > 35)
            status.setText(String.format(Locale.CHINA, "计时已启动 · 收到 %d 个定位点，当前误差约 %.0f m，尚未计距", rawCount, rawAccuracy));
        else if (segments == 0)
            status.setText(String.format(Locale.CHINA, "计时已启动 · 有效定位 %d 个，尚未计入路程", validCount));
        else {
            long lastCounted = TrackStore.prefs(this).getLong("lastCountedAt", 0);
            if (lastCounted > 0 && System.currentTimeMillis() - lastCounted <= 20000)
                status.setText(String.format(Locale.CHINA, "定位已计算 %d 段 · 最近一段 +%.0f m", segments,
                        TrackStore.prefs(this).getFloat("lastSegmentMeters", 0)));
            else status.setText(String.format(Locale.CHINA, "定位已计算 %d 段 · 暂无新的移动", segments));
        }
        if (active) status.setVisibility(View.VISIBLE);
        walkButton.setBackground(rounded(TrackStore.WALK.equals(selectedMode) ? LIME : PANEL, 14));
        runButton.setBackground(rounded(TrackStore.RUN.equals(selectedMode) ? LIME : PANEL, 14));
        walkButton.setTextColor(TrackStore.WALK.equals(selectedMode) ? BG : Color.WHITE);
        runButton.setTextColor(TrackStore.RUN.equals(selectedMode) ? BG : Color.WHITE);
        runButton.setText(String.format(Locale.CHINA, "跑步  ×%s", new java.text.DecimalFormat("0.##").format(TrackStore.runFactor(this))));
        correctionMode.setText("纠错 · 扣除" + (TrackStore.RUN.equals(selectedMode) ? "跑步" : "步行") + "里程");
        deductButton.setEnabled(!active);
        resetButton.setEnabled(!active);
        List<RouteStore.Segment> route = RouteStore.read(this, day);
        RouteMap.Point fix = RouteMap.currentPoint(this);
        if (!day.equals(renderedDay) || route.size() != renderedSegments || !Objects.equals(fix, renderedFix)) {
            mapView.update(route, fix);
            mapIssue.setVisibility(fix == null && route.isEmpty() ? View.VISIBLE : View.GONE);
            renderedDay = day;
            renderedSegments = route.size();
            renderedFix = fix;
        }
    }

    private void createMap() {
        mapView = new RouteMap(this);
        mapView.setOnClickListener(v -> startActivity(new Intent(this, RouteActivity.class)));
        mapContainer.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        mapIssue = label("等待定位", 12, Color.WHITE, false);
        mapContainer.addView(mapIssue, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        TextView hint = label("离线轨迹 · 点按全屏", 11, Color.WHITE, false);
        hint.setBackgroundColor(0xaa101815);
        hint.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        mapContainer.addView(hint, hintParams);
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (rootView != null) {
            rootView.requestFocus();
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
        }
        if (TrackStore.active(this) && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            long heartbeat = TrackStore.prefs(this).getLong("heartbeat", 0);
            if (heartbeat == 0 || android.os.SystemClock.elapsedRealtime() - heartbeat > 10000) {
                try { startForegroundService(new Intent(this, TrackingService.class).setAction(TrackingService.START)); }
                catch (RuntimeException ignored) { }
            }
        }
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        mapView.syncZoom();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lastMapLocationRequestAt = android.os.SystemClock.elapsedRealtime();
            MapLocation.request(this, this::refresh);
        } else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
    }
    @Override protected void onPause() {
        activityResumed = false;
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button modeButton(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setPadding(0, 0, 0, 0); return b;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private class ProgressRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        ProgressRing() { super(MainActivity.this); }
        void setProgress(float v) { value = v; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - dp(18);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(12)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xff34463a); canvas.drawCircle(cx, cy, radius, paint);
            paint.setColor(LIME);
            RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(oval, -90, 360 * value, false, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER); paint.setColor(Color.WHITE); paint.setTextSize(dp(39));
            canvas.drawText(String.format(Locale.CHINA, "%d%%", Math.round(value * 100)), cx, cy + dp(10), paint);
            paint.setTypeface(Typeface.DEFAULT); paint.setColor(MUTED); paint.setTextSize(dp(12));
            canvas.drawText("今日完成", cx, cy + dp(34), paint);
        }
    }
}
