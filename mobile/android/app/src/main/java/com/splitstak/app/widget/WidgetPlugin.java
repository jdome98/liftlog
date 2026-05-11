package com.splitstak.app.widget;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "SplitstakWidget")
public class WidgetPlugin extends Plugin {

    @PluginMethod
    public void publishSnapshot(PluginCall call) {
        JSObject snapshot = call.getObject("snapshot");
        if (snapshot == null) {
            call.reject("Missing snapshot");
            return;
        }
        WidgetState state = new WidgetState(getContext());
        state.saveSnapshot(snapshot);
        SplitstakAppWidgetProvider.refreshAll(getContext());
        call.resolve();
    }

    @PluginMethod
    public void drainPendingActions(PluginCall call) {
        WidgetState state = new WidgetState(getContext());
        JSONArray actions = state.drainPending();
        JSObject ret = new JSObject();
        ret.put("actions", actions);
        call.resolve(ret);
    }

    /**
     * Called by JS in handleSetDone(): schedules the same warning + finish
     * notifications the widget's done-tap fires, but for in-app set ticks.
     * Replaces the PWA's Web Push path inside the Capacitor build.
     */
    @PluginMethod
    public void scheduleLocalRestNotifications(PluginCall call) {
        Long endsAt = call.getLong("restEndsAt");
        if (endsAt != null && endsAt > 0) {
            WidgetNotifications.scheduleRestNotifications(getContext(), endsAt);
        }
        call.resolve();
    }

    @PluginMethod
    public void cancelLocalRestNotifications(PluginCall call) {
        WidgetNotifications.cancelRestNotifications(getContext());
        call.resolve();
    }
}
