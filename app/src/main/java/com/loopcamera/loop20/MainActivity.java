package com.loopcamera.loop20;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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

        if (prefs.hasSavedFolder()) {
            LoopMonitorService.start(this);
        }
        SafDiagnostics.logPermissionCheck(this, prefs, "onCreate");
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
                .setTitle("USB FILESYSTEM PROBE")
                .setMessage("CHỈ KIỂM TRA — KHÔNG XÓA VIDEO\n\n"
                        + "Chỉ tạo/xóa file thử:\n"
                        + UsbFilesystemProbe.PROBE_PATH
                        + "\n\nKhông xóa MP4. Không rename. Không SAF.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Chạy probe", (d, w) -> startUsbProbe())
                .show();
    }

    private void startUsbProbe() {
        Toast.makeText(this, "CHỈ KIỂM TRA — KHÔNG XÓA VIDEO", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                UsbFilesystemProbe.run(getApplicationContext());
            } catch (Throwable t) {
                CrashLog.write(this, t);
                UsbDeleteLog.e("PROBE_START", "probe crashed", t);
            }
        }, "usb-fs-probe").start();
    }

    /**
     * Head units like CARFU often have no DocumentsUI. Launching
     * ACTION_OPEN_DOCUMENT_TREE without a handler throws ActivityNotFoundException
     * and kills the app. Default path is USB scan; SAF is optional and guarded.
     * Diagnostic only: this method still does NOT auto-launch SAF.
     */
    private void openFolderPicker() {
        boolean resolvable = SafDiagnostics.openDocumentTreeResolvable(this);
        SafDiagnostics.logPermissionCheck(this, prefs, "CHON_THU_MUC");
        UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH,
                "button=CHỌN THƯ MỤC launched=false"
                + " reason=default_usb_file_scan"
                + " openDocumentTreeResolvable=" + resolvable
                + " pickerComponent=" + SafDiagnostics.pickerComponent(this)
                + " documentsUiPackages=" + SafDiagnostics.documentsUiPackages(this)
                + " note=SAF_only_if_user_taps_Trình_hệ_thống");
        requestStorageThenScanUsb();
    }

    private boolean hasSafDocumentTreePicker() {
        return SafDiagnostics.openDocumentTreeResolvable(this);
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
            attachOptionalSafPickerButton(b);
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
        attachOptionalSafPickerButton(b);
        b.setNegativeButton("Hủy", null);
        b.show();
    }

    private void attachOptionalSafPickerButton(AlertDialog.Builder b) {
        if (hasSafDocumentTreePicker()) {
            UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH,
                    "usb_chooser shows Trình_hệ_thống ACTION_OPEN_DOCUMENT_TREE=AVAILABLE"
                    + " pickerComponent=" + SafDiagnostics.pickerComponent(this));
            b.setNeutralButton("Trình hệ thống", (d, w) -> launchSafPickerGuarded());
        } else {
            UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH,
                    "usb_chooser hides Trình_hệ_thống ACTION_OPEN_DOCUMENT_TREE=UNAVAILABLE documentsUi="
                    + SafDiagnostics.documentsUiPackages(this));
        }
    }

    private void launchSafPickerGuarded() {
        boolean resolvable = SafDiagnostics.openDocumentTreeResolvable(this);
        UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH,
                "source=Trình_hệ_thống_or_grant_dialog"
                + " action=ACTION_OPEN_DOCUMENT_TREE"
                + " openDocumentTreeResolvable=" + resolvable
                + " pickerComponent=" + SafDiagnostics.pickerComponent(this)
                + " documentsUiPackages=" + SafDiagnostics.documentsUiPackages(this));
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
            UsbDeleteLog.i(SafDiagnostics.EVENT_PERSISTABLE_FLAGS,
                    "outgoing read=true write=true persistable=true");
            if (intent.resolveActivity(getPackageManager()) == null) {
                UsbDeleteLog.e(SafDiagnostics.EVENT_PICKER_LAUNCH,
                        "launched=false reason=no_activity ACTION_OPEN_DOCUMENT_TREE UNAVAILABLE");
                Toast.makeText(this, "Head unit không hỗ trợ SAF", Toast.LENGTH_LONG).show();
                return;
            }
            UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH, "launched=true startActivityForResult REQ_TREE");
            startActivityForResult(intent, REQ_TREE);
        } catch (Throwable t) {
            CrashLog.write(this, t);
            UsbDeleteLog.e(SafDiagnostics.EVENT_PICKER_LAUNCH,
                    "launched=false reason=exception", t);
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
            UsbDeleteLog.i(SafDiagnostics.EVENT_PERMISSION_CHECK,
                    "reason=after_file_folder_pick documentsUi=UNAVAILABLE"
                    + " ACTION_OPEN_DOCUMENT_TREE=UNAVAILABLE"
                    + " path=" + dir.getAbsolutePath()
                    + " note=no_SAF_grant_dialog");
            return;
        }
        if (UsbDeleter.hasPersistedTree(this, prefs.getSafTreeUri())) {
            UsbDeleteLog.i(SafDiagnostics.EVENT_PERMISSION_CHECK,
                    "reason=after_file_folder_pick alreadyGranted=true uri=" + prefs.getSafTreeUri());
            return;
        }
        UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_LAUNCH,
                "offering_grant_dialog ACTION_OPEN_DOCUMENT_TREE=AVAILABLE path=" + dir.getAbsolutePath());
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
        Uri uri = data != null ? data.getData() : null;
        int intentFlags = data != null ? data.getFlags() : 0;
        boolean flagRead = (intentFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean flagWrite = (intentFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        boolean flagPersistable = (intentFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        UsbDeleteLog.i(SafDiagnostics.EVENT_PICKER_RESULT,
                "resultCode=" + resultCode
                + " resultOk=" + (resultCode == RESULT_OK)
                + " dataNull=" + (data == null)
                + " uriNull=" + (uri == null));
        if (resultCode != RESULT_OK || uri == null) {
            UsbDeleteLog.w(SafDiagnostics.EVENT_TREE_URI, "none reason=cancelled_or_empty");
            return;
        }
        UsbDeleteLog.i(SafDiagnostics.EVENT_TREE_URI,
                "uri=" + uri + " scheme=" + uri.getScheme() + " authority=" + uri.getAuthority());
        UsbDeleteLog.i(SafDiagnostics.EVENT_PERSISTABLE_FLAGS,
                "intentFlags=" + intentFlags
                + " grantRead=" + flagRead
                + " grantWrite=" + flagWrite
                + " grantPersistable=" + flagPersistable);
        boolean took = false;
        String takeApi = "READ|WRITE";
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            took = true;
        } catch (SecurityException first) {
            UsbDeleteLog.e(SafDiagnostics.EVENT_TAKE_PERMISSION,
                    "attempt=READ|WRITE failed", first);
            takeApi = "WRITE";
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                took = true;
            } catch (SecurityException e) {
                UsbDeleteLog.e(SafDiagnostics.EVENT_TAKE_PERMISSION,
                        "attempt=WRITE failed", e);
            }
        }
        UsbDeleteLog.i(SafDiagnostics.EVENT_TAKE_PERMISSION,
                "ok=" + took + " api=" + takeApi);
        prefs.setSafTreeUri(uri);
        prefs.setMode(AppPreferences.MODE_TEST);
        Uri restored = prefs.getSafTreeUri();
        boolean writeGranted = UsbDeleter.hasPersistedTree(this, restored);
        UsbDeleteLog.i(SafDiagnostics.EVENT_PERSISTED_URI,
                "stored=" + restored
                + " matchesReturned=" + (restored != null && restored.equals(uri))
                + " persistedWrite=" + writeGranted
                + " " + SafDiagnostics.persistedPermissionDump(this));
        SafDiagnostics.logDocumentFileResolve(this, restored, "(tree-root)");
        SafDiagnostics.logPermissionCheck(this, prefs, "after_saf_picker");
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
                    boolean resetOk = DeletePolicy.failsafeResetComplete(
                            prefs.isFailsafe(), prefs.getDeleteFailStreak(), prefs.getMode());
                    UsbDeleteLog.i("FAILSAFE_RESET",
                            "button=ĐÃ KIỂM TRA / XÓA FAILSAFE"
                            + " failsafe=" + prefs.isFailsafe()
                            + " streak=" + prefs.getDeleteFailStreak()
                            + " mode=" + prefs.getMode()
                            + " complete=" + resetOk);
                    LoopLog.get().i("Đã xóa FAILSAFE. Quay về TEST MODE. streak=0");
                    renderState(LoopStateBus.get().latest());
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
