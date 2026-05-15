package com.splitstak.app.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;

/**
 * Writes user-visible files directly into the device's public Downloads
 * folder via MediaStore.Downloads (Android 10+, API 29). Replaces the
 * share-sheet UX of @capacitor/share for the CSV-export action so the
 * native app behaves like Chrome's `<a download>` on splitstak.com:
 * file lands in Downloads, full stop.
 *
 * Pre-Android-10 devices reject with a clear message — those users can
 * still export via the Chrome PWA on splitstak.com. We don't carry the
 * WRITE_EXTERNAL_STORAGE permission for that legacy path; scoped storage
 * means the modern path needs no permission at all.
 *
 * Exposed to JS as `Capacitor.Plugins.SplitstakDownloads.saveText({...})`
 * (see plugin name annotation).
 */
@CapacitorPlugin(name = "SplitstakDownloads")
public class DownloadsPlugin extends Plugin {

    @PluginMethod
    public void saveText(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("Direct save to Downloads requires Android 10 or newer");
            return;
        }

        String filename = call.getString("filename");
        String content = call.getString("content");
        String mimeType = call.getString("mimeType", "text/csv");

        if (filename == null || filename.isEmpty()) {
            call.reject("filename is required");
            return;
        }
        if (content == null) {
            call.reject("content is required");
            return;
        }

        try {
            Context ctx = getContext();

            // Insert a pending entry in MediaStore.Downloads. IS_PENDING=1
            // hides the file from the Files app while we're still writing,
            // so other apps can't observe a half-written file.
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = ctx.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            );
            if (uri == null) {
                call.reject("Could not create file in Downloads");
                return;
            }

            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    call.reject("Could not open output stream");
                    return;
                }
                os.write(content.getBytes("UTF-8"));
                os.flush();
            }

            // Flip IS_PENDING back to 0 so the file becomes visible to the
            // user via Files / Drive / etc.
            ContentValues update = new ContentValues();
            update.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, update, null, null);

            JSObject ret = new JSObject();
            ret.put("uri", uri.toString());
            ret.put("filename", filename);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Save failed: " + e.getMessage(), e);
        }
    }
}
