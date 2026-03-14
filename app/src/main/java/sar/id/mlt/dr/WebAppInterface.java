package sar.id.mlt.dr;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebAppInterface {
    private final Context mContext;

    // Single-thread executor so file writes are off the JS thread but still
    // serialised — no two writes race each other.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    WebAppInterface(Context c) {
        mContext = c;
    }

    /**
     * Native haptic feedback — called from JS as window.Android.vibrate(ms).
     * More reliable than navigator.vibrate() which is inconsistent across
     * Android WebView versions and can be silently blocked by the OS.
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    @JavascriptInterface
    public void vibrate(int durationMs) {
        if (durationMs <= 0) return;
        try {
            Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }
        } catch (Exception e) {
            Log.w("WebAppInterface", "Vibrate failed: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void saveFile(String base64Data, String fileName, String mimeType, String trialFolderName) {
        // Input validation — fail fast with a clear message rather than a cryptic exception
        if (base64Data == null || base64Data.isEmpty()) {
            showToast("Save failed: no data provided");
            return;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            showToast("Save failed: file name is missing");
            return;
        }

        // Run the actual file write on a background thread so the JS thread
        // is freed immediately and the UI stays responsive.
        executor.execute(() -> {
            try {
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
                boolean isImage = mimeType != null && mimeType.startsWith("image/");

                String primaryDir = isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS;
                String relativePath = primaryDir;

                if (isImage && trialFolderName != null && !trialFolderName.trim().isEmpty()) {
                    relativePath = primaryDir + File.separator + trialFolderName.trim();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(fileBytes, fileName, mimeType, relativePath, isImage);
                } else {
                    saveLegacy(fileBytes, fileName, relativePath, isImage);
                }
            } catch (Exception e) {
                Log.e("WebAppInterface", "Failed to save file", e);
                showToast("Save failed: " + e.getMessage());
            }
        });
    }

    /**
     * Scoped storage save for Android 10+ (API 29+) using MediaStore.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void saveWithMediaStore(byte[] fileBytes, String fileName, String mimeType,
                                    String relativePath, boolean isImage) throws Exception {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

        Uri collection = isImage
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        Uri fileUri = resolver.insert(collection, values);
        if (fileUri == null) throw new IOException("Failed to create MediaStore entry.");

        try (OutputStream out = resolver.openOutputStream(fileUri)) {
            if (out == null) throw new IOException("OutputStream is null");
            out.write(fileBytes);
        }

        showToast("File saved to " + relativePath);
    }

    /**
     * Direct FileOutputStream save for Android 9 and below (API < 29).
     * Avoids the deprecated MediaStore.MediaColumns.DATA raw-path approach
     * which is unreliable on some Android 9 devices.
     */
    private void saveLegacy(byte[] fileBytes, String fileName,
                            String relativePath, boolean isImage) throws Exception {
        File baseDir = isImage
                ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // Build the subfolder path (e.g. Pictures/TrialName) without passing
        // a constructed string to getExternalStoragePublicDirectory.
        File targetDir = (isImage && relativePath.contains(File.separator))
                ? new File(baseDir, relativePath.substring(relativePath.indexOf(File.separator) + 1))
                : baseDir;

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Could not create directory: " + targetDir.getAbsolutePath());
        }

        File outFile = new File(targetDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(fileBytes);
        }

        showToast("File saved to " + outFile.getAbsolutePath());
    }

    /**
     * UI thread toast helper — safe to call from any thread.
     */
    private void showToast(final String message) {
        new android.os.Handler(mContext.getMainLooper()).post(
                () -> Toast.makeText(mContext, message, Toast.LENGTH_LONG).show()
        );
    }
}