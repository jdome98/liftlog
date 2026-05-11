package com.splitstak.app.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Single source of truth for the widget. The PWA publishes a snapshot of the
 * current day's exercises (sets/cardio/step sizes/timer config); the widget
 * reads from that snapshot, and button taps mutate it locally and queue
 * actions that the PWA drains the next time it comes to the foreground.
 *
 * Mutations always: load the snapshot, mutate the in-memory tree, save the
 * mutated tree atomically.
 */
public class WidgetState {

    private static final String PREFS = "splitstak_widget";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_WIDGET_SELECTED_ID = "widget_selected_id";

    private final Context ctx;
    private final SharedPreferences prefs;

    public WidgetState(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSnapshot(JSONObject snap) {
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        String widgetId = prefs.getString(KEY_WIDGET_SELECTED_ID, null);
        if (widgetId == null || !exerciseIdExists(snap, widgetId)) {
            String pwaId = snap.optString("selectedExerciseId", null);
            if (pwaId == null || pwaId.isEmpty()) {
                JSONArray ex = snap.optJSONArray("exercises");
                if (ex != null && ex.length() > 0) {
                    pwaId = ex.optJSONObject(0).optString("id", null);
                }
            }
            prefs.edit().putString(KEY_WIDGET_SELECTED_ID, pwaId).apply();
        }
    }

    public JSONObject loadSnapshot() {
        String raw = prefs.getString(KEY_SNAPSHOT, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    public String getWidgetSelectedId() {
        return prefs.getString(KEY_WIDGET_SELECTED_ID, null);
    }

    public void setWidgetSelectedId(String id) {
        prefs.edit().putString(KEY_WIDGET_SELECTED_ID, id).apply();
    }

    public JSONObject getCurrentExercise() {
        return findCurrentExerciseIn(loadSnapshot());
    }

    public void selectByDelta(int delta) {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONArray ex = snap.optJSONArray("exercises");
        if (ex == null || ex.length() == 0) return;
        int cur = indexOfId(ex, getWidgetSelectedId());
        if (cur < 0) cur = 0;
        int next = ((cur + delta) % ex.length() + ex.length()) % ex.length();
        JSONObject newEx = ex.optJSONObject(next);
        if (newEx == null) return;
        String newId = newEx.optString("id", null);
        if (newId == null) return;
        setWidgetSelectedId(newId);
        enqueue(actionWith("select", newId, -1, 0, null));
    }

    /**
     * Strength inc: +/- delta*step on a set's weight or reps. Integer math,
     * clamped at 0, written as plain integer string.
     */
    public void applyIncStrength(String field, int setIdx, int delta, int step) {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONObject ex = findCurrentExerciseIn(snap);
        if (ex == null) return;
        JSONArray sets = ex.optJSONArray("sets");
        if (sets == null || setIdx < 0 || setIdx >= sets.length()) return;
        JSONObject set = sets.optJSONObject(setIdx);
        if (set == null) return;
        String key = "w".equals(field) ? "w" : "r";
        int current = parseLooseInt(set.optString(key, ""));
        int next = Math.max(0, current + delta * step);
        try {
            set.put(key, String.valueOf(next));
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        enqueue(actionWith("w".equals(field) ? "inc_weight" : "inc_reps",
                ex.optString("id"), setIdx, delta * step, null));
    }

    /**
     * Timed-hold inc: +/- delta*step on a set's seconds field. Used for
     * Plank, Dead Hang, Farmer's Walk and other mode='time' exercises.
     * Integer seconds, clamped at 0, written as plain integer string.
     */
    public void applyIncHold(int setIdx, int delta, int step) {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONObject ex = findCurrentExerciseIn(snap);
        if (ex == null) return;
        JSONArray sets = ex.optJSONArray("sets");
        if (sets == null || setIdx < 0 || setIdx >= sets.length()) return;
        JSONObject set = sets.optJSONObject(setIdx);
        if (set == null) return;
        int current = parseLooseInt(set.optString("t", ""));
        int next = Math.max(0, current + delta * step);
        try {
            set.put("t", String.valueOf(next));
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        enqueue(actionWith("inc_hold", ex.optString("id"), setIdx, delta * step, null));
    }

    /**
     * Cardio inc: +/- delta*step on time (minutes) or distance (miles).
     * Decimal math, clamped at 0, written as integer string when whole, else
     * 1-decimal string (matching the PWA's free-form numeric format).
     */
    public void applyIncCardio(String field, int delta, double step) {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONObject ex = findCurrentExerciseIn(snap);
        if (ex == null) return;
        JSONObject cardio = ex.optJSONObject("cardio");
        if (cardio == null) return;
        String key = "time".equals(field) ? "time" : "distance";
        double current = parseLooseDouble(cardio.optString(key, ""));
        double next = Math.max(0.0, current + delta * step);
        try {
            cardio.put(key, formatNumber(next));
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        JSONObject act = new JSONObject();
        try {
            act.put("type", "time".equals(field) ? "inc_time" : "inc_distance");
            act.put("exerciseId", ex.optString("id"));
            act.put("delta", delta * step);
            act.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        enqueue(act);
    }

    /**
     * Toggle done on the currently-displayed exercise. Routes to strength
     * (sets[setIdx].d) or cardio (cardio.done) based on the exercise kind.
     * If turning ON and the snapshot has timerEnabled+timerDuration, also
     * stamps restEndsAt so the widget's countdown bar fires immediately.
     */
    public void applyToggleDone(int setIdx) {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONObject ex = findCurrentExerciseIn(snap);
        if (ex == null) return;
        boolean turnedOn;
        String kind = ex.optString("kind", "strength");
        if ("cardio".equals(kind)) {
            JSONObject cardio = ex.optJSONObject("cardio");
            if (cardio == null) return;
            boolean wasDone = cardio.optBoolean("done", false);
            turnedOn = !wasDone;
            try {
                cardio.put("done", !wasDone);
                ex.put("allComplete", !wasDone);
            } catch (JSONException ignored) {
                return;
            }
        } else {
            JSONArray sets = ex.optJSONArray("sets");
            if (sets == null || setIdx < 0 || setIdx >= sets.length()) return;
            JSONObject set = sets.optJSONObject(setIdx);
            if (set == null) return;
            boolean wasDone = set.optBoolean("d", false);
            turnedOn = !wasDone;
            try {
                set.put("d", !wasDone);
                boolean all = true;
                for (int i = 0; i < sets.length(); i++) {
                    JSONObject s = sets.optJSONObject(i);
                    if (s == null || !s.optBoolean("d", false)) { all = false; break; }
                }
                ex.put("allComplete", all);
            } catch (JSONException ignored) {
                return;
            }
        }

        // Start rest timer if we just ticked ON and the user has it enabled.
        // Mirror the in-app behavior at index.html:handleSetDone.
        boolean firedTimer = false;
        int firedDurationSec = 0;
        if (turnedOn) {
            boolean timerEnabled = snap.optBoolean("timerEnabled", true);
            int timerDurationSec = snap.optInt("timerDuration", 90);
            if (timerEnabled && timerDurationSec > 0) {
                long endsAt = System.currentTimeMillis() + timerDurationSec * 1000L;
                try {
                    snap.put("restEndsAt", endsAt);
                } catch (JSONException ignored) {}
                JSONObject startAct = new JSONObject();
                try {
                    startAct.put("type", "start_rest");
                    startAct.put("restEndsAt", endsAt);
                    startAct.put("ts", System.currentTimeMillis());
                } catch (JSONException ignored) {}
                enqueue(startAct);
                firedTimer = true;
                firedDurationSec = timerDurationSec;
            }
        }

        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        enqueue(actionWith("toggle_done", ex.optString("id"), setIdx, 0, null));

        // Schedule native local notifications (warning + finish) so the user
        // gets lock-screen alerts even with the app killed. Web Push doesn't
        // work reliably under capacitor://localhost, so we don't use the
        // same worker pipeline the PWA uses here.
        if (firedTimer) {
            long endsAt = System.currentTimeMillis() + firedDurationSec * 1000L;
            WidgetNotifications.scheduleRestNotifications(ctx, endsAt);
        }
    }

    public void applyDismissRest() {
        clearRest();
        WidgetNotifications.cancelRestNotifications(ctx);
    }

    /**
     * Same as applyDismissRest but does NOT cancel the push schedule. Used
     * by the alarm-driven expiry path: at endsAt the finish push is firing
     * (or just fired), so we clear the visual but leave the worker alone.
     */
    public void applyExpireRest() {
        clearRest();
    }

    private void clearRest() {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        try {
            snap.put("restEndsAt", JSONObject.NULL);
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();
        JSONObject a = new JSONObject();
        try {
            a.put("type", "dismiss_rest");
            a.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        enqueue(a);
    }


    /**
     * Widget-side finish-day. Resets every exercise's done flags + cardio
     * done flag in the snapshot so the widget instantly shows empty ticks
     * (no app launch needed), and queues a finish_day action so JS does
     * the real history rewrite + PR detection the NEXT time the PWA runs.
     * Until then, weight/reps survive and stay where the user left them.
     */
    public void applyFinishDay() {
        JSONObject snap = loadSnapshot();
        if (snap == null) return;
        JSONArray exercises = snap.optJSONArray("exercises");
        if (exercises == null) return;
        for (int i = 0; i < exercises.length(); i++) {
            JSONObject e = exercises.optJSONObject(i);
            if (e == null) continue;
            JSONArray sets = e.optJSONArray("sets");
            if (sets != null) {
                for (int j = 0; j < sets.length(); j++) {
                    JSONObject s = sets.optJSONObject(j);
                    if (s == null) continue;
                    try { s.put("d", false); } catch (JSONException ignored) {}
                }
            }
            JSONObject cardio = e.optJSONObject("cardio");
            if (cardio != null) {
                try { cardio.put("done", false); } catch (JSONException ignored) {}
            }
            try { e.put("allComplete", false); } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_SNAPSHOT, snap.toString()).apply();

        JSONObject a = new JSONObject();
        try {
            a.put("type", "finish_day");
            a.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        enqueue(a);
    }

    public JSONArray drainPending() {
        String raw = prefs.getString(KEY_PENDING, null);
        if (raw == null) return new JSONArray();
        prefs.edit().remove(KEY_PENDING).apply();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private JSONObject findCurrentExerciseIn(JSONObject snap) {
        if (snap == null) return null;
        JSONArray ex = snap.optJSONArray("exercises");
        if (ex == null || ex.length() == 0) return null;
        int idx = indexOfId(ex, getWidgetSelectedId());
        return ex.optJSONObject(idx < 0 ? 0 : idx);
    }

    private static int indexOfId(JSONArray ex, String id) {
        if (id == null) return -1;
        for (int i = 0; i < ex.length(); i++) {
            JSONObject e = ex.optJSONObject(i);
            if (e != null && id.equals(e.optString("id"))) return i;
        }
        return -1;
    }

    private void enqueue(JSONObject action) {
        if (action == null) return;
        String raw = prefs.getString(KEY_PENDING, null);
        JSONArray queue;
        try {
            queue = raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (JSONException e) {
            queue = new JSONArray();
        }
        queue.put(action);
        prefs.edit().putString(KEY_PENDING, queue.toString()).apply();
    }

    private static JSONObject actionWith(String type, String exerciseId, int setIdx, int delta, JSONObject extras) {
        JSONObject a = new JSONObject();
        try {
            a.put("type", type);
            if (exerciseId != null) a.put("exerciseId", exerciseId);
            if (setIdx >= 0) a.put("setIdx", setIdx);
            if (delta != 0) a.put("delta", delta);
            a.put("ts", System.currentTimeMillis());
            if (extras != null) {
                for (java.util.Iterator<String> it = extras.keys(); it.hasNext(); ) {
                    String k = it.next();
                    a.put(k, extras.opt(k));
                }
            }
        } catch (JSONException ignored) {}
        return a;
    }

    private static boolean exerciseIdExists(JSONObject snap, String id) {
        if (id == null) return false;
        JSONArray ex = snap.optJSONArray("exercises");
        if (ex == null) return false;
        return indexOfId(ex, id) >= 0;
    }

    private static int parseLooseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseLooseDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.US, "%.1f", v);
    }
}
