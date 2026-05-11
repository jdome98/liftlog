package com.splitstak.app.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import com.splitstak.app.MainActivity;
import com.splitstak.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Renders the currently-selected exercise from the widget snapshot. Buttons
 * fire broadcasts back to onReceive(), which mutates WidgetState and re-
 * renders. Only btn_open_app launches the full app — every other interactive
 * surface uses PendingIntent.getBroadcast so it works without unlocking.
 */
public class SplitstakAppWidgetProvider extends AppWidgetProvider {

    public static final String ACTION = "com.splitstak.app.widget.ACTION";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_SET_IDX = "setIdx";
    public static final String EXTRA_DELTA = "delta";

    public static final String TYPE_INC_WEIGHT = "inc_weight";
    public static final String TYPE_INC_REPS = "inc_reps";
    public static final String TYPE_INC_TIME = "inc_time";
    public static final String TYPE_INC_DIST = "inc_dist";
    public static final String TYPE_INC_HOLD = "inc_hold";
    public static final String TYPE_TOGGLE_DONE = "toggle_done";
    public static final String TYPE_NAV = "nav";
    public static final String TYPE_DISMISS_REST = "dismiss_rest";
    public static final String TYPE_REST_EXPIRED = "rest_expired";
    public static final String TYPE_FINISH_DAY = "finish_day";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION.equals(intent.getAction())) return;

        WidgetState state = new WidgetState(context);
        String type = intent.getStringExtra(EXTRA_TYPE);
        int setIdx = intent.getIntExtra(EXTRA_SET_IDX, -1);
        int delta = intent.getIntExtra(EXTRA_DELTA, 0);

        if (TYPE_INC_WEIGHT.equals(type)) {
            JSONObject snap = state.loadSnapshot();
            int step = snap == null ? 5 : snap.optInt("weightStep", 5);
            state.applyIncStrength("w", setIdx, delta, step);
        } else if (TYPE_INC_REPS.equals(type)) {
            JSONObject snap = state.loadSnapshot();
            int step = snap == null ? 1 : snap.optInt("repStep", 1);
            state.applyIncStrength("r", setIdx, delta, step);
        } else if (TYPE_INC_TIME.equals(type)) {
            JSONObject snap = state.loadSnapshot();
            double step = snap == null ? 5.0 : snap.optDouble("timeStep", 5.0);
            state.applyIncCardio("time", delta, step);
        } else if (TYPE_INC_DIST.equals(type)) {
            JSONObject snap = state.loadSnapshot();
            double step = snap == null ? 0.5 : snap.optDouble("distanceStep", 0.5);
            state.applyIncCardio("distance", delta, step);
        } else if (TYPE_INC_HOLD.equals(type)) {
            JSONObject snap = state.loadSnapshot();
            int step = snap == null ? 5 : snap.optInt("holdStep", 5);
            state.applyIncHold(setIdx, delta, step);
        } else if (TYPE_TOGGLE_DONE.equals(type)) {
            state.applyToggleDone(setIdx);
            // applyToggleDone may have stamped restEndsAt; if so, schedule an
            // alarm to hide the timer bar at expiry instead of letting the
            // Chronometer count negative past 0.
            JSONObject snap = state.loadSnapshot();
            if (snap != null) {
                long endsAt = snap.optLong("restEndsAt", 0L);
                if (endsAt > System.currentTimeMillis()) {
                    scheduleRestExpiryAlarm(context, endsAt);
                }
            }
        } else if (TYPE_NAV.equals(type)) {
            state.selectByDelta(delta);
        } else if (TYPE_DISMISS_REST.equals(type)) {
            state.applyDismissRest();
            cancelRestExpiryAlarm(context);
        } else if (TYPE_REST_EXPIRED.equals(type)) {
            state.applyExpireRest();
        } else if (TYPE_FINISH_DAY.equals(type)) {
            // Reset ticks across the day in the widget snapshot directly so
            // the user sees a clean slate immediately — no app launch. The
            // queued finish_day action lets JS do the real history rewrite
            // + PR detection the next time the PWA naturally runs.
            state.applyFinishDay();
        } else if (WidgetNotifications.TYPE_FIRE_WARNING.equals(type)) {
            WidgetNotifications.postWarning(context);
            return;  // notification only — no widget refresh needed
        } else if (WidgetNotifications.TYPE_FIRE_FINISH.equals(type)) {
            WidgetNotifications.postFinish(context);
            // The finish notification fires at exactly restEndsAt — use
            // the same trigger to hide the widget's rest bar so the
            // Chronometer doesn't keep ticking past 0:00 into negatives.
            state.applyExpireRest();
            refreshAll(context);
            return;
        }

        refreshAll(context);
    }

    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, SplitstakAppWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids.length == 0) return;
        for (int id : ids) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context ctx) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_strength);
        WidgetState state = new WidgetState(ctx);
        JSONObject snap = state.loadSnapshot();
        JSONObject ex = state.getCurrentExercise();

        rv.setOnClickPendingIntent(android.R.id.background, noOpPending(ctx));

        Intent openApp = new Intent(ctx, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        rv.setOnClickPendingIntent(R.id.btn_open_app,
                PendingIntent.getActivity(ctx, 999, openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        rv.setOnClickPendingIntent(R.id.btn_prev, actionPending(ctx, TYPE_NAV, -1, -1, 1));
        rv.setOnClickPendingIntent(R.id.btn_next, actionPending(ctx, TYPE_NAV, -1, 1, 2));
        rv.setOnClickPendingIntent(R.id.btn_dismiss_rest,
                actionPending(ctx, TYPE_DISMISS_REST, -1, 0, 3));
        rv.setOnClickPendingIntent(R.id.save_day_btn,
                actionPending(ctx, TYPE_FINISH_DAY, -1, 0, 4));

        boolean isRest = snap != null && snap.optBoolean("isRestDay", false);

        if (snap == null) {
            renderPlaceholder(rv, "Open SPLITSTAK to start", false);
            renderRestBar(rv, ctx, snap);
            return rv;
        }
        if (isRest) {
            renderRestDay(rv);
            renderRestBar(rv, ctx, snap);
            return rv;
        }
        if (ex == null) {
            renderPlaceholder(rv, "No workout selected", false);
            renderRestBar(rv, ctx, snap);
            return rv;
        }

        rv.setViewVisibility(R.id.btn_open_app, View.VISIBLE);
        rv.setViewVisibility(R.id.placeholder_text, View.GONE);
        rv.setViewVisibility(R.id.columns_header, View.VISIBLE);
        rv.setTextViewText(R.id.exercise_name, ex.optString("name", ""));

        StringBuilder meta = new StringBuilder();
        String target = ex.optString("target", "");
        String lastTop = ex.optString("lastTop", "");
        if (!target.isEmpty()) meta.append(target);
        if (!lastTop.isEmpty() && !"null".equals(lastTop)) {
            if (meta.length() > 0) meta.append("   ");
            meta.append("Last: ").append(lastTop);
        }
        // Empty meta hidden so the name is true-vertically-centered between
        // the chevrons (an empty TextView still occupies a baseline gap).
        if (meta.length() == 0) {
            rv.setViewVisibility(R.id.exercise_meta, View.GONE);
        } else {
            rv.setViewVisibility(R.id.exercise_meta, View.VISIBLE);
            rv.setTextViewText(R.id.exercise_meta, meta.toString());
        }

        // Progress dots — filled per completed exercise, hollow per remaining.
        String dots = buildProgressDots(snap);
        if (dots.length() == 0) {
            rv.setViewVisibility(R.id.progress_dots, View.GONE);
        } else {
            rv.setViewVisibility(R.id.progress_dots, View.VISIBLE);
            rv.setTextViewText(R.id.progress_dots, dots);
        }

        // Right-side header pill: SAVE DAY (when every exercise on the day is
        // complete) takes precedence over the per-exercise PR / ✓ badge.
        boolean allDone = ex.optBoolean("allComplete", false);
        boolean dayAllComplete = isDayAllComplete(snap);
        if (dayAllComplete) {
            // Hide the launch icon while SAVE DAY is shown — the two would
            // crowd each other otherwise and the user reported tapping the
            // launch icon by accident when reaching for SAVE DAY.
            rv.setViewVisibility(R.id.save_day_btn, View.VISIBLE);
            rv.setViewVisibility(R.id.badge, View.GONE);
            rv.setViewVisibility(R.id.btn_open_app, View.GONE);
        } else {
            rv.setViewVisibility(R.id.save_day_btn, View.GONE);
            rv.setViewVisibility(R.id.btn_open_app, View.VISIBLE);
            if (allDone) {
                rv.setViewVisibility(R.id.badge, View.VISIBLE);
                if (isPR(ex)) {
                    // Inverted style: orange pill + dark "PR" text. Same
                    // contrast logic the app uses on .last-selected: the
                    // emphasis comes from the surface flip, not just the
                    // glyph swap.
                    rv.setTextViewText(R.id.badge, "PR");
                    rv.setInt(R.id.badge, "setBackgroundResource", R.drawable.widget_badge_bg);
                    rv.setInt(R.id.badge, "setTextColor", 0xFF0A0A0A);
                } else {
                    rv.setTextViewText(R.id.badge, "✓");
                    rv.setInt(R.id.badge, "setBackgroundResource", R.drawable.widget_badge_inverse_bg);
                    rv.setInt(R.id.badge, "setTextColor", 0xFFFF5722);
                }
            } else {
                rv.setViewVisibility(R.id.badge, View.GONE);
            }
        }

        String kind = ex.optString("kind", "strength");
        String mode = ex.optString("mode", "reps");
        if ("cardio".equals(kind) || "cardio".equals(mode)) {
            renderCardio(rv, ctx, ex);
        } else if ("time".equals(mode)) {
            renderTimedHold(rv, ctx, ex);
        } else if ("bodyweight".equals(mode)) {
            renderBodyweight(rv, ctx, ex);
        } else {
            renderStrength(rv, ctx, ex);
        }

        renderRestBar(rv, ctx, snap);
        return rv;
    }

    private static void renderStrength(RemoteViews rv, Context ctx, JSONObject ex) {
        rv.setTextViewText(R.id.column_label_left, "Weight");
        rv.setTextViewText(R.id.column_label_right, "Reps");
        rv.setViewVisibility(R.id.column_label_left, View.VISIBLE);
        rv.setViewVisibility(R.id.weight_col_0, View.VISIBLE);
        rv.setViewVisibility(R.id.weight_col_1, View.VISIBLE);
        rv.setViewVisibility(R.id.weight_col_2, View.VISIBLE);
        JSONArray sets = ex.optJSONArray("sets");
        int n = sets == null ? 0 : sets.length();
        renderStrengthSetRow(rv, ctx, 0, n, sets);
        renderStrengthSetRow(rv, ctx, 1, n, sets);
        renderStrengthSetRow(rv, ctx, 2, n, sets);
    }

    /**
     * Timed-hold mode (Plank, Dead Hang, Farmer's Walk) — set is {t, d},
     * single seconds field. Hide the weight column and label; the reps
     * column slot displays the seconds value with ± controls wired to
     * TYPE_INC_HOLD instead of TYPE_INC_REPS.
     */
    private static void renderTimedHold(RemoteViews rv, Context ctx, JSONObject ex) {
        rv.setViewVisibility(R.id.column_label_left, View.GONE);
        rv.setTextViewText(R.id.column_label_right, "Time (sec)");
        rv.setViewVisibility(R.id.weight_col_0, View.GONE);
        rv.setViewVisibility(R.id.weight_col_1, View.GONE);
        rv.setViewVisibility(R.id.weight_col_2, View.GONE);
        JSONArray sets = ex.optJSONArray("sets");
        int n = sets == null ? 0 : sets.length();
        renderHoldSetRow(rv, ctx, 0, n, sets);
        renderHoldSetRow(rv, ctx, 1, n, sets);
        renderHoldSetRow(rv, ctx, 2, n, sets);
    }

    private static void renderHoldSetRow(RemoteViews rv, Context ctx, int idx, int total, JSONArray sets) {
        int rowId = idx == 0 ? R.id.set_row_0 : idx == 1 ? R.id.set_row_1 : R.id.set_row_2;
        int labelId = idx == 0 ? R.id.set_label_0 : idx == 1 ? R.id.set_label_1 : R.id.set_label_2;
        if (idx >= total || sets == null) {
            rv.setViewVisibility(rowId, View.GONE);
            return;
        }
        rv.setViewVisibility(rowId, View.VISIBLE);
        rv.setViewVisibility(labelId, View.VISIBLE);
        rv.setTextViewText(labelId, String.valueOf(idx + 1));

        JSONObject s = sets.optJSONObject(idx);
        if (s == null) return;
        String t = s.optString("t", "");
        boolean done = s.optBoolean("d", false);

        int repsId = idx == 0 ? R.id.reps_0 : idx == 1 ? R.id.reps_1 : R.id.reps_2;
        int doneId = idx == 0 ? R.id.btn_done_0 : idx == 1 ? R.id.btn_done_1 : R.id.btn_done_2;
        int rMinusId = idx == 0 ? R.id.btn_r_minus_0 : idx == 1 ? R.id.btn_r_minus_1 : R.id.btn_r_minus_2;
        int rPlusId = idx == 0 ? R.id.btn_r_plus_0 : idx == 1 ? R.id.btn_r_plus_1 : R.id.btn_r_plus_2;

        rv.setTextViewText(repsId, t.isEmpty() ? "—" : t);
        rv.setInt(doneId, "setBackgroundResource",
                done ? R.drawable.widget_done_on_bg : R.drawable.widget_done_off_bg);
        rv.setInt(doneId, "setImageAlpha", done ? 255 : 0);

        int base = 100 + idx * 10;
        rv.setOnClickPendingIntent(rMinusId, actionPending(ctx, TYPE_INC_HOLD, idx, -1, base + 2));
        rv.setOnClickPendingIntent(rPlusId, actionPending(ctx, TYPE_INC_HOLD, idx, 1, base + 3));
        rv.setOnClickPendingIntent(doneId, actionPending(ctx, TYPE_TOGGLE_DONE, idx, 0, base + 4));
    }

    /**
     * Bodyweight mode (push-ups, pull-ups, hanging knee raise) — set is
     * {r, d}, no weight. Hide the weight column and its sub-header label;
     * reps controls expand to fill the freed space via their existing
     * weight=1.
     */
    private static void renderBodyweight(RemoteViews rv, Context ctx, JSONObject ex) {
        rv.setViewVisibility(R.id.column_label_left, View.GONE);
        rv.setTextViewText(R.id.column_label_right, "Reps");
        rv.setViewVisibility(R.id.weight_col_0, View.GONE);
        rv.setViewVisibility(R.id.weight_col_1, View.GONE);
        rv.setViewVisibility(R.id.weight_col_2, View.GONE);
        JSONArray sets = ex.optJSONArray("sets");
        int n = sets == null ? 0 : sets.length();
        renderStrengthSetRow(rv, ctx, 0, n, sets);
        renderStrengthSetRow(rv, ctx, 1, n, sets);
        renderStrengthSetRow(rv, ctx, 2, n, sets);
    }

    private static void renderStrengthSetRow(RemoteViews rv, Context ctx, int idx, int total, JSONArray sets) {
        int rowId = idx == 0 ? R.id.set_row_0 : idx == 1 ? R.id.set_row_1 : R.id.set_row_2;
        int labelId = idx == 0 ? R.id.set_label_0 : idx == 1 ? R.id.set_label_1 : R.id.set_label_2;
        if (idx >= total || sets == null) {
            rv.setViewVisibility(rowId, View.GONE);
            return;
        }
        rv.setViewVisibility(rowId, View.VISIBLE);
        rv.setViewVisibility(labelId, View.VISIBLE);
        rv.setTextViewText(labelId, String.valueOf(idx + 1));

        JSONObject s = sets.optJSONObject(idx);
        if (s == null) return;
        String w = s.optString("w", "");
        String r = s.optString("r", "");
        boolean done = s.optBoolean("d", false);

        int weightId = idx == 0 ? R.id.weight_0 : idx == 1 ? R.id.weight_1 : R.id.weight_2;
        int repsId = idx == 0 ? R.id.reps_0 : idx == 1 ? R.id.reps_1 : R.id.reps_2;
        int doneId = idx == 0 ? R.id.btn_done_0 : idx == 1 ? R.id.btn_done_1 : R.id.btn_done_2;
        int wMinusId = idx == 0 ? R.id.btn_w_minus_0 : idx == 1 ? R.id.btn_w_minus_1 : R.id.btn_w_minus_2;
        int wPlusId = idx == 0 ? R.id.btn_w_plus_0 : idx == 1 ? R.id.btn_w_plus_1 : R.id.btn_w_plus_2;
        int rMinusId = idx == 0 ? R.id.btn_r_minus_0 : idx == 1 ? R.id.btn_r_minus_1 : R.id.btn_r_minus_2;
        int rPlusId = idx == 0 ? R.id.btn_r_plus_0 : idx == 1 ? R.id.btn_r_plus_1 : R.id.btn_r_plus_2;

        rv.setTextViewText(weightId, w.isEmpty() ? "—" : w);
        rv.setTextViewText(repsId, r.isEmpty() ? "—" : r);
        rv.setInt(doneId, "setBackgroundResource",
                done ? R.drawable.widget_done_on_bg : R.drawable.widget_done_off_bg);
        rv.setInt(doneId, "setImageAlpha", done ? 255 : 0);

        int base = 100 + idx * 10;
        rv.setOnClickPendingIntent(wMinusId, actionPending(ctx, TYPE_INC_WEIGHT, idx, -1, base));
        rv.setOnClickPendingIntent(wPlusId, actionPending(ctx, TYPE_INC_WEIGHT, idx, 1, base + 1));
        rv.setOnClickPendingIntent(rMinusId, actionPending(ctx, TYPE_INC_REPS, idx, -1, base + 2));
        rv.setOnClickPendingIntent(rPlusId, actionPending(ctx, TYPE_INC_REPS, idx, 1, base + 3));
        rv.setOnClickPendingIntent(doneId, actionPending(ctx, TYPE_TOGGLE_DONE, idx, 0, base + 4));
    }

    private static void renderCardio(RemoteViews rv, Context ctx, JSONObject ex) {
        rv.setTextViewText(R.id.column_label_left, "Time (min)");
        rv.setTextViewText(R.id.column_label_right, "Dist (mi)");
        rv.setViewVisibility(R.id.set_row_0, View.VISIBLE);
        rv.setViewVisibility(R.id.set_row_1, View.GONE);
        rv.setViewVisibility(R.id.set_row_2, View.GONE);
        rv.setViewVisibility(R.id.set_label_0, View.INVISIBLE);

        JSONObject cardio = ex.optJSONObject("cardio");
        String time = cardio == null ? "" : cardio.optString("time", "");
        String dist = cardio == null ? "" : cardio.optString("distance", "");
        boolean done = cardio != null && cardio.optBoolean("done", false);

        rv.setTextViewText(R.id.weight_0, time.isEmpty() ? "—" : time);
        rv.setTextViewText(R.id.reps_0, dist.isEmpty() ? "—" : dist);
        rv.setInt(R.id.btn_done_0, "setBackgroundResource",
                done ? R.drawable.widget_done_on_bg : R.drawable.widget_done_off_bg);
        rv.setInt(R.id.btn_done_0, "setImageAlpha", done ? 255 : 0);

        rv.setOnClickPendingIntent(R.id.btn_w_minus_0, actionPending(ctx, TYPE_INC_TIME, -1, -1, 200));
        rv.setOnClickPendingIntent(R.id.btn_w_plus_0, actionPending(ctx, TYPE_INC_TIME, -1, 1, 201));
        rv.setOnClickPendingIntent(R.id.btn_r_minus_0, actionPending(ctx, TYPE_INC_DIST, -1, -1, 202));
        rv.setOnClickPendingIntent(R.id.btn_r_plus_0, actionPending(ctx, TYPE_INC_DIST, -1, 1, 203));
        rv.setOnClickPendingIntent(R.id.btn_done_0, actionPending(ctx, TYPE_TOGGLE_DONE, -1, 0, 204));
    }

    private static void renderRestDay(RemoteViews rv) {
        rv.setTextViewText(R.id.exercise_name, "SPLITSTAK");
        rv.setViewVisibility(R.id.exercise_meta, View.GONE);
        rv.setViewVisibility(R.id.progress_dots, View.GONE);
        rv.setViewVisibility(R.id.save_day_btn, View.GONE);
        // Keep open-app available even on rest days — user can still want
        // into the app to flip to a non-rest day or check history.
        rv.setViewVisibility(R.id.btn_open_app, View.VISIBLE);
        rv.setViewVisibility(R.id.badge, View.GONE);
        rv.setViewVisibility(R.id.columns_header, View.GONE);
        rv.setViewVisibility(R.id.set_row_0, View.GONE);
        rv.setViewVisibility(R.id.set_row_1, View.GONE);
        rv.setViewVisibility(R.id.set_row_2, View.GONE);
        rv.setViewVisibility(R.id.placeholder_text, View.VISIBLE);
        rv.setTextViewText(R.id.placeholder_text, "REST");
        // 0xFFFF5722 = ss_accent
        rv.setInt(R.id.placeholder_text, "setTextColor", 0xFFFF5722);
    }

    private static void renderPlaceholder(RemoteViews rv, String message, boolean accent) {
        rv.setTextViewText(R.id.exercise_name, "SPLITSTAK");
        rv.setViewVisibility(R.id.exercise_meta, View.GONE);
        rv.setViewVisibility(R.id.progress_dots, View.GONE);
        rv.setViewVisibility(R.id.save_day_btn, View.GONE);
        rv.setViewVisibility(R.id.btn_open_app, View.VISIBLE);
        rv.setViewVisibility(R.id.badge, View.GONE);
        rv.setViewVisibility(R.id.columns_header, View.GONE);
        rv.setViewVisibility(R.id.set_row_0, View.GONE);
        rv.setViewVisibility(R.id.set_row_1, View.GONE);
        rv.setViewVisibility(R.id.set_row_2, View.GONE);
        rv.setViewVisibility(R.id.placeholder_text, View.VISIBLE);
        rv.setTextViewText(R.id.placeholder_text, message);
        rv.setInt(R.id.placeholder_text, "setTextColor",
                accent ? 0xFFFF5722 : 0xFF888888);
    }

    /**
     * Builds the "● ● ○ ○ ○" progress string from snap.exercises. Filled
     * dot per allComplete exercise, hollow per remaining. Empty when there
     * are no exercises (placeholder / rest day callers don't show this).
     */
    private static String buildProgressDots(JSONObject snap) {
        if (snap == null) return "";
        JSONArray exercises = snap.optJSONArray("exercises");
        if (exercises == null || exercises.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exercises.length(); i++) {
            JSONObject e = exercises.optJSONObject(i);
            if (e == null) continue;
            if (i > 0) sb.append(' ');
            sb.append(e.optBoolean("allComplete", false) ? '●' : '○');
        }
        return sb.toString();
    }

    private static boolean isDayAllComplete(JSONObject snap) {
        if (snap == null) return false;
        if (snap.optBoolean("isRestDay", false)) return false;
        JSONArray exercises = snap.optJSONArray("exercises");
        if (exercises == null || exercises.length() == 0) return false;
        for (int i = 0; i < exercises.length(); i++) {
            JSONObject e = exercises.optJSONObject(i);
            if (e == null || !e.optBoolean("allComplete", false)) return false;
        }
        return true;
    }

    /**
     * Replicates the PWA's PR rules per mode:
     *   strength (mode='reps') — higher topWeight, or same + more reps
     *   bodyweight (mode='bodyweight') — more reps than prev best
     *   cardio / time — no widget PR badge (PWA handles these in-app)
     * prevBestWeight/prevBestReps come from the last history entry on the
     * snapshot. No prev → not a PR (matches PWA: first session can't beat
     * something that doesn't exist yet).
     */
    private static boolean isPR(JSONObject ex) {
        if (ex == null) return false;
        String mode = ex.optString("mode", "reps");
        if ("cardio".equals(mode) || "time".equals(mode)) return false;

        JSONArray sets = ex.optJSONArray("sets");
        if (sets == null || sets.length() == 0) return false;

        if ("bodyweight".equals(mode)) {
            int topReps = 0;
            for (int i = 0; i < sets.length(); i++) {
                JSONObject s = sets.optJSONObject(i);
                if (s == null) continue;
                int r = parseInt(s.optString("r", ""));
                if (r > topReps) topReps = r;
            }
            if (topReps <= 0) return false;
            if (ex.isNull("prevBestReps")) return false;
            int prevR = ex.optInt("prevBestReps", -1);
            if (prevR < 0) return false;
            return topReps > prevR;
        }

        double topWeight = -1;
        int topRepsAtTopWeight = 0;
        for (int i = 0; i < sets.length(); i++) {
            JSONObject s = sets.optJSONObject(i);
            if (s == null) continue;
            double w = parseDouble(s.optString("w", ""));
            int r = parseInt(s.optString("r", ""));
            if (w > topWeight) { topWeight = w; topRepsAtTopWeight = r; }
            else if (w == topWeight && r > topRepsAtTopWeight) { topRepsAtTopWeight = r; }
        }
        if (topWeight <= 0) return false;

        if (ex.isNull("prevBestWeight")) return false;
        double prevW = ex.optDouble("prevBestWeight", -1);
        int prevR = ex.optInt("prevBestReps", 0);
        if (prevW < 0) return false;

        if (topWeight > prevW) return true;
        if (topWeight == prevW && topRepsAtTopWeight > prevR) return true;
        return false;
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static void renderRestBar(RemoteViews rv, Context ctx, JSONObject snap) {
        long endsAt = (snap == null) ? 0L : snap.optLong("restEndsAt", 0L);
        long remaining = endsAt - System.currentTimeMillis();
        if (endsAt <= 0L || remaining <= 0L) {
            rv.setViewVisibility(R.id.rest_timer_bar, View.GONE);
            return;
        }
        rv.setViewVisibility(R.id.rest_timer_bar, View.VISIBLE);
        long base = SystemClock.elapsedRealtime() + remaining;
        rv.setChronometer(R.id.rest_chrono, base, null, true);
        rv.setChronometerCountDown(R.id.rest_chrono, true);
    }

    private static void scheduleRestExpiryAlarm(Context ctx, long endsAt) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // setAndAllowWhileIdle fires even while the device is in Doze — set()
        // alone gets deferred to the next maintenance window which can be
        // many minutes away.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt + 250, expiryAlarmPI(ctx));
    }

    private static void cancelRestExpiryAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(expiryAlarmPI(ctx));
    }

    private static PendingIntent expiryAlarmPI(Context ctx) {
        Intent i = new Intent(ctx, SplitstakAppWidgetProvider.class);
        i.setAction(ACTION);
        i.putExtra(EXTRA_TYPE, TYPE_REST_EXPIRED);
        return PendingIntent.getBroadcast(ctx, 997, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actionPending(Context ctx, String type, int setIdx, int delta, int requestCode) {
        Intent i = new Intent(ctx, SplitstakAppWidgetProvider.class);
        i.setAction(ACTION);
        i.putExtra(EXTRA_TYPE, type);
        i.putExtra(EXTRA_SET_IDX, setIdx);
        i.putExtra(EXTRA_DELTA, delta);
        return PendingIntent.getBroadcast(ctx, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent noOpPending(Context ctx) {
        Intent i = new Intent(ctx, SplitstakAppWidgetProvider.class);
        return PendingIntent.getBroadcast(ctx, 998, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
