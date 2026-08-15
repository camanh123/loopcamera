package com.loopcamera.loop20;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_TREE = 10;
    private static final int REQ_STORAGE = 11;

    private AppPreferences prefs;
    private TextView txtFolder;
    private TextView txtStatus;
    private TextView txtCount;
    private TextView txtMode;
    private TextView txtNewest;
    private TextView txtOldest;
    private TextView txtFileState;
    private TextView txtPlan;
    private TextView txtLog;
    private TextView txtFailsafe;
    private Button btnTest;
    private Button btnLoop;
    private Button btnStop;
    private Button btnClearFailsafe;
    private TextView txtProbeVerdict;
    private TextView txtProbeReport;
    private PrivateUsbProbe.Report lastPrivateProbe;

    private final LoopLog.Listener logListener = text -> {
        if (txtLog != null) {
            txtLog.setText(text);
        }
    };

    private final LoopStateBus.Listener stateListener = this::renderState;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new AppPreferences(this);

        txtFolder = findViewById(R.id.txtFolder);
        txtStatus = findViewById(R.id.txtStatus);
        txtCount = findViewById(R.id.txtCount);
        txtMode = findViewById(R.id.txtMode);
        txtNewest = findViewById(R.id.txtNewest);
        txtOldest = findViewById(R.id.txtOldest);
        txtFileState = findViewById(R.id.txtFileState);
        txtPlan = findViewById(R.id.txtPlan);
        txtLog = findViewById(R.id.txtLog);
        txtFailsafe = findViewById(R.id.txtFailsafe);
        btnTest = findViewById(R.id.btnTestMode);
        btnLoop = findViewById(R.id.btnLoopMode);
        btnStop = findViewById(R.id.btnStopLoop);
        btnClearFailsafe = findViewById(R.id.btnClearFailsafe);
        txtProbeVerdict = findViewById(R.id.txtProbeVerdict);
        txtProbeReport = findViewById(R.id.txtProbeReport);

        findViewById(R.id.btnChooseFolder).setOnClickListener(v -> {
            try {
                openFolderPicker();
            } catch (Throwable t) {
                CrashLog.write(this, t);
                LoopLog.get().e("Crash khi bấm CHỌN THƯ MỤC", t);
                Toast.makeText(this, "Trình chọn hệ thống lỗi. Chuyển sang quét USB.", Toast.LENGTH_LONG).show();
                requestStorageThenScanUsb();
            }
        });
        btnTest.setOnClickListener(v -> setTestMode());
        btnLoop.setOnClickListener(v -> confirmLoopMode());
        btnStop.setOnClickListener(v -> setTestMode());
        btnClearFailsafe.setOnClickListener(v -> confirmClearFailsafe());
        findViewById(R.id.btnUsbProbe).setOnClickListener(v -> confirmUsbProbe());
        findViewById(R.id.btnCopyProbe).setOnClickListener(v -> copyProbeReport());
        findViewById(R.id.btnExportProbe).setOnClickListener(v -> exportProbeReport());

        if (prefs.hasSavedFolder()) {
            LoopMonitorService.start(this);
        }
        renderState(LoopStateBus.get().latest());
    }

    @Override
    protected void onStart() {
        super.onStart();
        LoopLog.get().addListener(logListener);
        LoopStateBus.get().addListener(stateListener);
    }

    @Override
    protected void onStop() {
        LoopLog.get().removeListener(logListener);
        LoopStateBus.get().removeListener(stateListener);
        super.onStop();
    }

    private void confirmUsbProbe() {
        new AlertDialog.Builder(this)
                .setTitle("USB PRIVATE STORAGE PROBE")
                .setMessage("CHỈ KIỂM TRA — KHÔNG XÓA VIDEO\n\n"
                        + "Chỉ tạo/xóa .cameraloop_probe.tmp trong thư mục app-private USB/Camera.\n"
                        + "Không đụng /DCIM/Camera. Không đụng MP4. Không đổi production.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Chạy probe", (d, w) -> startUsbProbe())
                .show();
    }

    private void startUsbProbe() {
        Toast.makeText(this, "USB PRIVATE STORAGE PROBE — KHÔNG XÓA VIDEO", Toast.LENGTH_LONG).show();
        if (txtProbeVerdict != null) {
            txtProbeVerdict.setText("USB PRIVATE STORAGE PROBE: đang chạy…");
            txtProbeVerdict.setTextColor(getColor(R.color.warn));
        }
        new Thread(() -> {
            PrivateUsbProbe.Report report;
            try {
                report = PrivateUsbProbe.run(getApplicationContext());
            } catch (Throwable t) {
                CrashLog.write(this, t);
                LoopLog.get().e("PRIVATE USB PROBE crashed", t);
                report = new PrivateUsbProbe.Report();
                report.success = false;
                report.reason = "probe crashed: " + t.getClass().getName() + ": " + t.getMessage();
                report.exception = t.getClass().getName() + ": " + t.getMessage();
            }
            final PrivateUsbProbe.Report shown = report;
            runOnUiThread(() -> showProbeReport(shown));
        }, "private-usb-probe").start();
    }

    private void showProbeReport(PrivateUsbProbe.Report report) {
        lastPrivateProbe = report;
        if (txtProbeVerdict != null) {
            txtProbeVerdict.setText((report.success ? "FINAL PROBE RESULT: PASS" : "FINAL PROBE RESULT: FAIL")
                    + "\n" + report.reason);
            txtProbeVerdict.setTextColor(getColor(report.success ? R.color.ok : R.color.error));
        }
        if (txtProbeReport != null) {
            txtProbeReport.setText(report.screenText());
        }
        if (txtLog != null) {
            txtLog.setText(LoopLog.get().text());
        }
        Toast.makeText(this, report.success ? "PROBE PASS" : "PROBE FAIL", Toast.LENGTH_LONG).show();
    }

    private void copyProbeReport() {
        if (lastPrivateProbe == null) {
            Toast.makeText(this, "Chưa có báo cáo. Bấm USB PRIVATE STORAGE PROBE trước.", Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "Clipboard không dùng được. Dùng EXPORT.", Toast.LENGTH_LONG).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("CameraLoop private USB probe",
                PrivateUsbProbe.fullExportText(lastPrivateProbe)));
        Toast.makeText(this, "Đã copy báo cáo", Toast.LENGTH_SHORT).show();
    }

    private void exportProbeReport() {
        PrivateUsbProbe.Report report = lastPrivateProbe;
        if (report == null) {
            Toast.makeText(this, "Chưa có báo cáo. Bấm USB PRIVATE STORAGE PROBE trước.", Toast.LENGTH_LONG).show();
            return;
        }
        File f = PrivateUsbProbe.exportFile(this, report);
        if (f == null) {
            Toast.makeText(this, "EXPORT FAILED", Toast.LENGTH_LONG).show();
            return;
        }
        report.exportPath = f.getAbsolutePath();
        lastPrivateProbe = report;
        if (txtProbeReport != null) {
            txtProbeReport.setText(report.screenText());
        }
        Toast.makeText(this, "EXPORT: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }

    /**
     * Head units like CARFU often have no DocumentsUI. Launching
     * ACTION_OPEN_DOCUMENT_TREE without a handler throws ActivityNotFoundException
     * and kills the app. Default path is USB scan; SAF is optional and guarded.
     */
    private void openFolderPicker() {
        boolean saf = hasSafDocumentTreePicker();
        LoopLog.get().i("CHỌN THƯ MỤC: SAF picker=" + saf
                + (saf ? " (" + safPickerComponent() + ")" : " — thiết bị không có DocumentsUI"));
        requestStorageThenScanUsb();
    }

    private boolean hasSafDocumentTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        return intent.resolveActivity(getPackageManager()) != null;
    }

    private String safPickerComponent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        ResolveInfo info = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) {
            return "none";
        }
        return info.activityInfo.packageName + "/" + info.activityInfo.name;
    }

    private void requestStorageThenScanUsb() {
        boolean read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        boolean write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (!read || !write) {
            LoopLog.get().i("Xin quyền bộ nhớ để quét USB/DCIM (Android 10 File API).");
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
            return;
        }
        showUsbChooser();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) {
            return;
        }
        boolean granted = false;
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (!granted) {
            LoopLog.get().w("Quyền bộ nhớ bị từ chối — vẫn thử quét USB (một số head unit cho đọc USB không cần quyền).");
        } else {
            LoopLog.get().i("Đã có quyền bộ nhớ.");
        }
        showUsbChooser();
    }

    private void showUsbChooser() {
        List<UsbLocator.Candidate> found = UsbLocator.findDcimCandidates(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Chọn thư mục DCIM");
        if (found.isEmpty()) {
            b.setMessage("Không thấy thư mục DCIM trên USB.\n\n"
                    + "Hãy cắm USB, chờ Android mount xong, rồi bấm Quét lại.\n"
                    + "App không hard-code đường dẫn; nó quét volume đang gắn.");
            b.setPositiveButton("Quét lại", (d, w) -> showUsbChooser());
            if (hasSafDocumentTreePicker()) {
                b.setNeutralButton("Trình hệ thống", (d, w) -> launchSafPickerGuarded());
            }
            b.setNegativeButton("Hủy", null);
            b.show();
            LoopLog.get().w("Không tìm thấy DCIM. USB có thể chưa mount hoặc không đọc được.");
            return;
        }
        CharSequence[] items = new CharSequence[found.size()];
        for (int i = 0; i < found.size(); i++) {
            items[i] = found.get(i).display();
        }
        b.setItems(items, (d, which) -> selectFileFolder(found.get(which).directory));
        if (hasSafDocumentTreePicker()) {
            b.setNeutralButton("Trình hệ thống", (d, w) -> launchSafPickerGuarded());
        }
        b.setNegativeButton("Hủy", null);
        b.show();
    }

    private void launchSafPickerGuarded() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
            if (intent.resolveActivity(getPackageManager()) == null) {
                LoopLog.get().e("Không có Activity cho ACTION_OPEN_DOCUMENT_TREE — bỏ qua SAF.");
                Toast.makeText(this, "Head unit không hỗ trợ SAF", Toast.LENGTH_LONG).show();
                return;
            }
            LoopLog.get().i("Mở SAF ACTION_OPEN_DOCUMENT_TREE");
            startActivityForResult(intent, REQ_TREE);
        } catch (Throwable t) {
            CrashLog.write(this, t);
            LoopLog.get().e("SAF startActivityForResult crash (thường là ActivityNotFoundException trên head unit)", t);
            Toast.makeText(this, "SAF không chạy được trên máy này. Dùng quét USB.", Toast.LENGTH_LONG).show();
            showUsbChooser();
        }
    }

    private void selectFileFolder(File dir) {
        FolderResolver.save(this, prefs, dir);
        prefs.setMode(AppPreferences.MODE_TEST);
        LoopLog.get().i("Đã chọn và nhớ thư mục: " + dir.getAbsolutePath()
                + " (relative=" + prefs.getRelativePath() + ")");
        LoopLog.get().i("TEST MODE mặc định — không xóa/rename/modify. Tên file timestamp được giữ nguyên.");
        LoopMonitorService.start(this);
        Toast.makeText(this, "Đã chọn: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        maybeOfferSafWriteGrant(dir);
    }

    /**
     * File.delete() on /storage/USB1 is often blocked on Android 10.
     * Offer a one-time persistable tree grant when DocumentsUI exists.
     */
    private void maybeOfferSafWriteGrant(File dir) {
        if (!hasSafDocumentTreePicker()) {
            LoopLog.get().i("Không có DocumentsUI — xóa USB sẽ dùng MediaStore/DocumentsContract fallback.");
            return;
        }
        if (UsbDeleter.hasPersistedTree(this, prefs.getSafTreeUri())) {
            LoopLog.get().i("Đã có SAF write permission.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Cấp quyền xóa USB (một lần)")
                .setMessage("Android 10 có thể chặn File.delete() trên USB.\n\n"
                        + "Chọn đúng thư mục DCIM/Camera trong trình hệ thống để app xóa được video cũ (LOOP MODE).\n"
                        + "TEST MODE vẫn không xóa.")
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Cấp quyền", (d, w) -> launchSafPickerGuarded())
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            LoopLog.get().w("SAF bị hủy hoặc không trả thư mục. Có thể chọn bằng quét USB.");
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException first) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                LoopLog.get().w("Thiết bị không cho lưu quyền vĩnh viễn — dùng quyền phiên hiện tại.");
            }
        }
        prefs.setSafTreeUri(uri);
        prefs.setMode(AppPreferences.MODE_TEST);
        LoopLog.get().i("Đã lưu SAF tree (persistable): " + uri
                + " granted=" + UsbDeleter.hasPersistedTree(this, uri));
        LoopLog.get().i("TEST MODE mặc định — không xóa/đổi tên cho đến khi bật LOOP MODE.");
        LoopMonitorService.start(this);
    }

    private void setTestMode() {
        prefs.setMode(AppPreferences.MODE_TEST);
        LoopLog.get().i("Chuyển sang TEST MODE — không xóa file.");
        Toast.makeText(this, "TEST MODE", Toast.LENGTH_SHORT).show();
        renderState(LoopStateBus.get().latest());
    }

    private void confirmLoopMode() {
        if (!prefs.hasSavedFolder()) {
            Toast.makeText(this, "Hãy chọn thư mục DCIM/Camera trước", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.isFailsafe()) {
            Toast.makeText(this, "FAILSAFE đang bật. Hãy kiểm tra DCIM rồi xóa cờ failsafe.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Bật LOOP MODE?")
                .setMessage("LOOP MODE chỉ XÓA video COMPLETE cũ nhất khi có hơn 30 file timestamp hoàn tất.\n\n"
                        + "Không rename, không sửa nội dung MP4.\n"
                        + "TEST MODE thì không đụng file.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Bật LOOP MODE", (d, w) -> {
                    prefs.setMode(AppPreferences.MODE_LOOP);
                    LoopLog.get().i("LOOP MODE đã bật — chỉ xóa oldest COMPLETE khi > 30. Tên file giữ nguyên.");
                    LoopMonitorService.start(this);
                    Toast.makeText(this, "LOOP MODE", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmClearFailsafe() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa cờ FAILSAFE?")
                .setMessage("Chỉ làm sau khi bạn đã kiểm tra file trong DCIM/Camera.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đã kiểm tra", (d, w) -> {
                    prefs.clearFailsafe();
                    prefs.setMode(AppPreferences.MODE_TEST);
                    LoopLog.get().i("Đã xóa FAILSAFE. Quay về TEST MODE.");
                })
                .show();
    }

    private void renderState(LoopUiState s) {
        if (txtFolder == null) {
            return;
        }
        String mode = prefs.getMode();
        txtFolder.setText("USB/DCIM: " + s.folderLabel);
        txtStatus.setText("Status: " + s.phase
                + (s.statusDetail.isEmpty() ? "" : "\n" + s.statusDetail));
        txtCount.setText("Video count: " + s.videoCount + " / " + LoopPlanner.MAX_VIDEOS);
        txtMode.setText("Mode: " + (AppPreferences.MODE_LOOP.equals(mode) ? "LOOP MODE" : "TEST MODE"));
        txtNewest.setText("Newest:\n" + s.newest);
        txtOldest.setText("Oldest:\n" + s.oldest);
        if (txtFileState != null) {
            txtFileState.setText("Current state:\nNewest: " + s.newestState
                    + "\nOldest: " + s.oldestState);
        }

        if (s.planned.isEmpty()) {
            txtPlan.setText("Predicted action: (không xóa — COMPLETE ≤ "
                    + LoopPlanner.MAX_VIDEOS + ")");
        } else {
            StringBuilder sb = new StringBuilder("Predicted action:\n");
            for (String line : s.planned) {
                sb.append("• ").append(line).append('\n');
            }
            txtPlan.setText(sb.toString().trim());
        }

        boolean failsafe = prefs.isFailsafe();
        txtFailsafe.setVisibility(failsafe ? View.VISIBLE : View.GONE);
        btnClearFailsafe.setVisibility(failsafe ? View.VISIBLE : View.GONE);
        if (failsafe) {
            txtFailsafe.setText("FAILSAFE: " + prefs.getFailsafeReason());
        }

        boolean loop = AppPreferences.MODE_LOOP.equals(mode);
        btnStop.setVisibility(loop ? View.VISIBLE : View.GONE);
        btnTest.setEnabled(loop);
        btnLoop.setEnabled(!loop && !failsafe);
        styleModeButtons(loop);
    }

    private void styleModeButtons(boolean loop) {
        btnTest.setBackgroundColor(getColor(loop ? R.color.surface : R.color.test));
        btnLoop.setBackgroundColor(getColor(loop ? R.color.loop : R.color.surface));
    }
}
