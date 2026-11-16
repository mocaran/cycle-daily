package com.cycledaily.app;

import android.content.Context;
import android.location.Location;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class RouteStore {
    static final class Segment {
        final double startLat, startLon, endLat, endLon;
        final String mode;
        Segment(double a, double b, double c, double d, String mode) {
            startLat = a; startLon = b; endLat = c; endLon = d; this.mode = mode;
        }
    }
    private static File file(Context c, String day) {
        File dir = new File(c.getFilesDir(), "routes");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, day + ".jsonl");
    }
    static boolean append(Context c, String day, Location a, Location b, String mode) {
        try {
            JSONObject item = new JSONObject();
            item.put("a", a.getLatitude()); item.put("b", a.getLongitude());
            item.put("c", b.getLatitude()); item.put("d", b.getLongitude());
            item.put("mode", mode); item.put("time", b.getTime());
            String line = item.toString() + "\n";
            try (FileOutputStream out = new FileOutputStream(file(c, day), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) { return false; }
    }
    static boolean clear(Context c, String day) {
        File target = file(c, day);
        return !target.exists() || target.delete();
    }
    // Remove the newest distance of this mode so the drawn route matches manual corrections.
    static boolean trimLatest(Context c, String day, String mode, double meters) {
        File target = file(c, day);
        if (!target.exists()) return true;
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(target))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception e) { return false; }
        double remaining = meters;
        for (int i = lines.size() - 1; i >= 0 && remaining > 0.001; i--) {
            try {
                JSONObject item = new JSONObject(lines.get(i));
                if (!mode.equals(item.getString("mode"))) continue;
                double a = item.getDouble("a"), b = item.getDouble("b");
                double endLat = item.getDouble("c"), endLon = item.getDouble("d");
                float[] result = new float[1];
                Location.distanceBetween(a, b, endLat, endLon, result);
                double length = result[0];
                if (length <= remaining + 0.01) {
                    lines.remove(i);
                    remaining -= length;
                } else {
                    double keep = (length - remaining) / length;
                    item.put("c", a + (endLat - a) * keep);
                    item.put("d", b + (endLon - b) * keep);
                    lines.set(i, item.toString());
                    remaining = 0;
                }
            } catch (Exception e) { return false; }
        }
        File temporary = new File(target.getParentFile(), day + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            for (String line : lines) out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { temporary.delete(); return false; }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) { temporary.delete(); return false; }
    }
    static List<Segment> read(Context c, String day) {
        List<Segment> result = new ArrayList<>();
        File f = file(c, day);
        if (!f.exists()) return result;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject j = new JSONObject(line);
                    result.add(new Segment(j.getDouble("a"), j.getDouble("b"), j.getDouble("c"), j.getDouble("d"), j.getString("mode")));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }
}
