package com.modkit.injector;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.modkit.injector.engine.InjectionMethod;
import com.modkit.injector.engine.InjectionOptions;
import com.modkit.injector.engine.InjectionResult;
import com.modkit.injector.engine.Injector;
import com.modkit.injector.engine.Logger;
import com.modkit.injector.engine.util.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    private TextView tvApk, tvSo, tvLog;
    private EditText etLibName;
    private Spinner spAbi, spMethod;
    private Button btnInject, btnShare;
    private ProgressBar progress;

    private File apkFile, soFile, outputFile;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> pickApk =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) apkFile = importFile(uri, "apk", tvApk, "APK");
            });

    private final ActivityResultLauncher<String[]> pickSo =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    soFile = importFile(uri, "so", tvSo, "library");
                    if (soFile != null && etLibName.getText().length() == 0) {
                        etLibName.setHint(defaultLibName(soFile.getName()));
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvApk = findViewById(R.id.tvApk);
        tvSo = findViewById(R.id.tvSo);
        tvLog = findViewById(R.id.tvLog);
        etLibName = findViewById(R.id.etLibName);
        spAbi = findViewById(R.id.spAbi);
        spMethod = findViewById(R.id.spMethod);
        btnInject = findViewById(R.id.btnInject);
        btnShare = findViewById(R.id.btnShare);
        progress = findViewById(R.id.progress);

        spAbi.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ABIS));
        spMethod.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, InjectionMethod.values()));

        findViewById(R.id.btnPickApk).setOnClickListener(v ->
                pickApk.launch(new String[]{"application/vnd.android.package-archive", "application/octet-stream", "*/*"}));
        findViewById(R.id.btnPickSo).setOnClickListener(v ->
                pickSo.launch(new String[]{"application/octet-stream", "*/*"}));

        btnInject.setOnClickListener(v -> startInjection());
        btnShare.setOnClickListener(v -> shareOutput());
    }

    // --- pick & import ------------------------------------------------------

    private File importFile(Uri uri, String ext, TextView label, String what) {
        try {
            String name = queryName(uri);
            if (name == null) name = what + "." + ext;
            File dest = new File(getCacheDir(), "in_" + ext + "_" + sanitize(name));
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new Exception("cannot open stream");
                IOUtils.copy(in, out);
            }
            label.setText(name + "  (" + dest.length() / 1024 + " KB)");
            return dest;
        } catch (Exception e) {
            toast("Failed to read " + what + ": " + e.getMessage());
            return null;
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String defaultLibName(String fileName) {
        String n = fileName;
        if (n.endsWith(".so")) n = n.substring(0, n.length() - 3);
        if (n.startsWith("lib")) n = n.substring(3);
        return n;
    }

    // --- run ----------------------------------------------------------------

    private void startInjection() {
        if (apkFile == null) { toast("Pick an APK first"); return; }
        if (soFile == null) { toast("Pick a .so first"); return; }

        final InjectionOptions opts = new InjectionOptions();
        opts.targetApk = apkFile;
        opts.soFile = soFile;
        opts.abi = (String) spAbi.getSelectedItem();
        opts.method = (InjectionMethod) spMethod.getSelectedItem();
        String ln = etLibName.getText().toString().trim();
        opts.libName = ln.isEmpty() ? null : ln;

        File outDir = new File(getExternalFilesDir(null), "output");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        String base = apkFile.getName().replaceAll("\\.apk$", "").replaceFirst("^in_apk_", "");
        opts.outApk = new File(outDir, base + "-modded.apk");

        tvLog.setText("");
        setBusy(true);
        btnShare.setVisibility(View.GONE);

        final Logger logger = line -> runOnUiThread(() ->
                tvLog.append((tvLog.length() == 0 ? "" : "\n") + line));

        exec.execute(() -> {
            InjectionResult result = new Injector(this).run(opts, logger);
            runOnUiThread(() -> {
                setBusy(false);
                if (result.success) {
                    outputFile = result.outApk;
                    btnShare.setVisibility(View.VISIBLE);
                    toast("Done ✓");
                } else {
                    toast("Failed: " + result.message);
                }
            });
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnInject.setEnabled(!busy);
    }

    // --- share / install ----------------------------------------------------

    private void shareOutput() {
        if (outputFile == null || !outputFile.exists()) { toast("No output yet"); return; }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outputFile);

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share / save patched APK"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }
}
