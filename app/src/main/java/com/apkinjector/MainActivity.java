package com.apkinjector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.*;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.zip.*;
import javax.security.auth.x500.X500Principal;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    private static final int REQ_APK        = 1001;
    private static final int REQ_ZIP        = 1002;
    private static final int REQ_PERM_WRITE = 1003;
    private static final int REQ_MANAGE_EXT = 1004;

    /** Android KeyStore alias for the V1 signing key (auto-generated on first use) */
    private static final String KEY_ALIAS = "apk_injector_v1_key";

    // volatile: written/read from different threads
    private volatile String pendingJsCallback = null;
    private volatile File   resultApkFile     = null;

    // ─────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        initWebView();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void initWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setTextZoom(100);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
        webView.setScrollbarFadingEnabled(true);
        // Use static inner class with WeakReference to avoid memory leak
        webView.addJavascriptInterface(new JsBridge(this), "Android");
    }

    // ─────────────────────────────────────
    // JavaScript Bridge (static — no leak)
    // ─────────────────────────────────────

    private static class JsBridge {
        private final WeakReference<MainActivity> ref;

        JsBridge(MainActivity activity) {
            ref = new WeakReference<>(activity);
        }

        private MainActivity get() { return ref.get(); }

        /** FIX: startActivityForResult MUST run on the UI thread */
        @JavascriptInterface
        public void pickApk(String callback) {
            MainActivity a = get();
            if (a == null) return;
            a.pendingJsCallback = callback;
            a.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/vnd.android.package-archive",
                                 "application/octet-stream"});
                try {
                    a.startActivityForResult(
                        Intent.createChooser(intent, "انتخاب فایل APK"), REQ_APK);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در باز کردن انتخابگر فایل: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void pickZip(String callback) {
            MainActivity a = get();
            if (a == null) return;
            a.pendingJsCallback = callback;
            a.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/x-zip-compressed",
                                 "multipart/x-zip", "application/octet-stream"});
                try {
                    a.startActivityForResult(
                        Intent.createChooser(intent, "انتخاب پروژه ZIP"), REQ_ZIP);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در باز کردن انتخابگر فایل: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void startInjection(String apkPath, String zipPath) {
            MainActivity a = get();
            if (a == null) return;
            // FIX: named thread for easier debugging; daemon so it doesn't block app exit
            Thread t = new Thread(() -> {
                try {
                    a.runInjection(apkPath, zipPath);
                } catch (InterruptedException ie) {
                    // FIX: restore interrupt flag; don't show error to user on cancellation
                    Thread.currentThread().interrupt();
                } catch (Throwable err) {
                    String msg = err.getMessage() != null
                        ? err.getMessage() : err.getClass().getSimpleName();
                    a.jsEvent("onInjectError", msg);
                }
            }, "APK-Injector-Worker");
            t.setDaemon(true);
            t.start();
        }

        @JavascriptInterface
        public void installApk() {
            MainActivity a = get();
            if (a == null) return;
            if (a.resultApkFile == null || !a.resultApkFile.exists()) {
                a.runOnUiThread(() ->
                    Toast.makeText(a, "فایل APK یافت نشد", Toast.LENGTH_SHORT).show());
                return;
            }
            a.runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(
                        a, a.getPackageName() + ".fileprovider", a.resultApkFile);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                             | Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در نصب: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void saveApk() {
            MainActivity a = get();
            if (a == null) return;
            if (a.resultApkFile == null || !a.resultApkFile.exists()) {
                a.jsEvent("onSaveError", "فایل نتیجه وجود ندارد");
                return;
            }
            a.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        a.saveToDownloads();
                    } else {
                        Intent i = new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + a.getPackageName()));
                        a.startActivityForResult(i, REQ_MANAGE_EXT);
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(a,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(a,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_PERM_WRITE);
                    } else {
                        a.saveToDownloads();
                    }
                }
            });
        }

        @JavascriptInterface
        public void shareApk() {
            MainActivity a = get();
            if (a == null || a.resultApkFile == null || !a.resultApkFile.exists()) return;
            a.runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(
                        a, a.getPackageName() + ".fileprovider", a.resultApkFile);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/vnd.android.package-archive");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(share, "اشتراک‌گذاری APK"));
                } catch (Exception e) {
                    a.jsEvent("onError", "خطا در اشتراک‌گذاری: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            return Build.VERSION.SDK_INT + "|" + Build.MODEL + "|" + Build.VERSION.RELEASE;
        }

        @JavascriptInterface
        public void reset() {
            MainActivity a = get();
            if (a == null) return;
            new Thread(() -> {
                File cache = a.getCacheDir();
                File[] files = cache.listFiles((dir, name) ->
                    name.startsWith("apk_input_")
                    || name.startsWith("zip_input_")
                    || name.startsWith("injected_"));
                if (files != null) for (File f : files) f.delete();
                a.resultApkFile = null;
            }).start();
        }
    }

    // ─────────────────────────────────────
    // Core Injection Engine
    // ─────────────────────────────────────

    private void runInjection(String apkPath, String zipPath) throws Exception {
        File apkFile = new File(apkPath);
        File zipFile = new File(zipPath);

        if (!apkFile.exists()) throw new Exception("APK یافت نشد: " + apkPath);
        if (!zipFile.exists()) throw new Exception("ZIP یافت نشد: " + zipPath);

        // ── Phase 1: Analyze & Read APK ──
        jsProgress("analyze", 0, "در حال آنالیز APK...");
        Map<String, EntryData> apkMap = new LinkedHashMap<>();
        long apkRawSize = apkFile.length();

        // CRITICAL FIX: Use ZipFile (not ZipInputStream) to read the APK.
        // ZipFile reads from the central directory and correctly handles ALL APK entries,
        // including those with data descriptors (flag bit 3) that ZipInputStream often
        // misreads as zero bytes — which causes empty AndroidManifest.xml and size decrease.
        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && !name.isEmpty()) {
                    byte[] data;
                    try (InputStream entryStream = zf.getInputStream(entry)) {
                        data = readStream(entryStream);
                    }
                    apkMap.put(name, new EntryData(data, entry.getMethod()));
                    count++;
                    if (count % 30 == 0) {
                        jsProgress("analyze",
                            Math.min(28, 2 + count / 5),
                            "خواندن APK: " + count + " فایل");
                    }
                }
            }
        }
        jsProgress("analyze", 30,
            "APK آنالیز شد ← " + apkMap.size() + " فایل در " + formatBytes(apkRawSize));
        Thread.sleep(150);

        // FIX: Strip original APK's signing entries — they are now INVALID because we
        // are modifying the APK. Keeping invalid signing files causes "مشکلی در تجزیه".
        // We will re-sign the output APK with a fresh V1 signature below.
        apkMap.entrySet().removeIf(entry -> {
            String lk = entry.getKey().toLowerCase(Locale.ROOT);
            return lk.startsWith("meta-inf/") && (
                lk.endsWith(".sf")
                || lk.endsWith(".rsa")
                || lk.endsWith(".dsa")
                || lk.endsWith(".ec")
                || lk.equals("meta-inf/manifest.mf")
            );
        });

        // ── Phase 2: Read Injection ZIP ──
        jsProgress("inject", 32, "در حال خواندن پروژه تزریق...");

        // FIX: detectTopFolder now validates ALL entries
        String topFolder = detectTopFolderRobust(zipFile);

        Map<String, EntryData> zipMap = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile), 65536))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (!entry.isDirectory() && !rawName.isEmpty()) {
                    String name = stripTopFolder(rawName, topFolder);
                    if (!name.isEmpty()) {
                        byte[] data = readStream(zis);
                        // CRITICAL FIX: choose compression method based on file type.
                        // DEX/so/arsc MUST be STORED — Android's package manager requires it.
                        int injectMethod = mustBeStored(name)
                            ? ZipEntry.STORED : ZipEntry.DEFLATED;
                        zipMap.put(name, new EntryData(data, injectMethod));
                        count++;
                        if (count % 10 == 0) {
                            jsProgress("inject",
                                Math.min(48, 32 + count),
                                "خواندن پروژه: " + count + " فایل");
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        jsProgress("inject", 50,
            "پروژه تزریق خوانده شد ← " + zipMap.size() + " فایل");
        Thread.sleep(150);

        // ── Phase 3: Smart Merge ──
        jsProgress("merge", 52, "ترکیب هوشمند فایل‌ها...");
        int overridden = 0, added = 0, skipped = 0;

        for (Map.Entry<String, EntryData> ze : zipMap.entrySet()) {
            String rawKey = ze.getKey();

            // Skip shell scripts and metadata (pass data so AXML detection works)
            if (shouldSkip(rawKey, ze.getValue().data)) {
                skipped++;
                continue;
            }

            // FIX: improved smart path remapping
            String targetKey = remapPath(rawKey, apkMap.keySet());

            if (apkMap.containsKey(targetKey)) {
                overridden++;
            } else {
                added++;
            }
            apkMap.put(targetKey, ze.getValue());
        }

        jsProgress("merge", 65,
            added + " اضافه + " + overridden + " جایگزین + " + skipped + " نادیده");
        Thread.sleep(250);

        // ── Phase 4: Write Output APK ──
        jsProgress("write", 67, "در حال نوشتن APK نهایی...");
        File outDir = getCacheDir();
        String outName = "injected_" + System.currentTimeMillis() + ".apk";
        resultApkFile = new File(outDir, outName);
        if (resultApkFile.exists()) resultApkFile.delete();

        int totalEntries = apkMap.size();
        int written = 0;

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(resultApkFile), 65536))) {

            for (Map.Entry<String, EntryData> e : apkMap.entrySet()) {
                String entryName = e.getKey();
                ZipEntry outEntry = new ZipEntry(entryName);
                EntryData ed = e.getValue();

                // CRITICAL FIX: some entry types MUST be STORED (uncompressed).
                //   .dex  → Android dexopt requires uncompressed DEX for mmap
                //   .so   → native libs need 4-byte aligned STORED for direct execution
                // resources.arsc is skipped from injection and stays STORED from original APK
                boolean store = mustBeStored(entryName) || ed.method == ZipEntry.STORED;

                if (store) {
                    // STORED entries: CRC and sizes must be set before putNextEntry
                    CRC32 crc = new CRC32();
                    crc.update(ed.data);
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(ed.data.length);
                    outEntry.setCompressedSize(ed.data.length);
                    outEntry.setCrc(crc.getValue());
                    zos.setLevel(Deflater.NO_COMPRESSION);
                } else {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION);
                }

                zos.putNextEntry(outEntry);
                zos.write(ed.data);
                zos.closeEntry();

                written++;
                if (written % 40 == 0 || written == totalEntries) {
                    int pct = 67 + (int)(((float) written / totalEntries) * 28);
                    jsProgress("write", Math.min(94, pct),
                        "نوشتن: " + written + "/" + totalEntries);
                }
            }
        }

        jsProgress("write", 95, "نوشتن کامل شد ← " + written + " فایل");
        Thread.sleep(200);

        // ── Phase 5: ZIP Alignment (zipalign) ──
        // Android requires STORED entries to start at 4-byte aligned offsets.
        // Without this, resources.arsc can't be mmap'd → parse error on some devices.
        jsProgress("align", 95, "در حال تراز کردن ZIP (zipalign)...");
        try {
            zipalignApk(resultApkFile, 4);
            jsProgress("align", 96, "تراز ZIP ✓");
        } catch (Exception alignEx) {
            // Alignment failed (rare) — APK still usable but may have performance issues
            jsProgress("align", 96, "⚠️ تراز ZIP شکست: " + alignEx.getMessage());
        }
        Thread.sleep(100);

        // ── Phase 6: V1 JAR Signing ──
        // Without a valid signature, Android shows "مشکلی در تجزیه" (parse error).
        // We sign with a self-generated key stored in Android KeyStore.
        jsProgress("sign", 96, "در حال امضای دیجیتال APK (V1)...");
        String signNote = "";
        boolean signedOk = false;
        try {
            signApkV1(resultApkFile);
            jsProgress("sign", 99, "✅ امضای V1 موفق — APK آماده نصب");
            signNote = " | امضا شد ✅";
            signedOk = true;
        } catch (Exception signEx) {
            String errMsg = signEx.getMessage() != null
                ? signEx.getMessage() : signEx.getClass().getSimpleName();
            // Signing failed — APK is unsigned. Android WILL reject it with parse error.
            jsProgress("sign", 99, "❌ امضا شکست: " + errMsg);
            signNote = " | بدون امضا ❌ (نصب نخواهد شد)";
        }
        Thread.sleep(250);

        long outSize = resultApkFile.length();
        double ratio = apkRawSize > 0 ? (double) outSize / apkRawSize * 100 : 100;
        String doneMsg = signedOk
            ? "تزریق و امضا کامل شد! ✓ (حجم: " + formatBytes(outSize) + ")"
            : "تزریق کامل شد اما امضا شکست ❌ — نصب ممکن نیست";
        jsProgress("done", 100, doneMsg);
        Thread.sleep(150);

        String payload = resultApkFile.getAbsolutePath()
            + "|" + formatBytes(outSize) + signNote
            + "|" + added
            + "|" + overridden
            + "|" + totalEntries
            + "|" + String.format("%.1f", ratio);
        jsEvent("onInjectDone", payload);
    }

    // ─────────────────────────────────────
    // Injection Logic Helpers
    // ─────────────────────────────────────

    private static class EntryData {
        final byte[] data;
        final int    method;
        EntryData(byte[] d, int m) { data = d; method = m; }
    }

    /**
     * FIX: Detects a common top-level folder ONLY if ALL entries share it.
     * If any root-level file exists → returns null (no stripping).
     */
    private String detectTopFolderRobust(File zip) {
        String topFolder = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                // FIX: normalize Windows backslash separators (ZIP spec requires '/')
                String name = e.getName().replace('\\', '/');
                // Skip pure directory entries
                if (e.isDirectory() || name.endsWith("/")) { zis.closeEntry(); continue; }
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    // Root-level file → cannot strip any top folder
                    return null;
                }
                String candidate = name.substring(0, slash + 1);
                if (topFolder == null) {
                    topFolder = candidate;
                } else if (!name.startsWith(topFolder)) {
                    // Multiple different top-level folders
                    return null;
                }
                zis.closeEntry();
            }
        } catch (Exception ignored) {}
        return topFolder;
    }

    private String stripTopFolder(String name, String topFolder) {
        // FIX: also normalize backslash (Windows ZIPs) before stripping
        String normalized = name.replace('\\', '/');
        if (topFolder != null && normalized.startsWith(topFolder)) {
            return normalized.substring(topFolder.length());
        }
        return normalized;
    }

    /**
     * Returns true if data starts with Android Binary XML magic bytes (0x03 0x00 0x08 0x00).
     * APK's AndroidManifest.xml is always binary AXML; source XML from decompiled projects
     * starts with '<?xml' and must NOT replace the binary version.
     */
    private static boolean isBinaryAxml(byte[] data) {
        return data != null && data.length >= 4
            && (data[0] & 0xFF) == 0x03 && (data[1] & 0xFF) == 0x00
            && (data[2] & 0xFF) == 0x08 && (data[3] & 0xFF) == 0x00;
    }

    /** Files that should never be injected into an APK */
    private boolean shouldSkip(String path) {
        return shouldSkip(path, null);
    }

    /** Files that should never be injected into an APK (data-aware version) */
    private boolean shouldSkip(String path, byte[] data) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);

        // ── AndroidManifest.xml: smart binary detection ───────────────────────────
        // APK manifests are BINARY AXML. If the ZIP contains source XML (from apktool),
        // injecting it would erase the real manifest → parse error + crash.
        // If the ZIP contains binary AXML (from another APK), allow it to replace.
        if (lower.equals("androidmanifest.xml")) {
            if (data != null && isBinaryAxml(data)) {
                return false; // binary AXML → safe to inject
            }
            return true; // source XML → skip to protect the original binary manifest
        }

        // resources.arsc is the compiled resource table — unique binary format
        if (lower.equals("resources.arsc"))      return true;

        // ── APK signing files from injection ZIP — skip, we add our own ──────────
        // These would be from the original APK the user decompiled; they are
        // invalid for our output APK and must be excluded.
        if (lower.startsWith("meta-inf/") && (
                lower.endsWith(".sf")
                || lower.endsWith(".rsa")
                || lower.endsWith(".dsa")
                || lower.endsWith(".ec")
                || lower.equals("meta-inf/manifest.mf"))) {
            return true;
        }

        // ── Script / metadata / IDE files ─────────────────────────────────────────
        if (lower.endsWith(".sh")
            || lower.endsWith(".bat")
            || lower.endsWith(".cmd")
            || lower.endsWith(".py")
            || lower.endsWith(".rb")
            || lower.endsWith(".md")
            || lower.endsWith(".txt")
            || lower.endsWith(".gradle")
            || lower.endsWith(".gitignore")
            || lower.endsWith(".gitattributes")
            || lower.endsWith(".iml")
            || lower.endsWith(".class")
            || lower.endsWith(".ds_store")
            || lower.endsWith(".classpath")
            || lower.endsWith(".project")) {
            return true;
        }
        // Git internals and IDE config folders
        if (lower.contains("/.git/") || lower.startsWith(".git/")
            || lower.contains("/.idea/") || lower.startsWith(".idea/")
            || lower.contains("/__macosx/") || lower.startsWith("__macosx/")
            || lower.equals("readme")
            || lower.equals("license")
            || lower.equals("licence")) {
            return true;
        }
        return false;
    }

    /**
     * Smart path remapping — maps an injection file to the correct location in an APK.
     *
     * Priority order:
     * 1. Exact path match in APK → keep as-is (override)
     * 2. Path already in a known APK folder → keep as-is
     * 3. Known file types → map to correct APK location
     * 4. Unknown → keep as-is
     */
    private String remapPath(String injPath, Set<String> apkPaths) {
        // 1. Exact match — just override
        if (apkPaths.contains(injPath)) return injPath;

        String lp  = injPath.toLowerCase(Locale.ROOT);
        // Extract just the filename (no leading path)
        String fileName = injPath.contains("/")
            ? injPath.substring(injPath.lastIndexOf('/') + 1)
            : injPath;
        String fileNameLower = fileName.toLowerCase(Locale.ROOT);

        // 2. Already in a known APK top-level folder → keep path intact
        if (injPath.startsWith("res/")
                || injPath.startsWith("assets/")
                || injPath.startsWith("smali/")
                || injPath.startsWith("smali_classes")
                || injPath.startsWith("lib/")
                || injPath.startsWith("META-INF/")
                || injPath.startsWith("kotlin/")
                || injPath.startsWith("okhttp3/")
                || injPath.startsWith("com/")
                || injPath.startsWith("android/")) {
            return injPath;
        }

        // 3. AndroidManifest.xml → root of APK
        if (fileNameLower.equals("androidmanifest.xml")) {
            return "AndroidManifest.xml";
        }

        // 4. DEX files → always place at APK root (never in a subfolder)
        if (lp.endsWith(".dex")) {
            // FIX: return fileName (not injPath) so DEX always lands at APK root
            // even if the injection ZIP wrapped it in a subfolder
            if (fileNameLower.matches("classes\\d*\\.dex")) return fileName;
            // Non-standard name → find next free classesN.dex slot
            int slot = 2;
            while (apkPaths.contains("classes" + slot + ".dex")) slot++;
            return "classes" + slot + ".dex";
        }

        // 5. Smali files → map to smali/ preserving package structure
        if (lp.endsWith(".smali")) {
            // Preserve subfolder structure under smali/
            return "smali/" + injPath;
        }

        // 6. Java/Kotlin source → place in assets/src/ (won't break APK)
        if (lp.endsWith(".java") || lp.endsWith(".kt") || lp.endsWith(".kts")) {
            return "assets/src/" + injPath;
        }

        // 7. XML resources — map based on path context
        if (lp.endsWith(".xml")) {
            // Subfolder hints
            if (lp.contains("/layout/") || fileNameLower.contains("layout")) {
                return "res/layout/" + fileName;
            }
            if (lp.contains("/drawable/") || fileNameLower.contains("drawable")) {
                return "res/drawable/" + fileName;
            }
            if (lp.contains("/values/") || lp.contains("strings")
                    || lp.contains("colors") || lp.contains("dimens")
                    || lp.contains("styles") || lp.contains("themes")
                    || lp.contains("attrs")) {
                return "res/values/" + fileName;
            }
            if (lp.contains("/anim/") || fileNameLower.contains("anim")) {
                return "res/anim/" + fileName;
            }
            if (lp.contains("/xml/") || lp.contains("filepaths")
                    || lp.contains("network_security") || lp.contains("backup")) {
                return "res/xml/" + fileName;
            }
            if (lp.contains("/raw/")) {
                return "res/raw/" + fileName;
            }
            // Generic XML → res/xml/
            return "res/xml/" + fileName;
        }

        // 8. Images → res/drawable/
        if (lp.endsWith(".png") || lp.endsWith(".jpg") || lp.endsWith(".jpeg")
                || lp.endsWith(".webp") || lp.endsWith(".gif") || lp.endsWith(".svg")) {
            return "res/drawable/" + fileName;
        }

        // 9. Fonts → res/font/
        if (lp.endsWith(".ttf") || lp.endsWith(".otf") || lp.endsWith(".woff")
                || lp.endsWith(".woff2")) {
            return "res/font/" + fileName;
        }

        // 10. Native libraries → lib/
        if (lp.endsWith(".so")) {
            // Try to detect ABI from path
            if (lp.contains("arm64") || lp.contains("aarch64")) {
                return "lib/arm64-v8a/" + fileName;
            }
            if (lp.contains("armeabi")) {
                return "lib/armeabi-v7a/" + fileName;
            }
            if (lp.contains("x86_64")) {
                return "lib/x86_64/" + fileName;
            }
            if (lp.contains("x86")) {
                return "lib/x86/" + fileName;
            }
            return "lib/armeabi-v7a/" + fileName;
        }

        // 11. Web assets → assets/
        if (lp.endsWith(".html") || lp.endsWith(".js") || lp.endsWith(".css")
                || lp.endsWith(".json") || lp.endsWith(".wasm")) {
            return "assets/" + injPath;
        }

        // 12. Default: keep path as-is (add to APK without change)
        return injPath;
    }

    // ─────────────────────────────────────
    // V1 JAR Signing (APK Signing Scheme v1)
    // ─────────────────────────────────────

    /**
     * Signs the given APK file in-place using V1 (JAR) signing.
     *
     * Flow:
     *   1. Get or create an RSA-2048 key pair in the Android KeyStore (hardware-backed).
     *   2. Read all ZIP entries from the APK.
     *   3. Compute SHA-256 digests → build META-INF/MANIFEST.MF.
     *   4. Compute SHA-256 of each MANIFEST.MF section → build META-INF/CERT.SF.
     *   5. Sign CERT.SF with SHA256withRSA private key → build PKCS#7 DER block.
     *   6. Rewrite the APK with the three META-INF signing entries appended.
     */
    private void signApkV1(File apkFile) throws Exception {
        // ── Step 1: Get/create signing key in Android KeyStore ──
        KeyStore androidKs = KeyStore.getInstance("AndroidKeyStore");
        androidKs.load(null);

        if (!androidKs.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=APK Injector Pro"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setKeySize(2048)
                .build());
            kpg.generateKeyPair();
        }

        PrivateKey  privKey = (PrivateKey)  androidKs.getKey(KEY_ALIAS, null);
        X509Certificate cert = (X509Certificate) androidKs.getCertificate(KEY_ALIAS);

        // ── Step 2: Read all ZIP entries (use ZipFile for reliable data reading) ──
        Map<String, byte[]>  entryBytes  = new LinkedHashMap<>();
        Map<String, Integer> entryMethod = new LinkedHashMap<>();
        try (ZipFile zf2 = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries2 = zf2.entries();
            while (entries2.hasMoreElements()) {
                ZipEntry ze = entries2.nextElement();
                if (!ze.isDirectory()) {
                    byte[] d;
                    try (InputStream es = zf2.getInputStream(ze)) { d = readStream(es); }
                    entryBytes.put(ze.getName(), d);
                    entryMethod.put(ze.getName(), ze.getMethod());
                }
            }
        }

        // ── Step 3: Build MANIFEST.MF ──
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        StringBuilder mf = new StringBuilder();
        mf.append("Manifest-Version: 1.0\r\n");
        mf.append("Created-By: APK Injector Pro\r\n");
        mf.append("\r\n");

        // Ordered map: entry name → its MANIFEST.MF section text
        Map<String, String> mfSections = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entryBytes.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("META-INF/")) continue;  // skip existing META-INF
            sha256.reset();
            String digest = Base64.encodeToString(sha256.digest(e.getValue()), Base64.NO_WRAP);
            String section = "Name: " + name + "\r\n"
                + "SHA-256-Digest: " + digest + "\r\n"
                + "\r\n";
            mf.append(section);
            mfSections.put(name, section);
        }
        byte[] mfBytes = mf.toString().getBytes("UTF-8");

        // ── Step 4: Build CERT.SF ──
        StringBuilder sf = new StringBuilder();
        sha256.reset();
        String mfDigest = Base64.encodeToString(sha256.digest(mfBytes), Base64.NO_WRAP);
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("Created-By: APK Injector Pro\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(mfDigest).append("\r\n");
        sf.append("\r\n");
        for (Map.Entry<String, String> e : mfSections.entrySet()) {
            sha256.reset();
            String secDigest = Base64.encodeToString(
                sha256.digest(e.getValue().getBytes("UTF-8")), Base64.NO_WRAP);
            sf.append("Name: ").append(e.getKey()).append("\r\n");
            sf.append("SHA-256-Digest: ").append(secDigest).append("\r\n");
            sf.append("\r\n");
        }
        byte[] sfBytes = sf.toString().getBytes("UTF-8");

        // ── Step 5: Sign CERT.SF → PKCS#7 DER ──
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privKey);
        signer.update(sfBytes);
        byte[] sigBytes = signer.sign();
        byte[] pkcs7 = buildPkcs7(cert, sigBytes);

        // ── Step 6: Rewrite APK with signing entries ──
        File tmp = new File(apkFile.getParentFile(), "signed_" + apkFile.getName());
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp), 65536))) {

            // Write original entries (preserving STORED/DEFLATED)
            for (Map.Entry<String, byte[]> e : entryBytes.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();
                ZipEntry ze = new ZipEntry(name);
                int method  = entryMethod.get(name);

                if (method == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(data.length);
                    ze.setCompressedSize(data.length);
                    ze.setCrc(crc.getValue());
                    zos.setLevel(Deflater.NO_COMPRESSION);
                } else {
                    ze.setMethod(ZipEntry.DEFLATED);
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION);
                }
                zos.putNextEntry(ze);
                zos.write(data);
                zos.closeEntry();
            }

            // Append META-INF signing entries (STORED, so they're easy to verify)
            zos.setLevel(Deflater.NO_COMPRESSION);
            writeStoredEntry(zos, "META-INF/MANIFEST.MF", mfBytes);
            writeStoredEntry(zos, "META-INF/CERT.SF",     sfBytes);
            writeStoredEntry(zos, "META-INF/CERT.RSA",    pkcs7);
        }

        // Replace original with signed version.
        // Try rename first (atomic); fall back to byte-copy if rename fails
        // (rename can fail across mount points, which happens on some Android devices).
        apkFile.delete();
        if (!tmp.renameTo(apkFile)) {
            // Fallback: copy bytes, then delete temp
            try (FileInputStream  fi = new FileInputStream(tmp);
                 FileOutputStream fo = new FileOutputStream(apkFile)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = fi.read(buf)) != -1) fo.write(buf, 0, n);
            }
            tmp.delete();
        }
    }

    /** Writes a single STORED (uncompressed) entry to an open ZipOutputStream. */
    private static void writeStoredEntry(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry ze = new ZipEntry(name);
        ze.setMethod(ZipEntry.STORED);
        ze.setSize(data.length);
        ze.setCompressedSize(data.length);
        ze.setCrc(crc.getValue());
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    // ─────────────────────────────────────
    // mustBeStored — entry type policy
    // ─────────────────────────────────────

    /**
     * Returns true if this APK entry MUST be stored uncompressed (STORED method).
     *
     * Rules from the Android platform source:
     *   • classes*.dex  — dexopt mmaps DEX files directly from the APK ZIP
     *   • *.so          — native libs are exec-mmap'd (unless extractNativeLibs="true")
     *   • resources.arsc — resource table is mmap'd directly at runtime
     *
     * Compressing these breaks mmap → Android throws a parse error on install.
     */
    private static boolean mustBeStored(String name) {
        String lc = name.toLowerCase(Locale.ROOT);
        // Strip directory prefix to check just the filename for .dex
        String fn = lc.contains("/") ? lc.substring(lc.lastIndexOf('/') + 1) : lc;
        if (fn.matches("classes\\d*\\.dex")) return true;  // classes.dex, classes2.dex …
        if (lc.endsWith(".so"))              return true;  // native libs
        if (fn.equals("resources.arsc"))    return true;  // resource table
        return false;
    }

    // ─────────────────────────────────────
    // ZIP Alignment (zipalign)
    // ─────────────────────────────────────

    /**
     * Aligns STORED (uncompressed) entries in the APK ZIP so that their data starts
     * at a multiple of {@code alignment} bytes from the beginning of the file.
     *
     * This is the same operation that {@code zipalign -f 4} performs.  Android's
     * PackageManager memory-maps resources.arsc and native libs directly from the
     * ZIP, so unaligned data causes a parse error on many Android versions.
     *
     * Algorithm:
     *   1. Read the whole ZIP into memory.
     *   2. Scan local file headers; for STORED entries, add padding to the extra
     *      field so the data starts at an aligned offset.
     *   3. Rebuild the central directory with updated local header offsets.
     *   4. Write the aligned APK back to the same file.
     */
    private static void zipalignApk(File apkFile, int alignment) throws IOException {
        byte[] src = readAllBytes(apkFile);

        // ── Locate EOCD (End of Central Directory) ──────────────────────────────
        int eocdOff = findEocd(src);
        if (eocdOff < 0) throw new IOException("ZIP: EOCD not found");

        long cdOffset = zU32(src, eocdOff + 16);
        long cdSize   = zU32(src, eocdOff + 12);

        // ── Pass 1: Rebuild local entries with aligned STORED data ──────────────
        ByteArrayOutputStream locOut = new ByteArrayOutputStream(src.length + 4096);

        // Map: old local-header offset → new local-header offset
        // (APKs rarely have >4096 entries, so a pair of int[] is fine)
        int[] oldOff = new int[4096];
        int[] newOff = new int[4096];
        int   offCnt = 0;

        int pos = 0;
        while (pos + 4 <= (int) cdOffset) {
            long sig = zU32(src, pos);
            if (sig != 0x04034b50L) break;  // not a local file header

            int method   = zU16(src, pos + 8);
            int nameLen  = zU16(src, pos + 26);
            int extraLen = zU16(src, pos + 28);
            long cSz     = zU32(src, pos + 18);  // compressed size
            long uSz     = zU32(src, pos + 22);  // uncompressed size

            int dataPos  = pos + 30 + nameLen + extraLen;
            long dataLen = (method == ZipEntry.STORED) ? uSz : cSz;

            // Record offset mapping
            if (offCnt >= oldOff.length) {
                oldOff = Arrays.copyOf(oldOff, offCnt * 2);
                newOff = Arrays.copyOf(newOff, offCnt * 2);
            }
            oldOff[offCnt] = pos;
            newOff[offCnt] = locOut.size();
            offCnt++;

            if (method == ZipEntry.STORED) {
                // Compute padding needed so that data starts at aligned offset
                long futureDataStart = (long) locOut.size() + 30 + nameLen + extraLen;
                int  mod    = (int)(futureDataStart % alignment);
                int  pad    = (mod == 0) ? 0 : (alignment - mod);
                int  newEx  = extraLen + pad;

                // Write local header bytes 0..27 (fixed part before extra-length field)
                locOut.write(src, pos, 28);
                // Write updated extra-field length (bytes 28-29)
                locOut.write(newEx & 0xFF);
                locOut.write((newEx >> 8) & 0xFF);
                // Filename
                locOut.write(src, pos + 30, nameLen);
                // Original extra field
                if (extraLen > 0) locOut.write(src, pos + 30 + nameLen, extraLen);
                // Padding zeros
                for (int i = 0; i < pad; i++) locOut.write(0);
                // Data
                locOut.write(src, dataPos, (int) dataLen);
            } else {
                // DEFLATED: copy local header + data as-is
                locOut.write(src, pos, 30 + nameLen + extraLen + (int) cSz);
            }

            pos = dataPos + (int) dataLen;
        }

        int newCdOffset = locOut.size();

        // ── Pass 2: Rebuild central directory with updated local offsets ─────────
        ByteArrayOutputStream cdOut = new ByteArrayOutputStream((int) cdSize + 256);
        int cdPos = (int) cdOffset;

        while (cdPos + 4 <= src.length) {
            long sig = zU32(src, cdPos);
            if (sig != 0x02014b50L) break;  // not a central directory entry

            int nameLen    = zU16(src, cdPos + 28);
            int extraLen   = zU16(src, cdPos + 30);
            int commentLen = zU16(src, cdPos + 32);
            long oldLocal  = zU32(src, cdPos + 42);

            // Find new local offset
            int newLocal = (int) oldLocal;
            for (int i = 0; i < offCnt; i++) {
                if (oldOff[i] == (int) oldLocal) { newLocal = newOff[i]; break; }
            }

            // Write central entry bytes 0..41, then updated local offset, then rest
            cdOut.write(src, cdPos, 42);
            zW32(cdOut, newLocal);
            cdOut.write(src, cdPos + 46, nameLen + extraLen + commentLen);

            cdPos += 46 + nameLen + extraLen + commentLen;
        }

        // ── Rebuild EOCD ──────────────────────────────────────────────────────────
        ByteArrayOutputStream eocdOut = new ByteArrayOutputStream(22);
        eocdOut.write(src, eocdOff, 16);          // bytes 0-15
        zW32(eocdOut, newCdOffset);               // new CD offset at bytes 16-19
        // bytes 20+ (comment length + comment, if any)
        eocdOut.write(src, eocdOff + 20, src.length - eocdOff - 20);

        // ── Write aligned APK ────────────────────────────────────────────────────
        try (FileOutputStream fos = new FileOutputStream(apkFile)) {
            locOut.writeTo(fos);
            cdOut.writeTo(fos);
            eocdOut.writeTo(fos);
        }
    }

    // ─────────────────────────────────────
    // ZIP binary helpers (little-endian)
    // ─────────────────────────────────────

    /** Read unsigned 16-bit little-endian int from byte array. */
    private static int zU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** Read unsigned 32-bit little-endian long from byte array. */
    private static long zU32(byte[] b, int off) {
        return ((long)(b[off]   & 0xFF))
             | ((long)(b[off+1] & 0xFF) <<  8)
             | ((long)(b[off+2] & 0xFF) << 16)
             | ((long)(b[off+3] & 0xFF) << 24);
    }

    /** Write unsigned 32-bit little-endian to an OutputStream. */
    private static void zW32(OutputStream out, long v) throws IOException {
        out.write((int)( v        & 0xFF));
        out.write((int)((v >>  8) & 0xFF));
        out.write((int)((v >> 16) & 0xFF));
        out.write((int)((v >> 24) & 0xFF));
    }

    /**
     * Find the End of Central Directory (EOCD) record offset.
     * Searches backward from end of file (handles empty ZIP comment).
     */
    private static int findEocd(byte[] zip) {
        // Minimum EOCD is 22 bytes; maximum comment is 65535 bytes
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
            if (zip[i]   == 0x50 && zip[i+1] == 0x4B
             && zip[i+2] == 0x05 && zip[i+3] == 0x06) {
                return i;
            }
        }
        return -1;
    }

    /** Read an entire file into a byte array. */
    private static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return buf;
        }
    }

    // ─────────────────────────────────────
    // PKCS#7 / ASN.1 DER helpers
    // ─────────────────────────────────────

    /**
     * Builds a minimal PKCS#7 SignedData DER block for APK V1 signing.
     *
     * Structure:
     *   ContentInfo { OID signedData, [0] SignedData {
     *     version=1, digestAlgs={SHA-256}, contentInfo={OID data},
     *     [0]certs, signerInfos={ SignerInfo { version=1,
     *       issuerAndSerialNumber, SHA-256, SHA256withRSA, signature } }
     *   }}
     */
    private static byte[] buildPkcs7(X509Certificate cert, byte[] sigBytes)
            throws CertificateEncodingException {
        // OIDs
        byte[] oidSData   = derOid(1,2,840,113549,1,7,2);   // signedData
        byte[] oidData    = derOid(1,2,840,113549,1,7,1);   // data
        byte[] oidSha256  = derOid(2,16,840,1,101,3,4,2,1); // SHA-256
        byte[] oidSha256R = derOid(1,2,840,113549,1,1,11);  // sha256WithRSAEncryption
        byte[] nil        = derNull();

        // AlgorithmIdentifier: SEQUENCE { OID, NULL }
        byte[] digestAlgId = derSeq(oidSha256, nil);
        // EncapsulatedContentInfo: SEQUENCE { OID data }
        byte[] encapCI = derSeq(oidData);
        // Certificates: [0] IMPLICIT { <cert DER> }
        byte[] certDer = cert.getEncoded();
        byte[] certs   = derTagged(0xA0, certDer);
        // IssuerAndSerialNumber: SEQUENCE { issuer Name, serialNumber INTEGER }
        byte[] issuerDer  = cert.getIssuerX500Principal().getEncoded();
        byte[] serialDer  = derInteger(cert.getSerialNumber().toByteArray());
        byte[] issuerSN   = derSeq(issuerDer, serialDer);

        // SignerInfo: SEQUENCE { ver, issuerSN, digestAlg, sigAlg, sig }
        byte[] signerInfo = derSeq(
            derIntSmall(1),             // version
            issuerSN,                   // issuerAndSerialNumber
            digestAlgId,                // digestAlgorithm
            derSeq(oidSha256R, nil),    // signatureAlgorithm
            derOctet(sigBytes)          // signature
        );

        // SignedData: SEQUENCE { ver, digestAlgs, encapCI, certs, signerInfos }
        byte[] signedData = derSeq(
            derIntSmall(1),
            derSet(digestAlgId),
            encapCI,
            certs,
            derSet(signerInfo)
        );

        // ContentInfo: SEQUENCE { OID signedData, [0] EXPLICIT signedData }
        return derSeq(oidSData, derTagged(0xA0, signedData));
    }

    // ── DER/ASN.1 encoding primitives ────────────────────────────────────────

    private static byte[] derLen(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        if (n < 0x100) return new byte[]{(byte) 0x81, (byte)(n & 0xFF)};
        if (n < 0x10000) return new byte[]{
            (byte) 0x82, (byte)((n >> 8) & 0xFF), (byte)(n & 0xFF)};
        return new byte[]{
            (byte) 0x83, (byte)((n>>16)&0xFF), (byte)((n>>8)&0xFF), (byte)(n&0xFF)};
    }

    private static byte[] derTlv(int tag, byte[] content) {
        byte[] lb = derLen(content.length);
        byte[] out = new byte[1 + lb.length + content.length];
        out[0] = (byte)(tag & 0xFF);
        System.arraycopy(lb, 0, out, 1, lb.length);
        System.arraycopy(content, 0, out, 1 + lb.length, content.length);
        return out;
    }

    private static byte[] derCat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] derSeq(byte[]... parts)    { return derTlv(0x30, derCat(parts)); }
    private static byte[] derSet(byte[]... parts)    { return derTlv(0x31, derCat(parts)); }
    private static byte[] derOctet(byte[] data)      { return derTlv(0x04, data); }
    private static byte[] derTagged(int tag, byte[] c){ return derTlv(tag,  c); }
    private static byte[] derNull()                  { return new byte[]{0x05, 0x00}; }
    private static byte[] derIntSmall(int v)         { return new byte[]{0x02,0x01,(byte)(v&0xFF)}; }

    private static byte[] derInteger(byte[] bytes) {
        // Ensure positive (prepend 0x00 if high bit set)
        if (bytes.length > 0 && (bytes[0] & 0x80) != 0) {
            byte[] padded = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, padded, 1, bytes.length);
            return derTlv(0x02, padded);
        }
        // Trim redundant leading zeros
        int s = 0;
        while (s < bytes.length - 1 && bytes[s] == 0 && (bytes[s+1] & 0x80) == 0) s++;
        if (s > 0) {
            byte[] t = new byte[bytes.length - s];
            System.arraycopy(bytes, s, t, 0, t.length);
            return derTlv(0x02, t);
        }
        return derTlv(0x02, bytes);
    }

    private static byte[] derOid(int... ids) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(40 * ids[0] + ids[1]);
        for (int i = 2; i < ids.length; i++) {
            int v = ids[i];
            if (v == 0) { bos.write(0); continue; }
            byte[] buf = new byte[5];
            int j = 4;
            buf[j] = (byte)(v & 0x7F);
            v >>>= 7;
            while (v > 0) {
                buf[--j] = (byte)((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            for (; j <= 4; j++) bos.write(buf[j]);
        }
        return derTlv(0x06, bos.toByteArray());
    }

    private byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private String formatBytes(long b) {
        if (b < 1024)           return b + " B";
        if (b < 1024 * 1024)   return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.2f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ─────────────────────────────────────
    // Save to Downloads
    // ─────────────────────────────────────

    private void saveToDownloads() {
        new Thread(() -> {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    jsEvent("onSaveError", "خطا در ایجاد پوشه دانلودها");
                    return;
                }
                String name = "APK_Injected_" + System.currentTimeMillis() + ".apk";
                File dest = new File(downloads, name);

                try (FileInputStream  in  = new FileInputStream(resultApkFile);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                // Notify MediaStore (deprecated but harmless on newer APIs)
                Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scan.setData(Uri.fromFile(dest));
                sendBroadcast(scan);

                jsEvent("onSaveDone",
                    dest.getAbsolutePath() + "|" + formatBytes(dest.length()));
            } catch (Exception e) {
                jsEvent("onSaveError", "خطا در ذخیره: " + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────
    // JS Communication
    // ─────────────────────────────────────

    private void jsProgress(String phase, int pct, String message) {
        String js = "window.__onProgress&&window.__onProgress('"
            + esc(phase) + "'," + pct + ",'" + esc(message) + "')";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void jsEvent(String name, String data) {
        String js = "window.__ev&&window.__ev('" + esc(name) + "','" + esc(data) + "')";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'",  "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("<",  "\\u003c")
                .replace(">",  "\\u003e");
    }

    // ─────────────────────────────────────
    // Activity Results
    // ─────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_MANAGE_EXT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                saveToDownloads();
            } else {
                jsEvent("onSaveError", "دسترسی به فضای ذخیره‌سازی رد شد");
            }
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (pendingJsCallback != null) {
                jsEvent("onFileCancelled", pendingJsCallback);
                pendingJsCallback = null;
            }
            return;
        }

        Uri uri = data.getData();
        String ext = (requestCode == REQ_APK) ? ".apk" : ".zip";
        String callbackName = pendingJsCallback;
        pendingJsCallback = null;

        new Thread(() -> {
            try {
                File temp = copyUriToCache(uri, ext);
                String fileName = queryFileName(uri);
                if (fileName == null || fileName.isEmpty()) fileName = "file" + ext;

                // FIX B: whitelist callback name to prevent any JS injection via malformed cb
                String defaultCb = (requestCode == REQ_APK) ? "onApkSelected" : "onZipSelected";
                String cb = "onApkSelected".equals(callbackName) || "onZipSelected".equals(callbackName)
                    ? callbackName : defaultCb;

                String js = "window." + cb + "&&window." + cb
                    + "('" + esc(temp.getAbsolutePath()) + "','"
                    + esc(fileName) + "'," + temp.length() + ")";
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript(js, null);
                });
            } catch (Exception e) {
                jsEvent("onError", "خطا در بارگذاری فایل: " + e.getMessage());
            }
        }).start();
    }

    private File copyUriToCache(Uri uri, String ext) throws IOException {
        String prefix = ext.equals(".apk") ? "apk_input_" : "zip_input_";
        File temp = new File(getCacheDir(), prefix + System.currentTimeMillis() + ext);
        try (InputStream in  = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("نمی‌توان فایل را باز کرد");
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return temp;
    }

    private String queryFileName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String path = uri.getPath();
        if (path != null) {
            int cut = path.lastIndexOf('/');
            if (cut >= 0) return path.substring(cut + 1);
        }
        return null;
    }

    // ─────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM_WRITE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                saveToDownloads();
            } else {
                jsEvent("onSaveError", "دسترسی به حافظه رد شد");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
