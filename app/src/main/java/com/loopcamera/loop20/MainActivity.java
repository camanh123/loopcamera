package com.loopcamera.loop20;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_TREE = 20;

    private AppPreferences prefs;
    private TextView txtFolder;
    private TextView txtStatus;
    private TextView txtCount;
    private TextView txtMode;
    private TextView txtNewest;
    private TextView txtOldest;
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
        txtPlan = findViewById(R.id.txtPlan);
        txtLog = findViewById(R.id.txtLog);
        txtFailsafe = findViewById(R.id.txtFailsafe);
        btnTest = findViewById(R.id.btnTestMode);
        btnLoop = findViewById(R.id.btnLoopMode);
        btnStop = findViewById(R.id.btnStopLoop);
        btnClearFailsafe = findViewById(R.id.btnClearFailsafe);

        findViewById(R.id.btnChooseFolder).setOnClickListener(v -> openFolderPicker());
        btnTest.setOnClickListener(v -> setTestMode());
        btnLoop.setOnClickListener(v -> confirmLoopMode());
        btnStop.setOnClickListener(v -> setTestMode());
        btnClearFailsafe.setOnClickListener(v -> confirmClearFailsafe());

        if (prefs.getTreeUri() != null) {
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

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException e) {
            LoopLog.get().w("Thiết bị không cho lưu quyền vĩnh viễn — dùng quyền phiên hiện tại.");
        }
        prefs.setTreeUri(uri);
        prefs.setMode(AppPreferences.MODE_TEST);
        LoopLog.get().i("Đã chọn thư mục SAF: " + uri);
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
        if (prefs.getTreeUri() == null) {
            Toast.makeText(this, "Hãy chọn thư mục DCIM trước", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.isFailsafe()) {
            Toast.makeText(this, "FAILSAFE đang bật. Hãy kiểm tra DCIM rồi xóa cờ failsafe.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Bật LOOP MODE?")
                .setMessage("LOOP MODE sẽ XÓA video 20 và ĐỔI TÊN 19→20 … 01→02, rồi đưa video mới vào 01.\n\n"
                        + "TEST MODE thì không đụng file.\n\nChỉ bật khi bạn chắc USB/DCIM đúng.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Bật LOOP MODE", (d, w) -> {
                    prefs.setMode(AppPreferences.MODE_LOOP);
                    LoopLog.get().i("LOOP MODE đã bật — sẽ xử lý tự động khi video mới ổn định.");
                    LoopMonitorService.start(this);
                    Toast.makeText(this, "LOOP MODE", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmClearFailsafe() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa cờ FAILSAFE?")
                .setMessage("Chỉ làm sau khi bạn đã kiểm tra file trong DCIM. App sẽ không tự hoàn tất thao tác dở dang.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đã kiểm tra", (d, w) -> {
                    Uri tree = prefs.getTreeUri();
                    if (tree != null) {
                        DcimStore store = new DcimStore(this);
                        DcimStore.Folder folder = store.open(tree);
                        if (folder != null) {
                            store.deleteTxn(folder);
                        }
                    }
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
        txtStatus.setText("Status: " + (s.connected ? "USB/DCIM: Connected" : "USB/DCIM: Not connected")
                + (s.statusDetail.isEmpty() ? "" : "\n" + s.statusDetail));
        txtCount.setText("Video count: " + s.videoCount + " / " + LoopPlanner.MAX_VIDEOS);
        txtMode.setText("Mode: " + (AppPreferences.MODE_LOOP.equals(mode) ? "LOOP MODE" : "TEST MODE"));
        txtNewest.setText("Video mới nhất: " + s.newest);
        txtOldest.setText("Video cũ nhất: " + s.oldest);

        if (s.planned.isEmpty()) {
            txtPlan.setText("Thao tác dự kiến: (chưa có video mới đã ổn định)");
        } else {
            StringBuilder sb = new StringBuilder("Thao tác dự kiến:\n");
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
