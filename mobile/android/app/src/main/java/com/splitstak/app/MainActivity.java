package com.splitstak.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.splitstak.app.widget.WidgetNotifications;
import com.splitstak.app.widget.WidgetPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetPlugin.class);
        super.onCreate(savedInstanceState);
        // Create the rest-timer channel up front so any subsequent notify()
        // call (from the widget BroadcastReceiver, even with the app killed)
        // has a registered channel to post into.
        WidgetNotifications.ensureChannel(this);
    }
}
