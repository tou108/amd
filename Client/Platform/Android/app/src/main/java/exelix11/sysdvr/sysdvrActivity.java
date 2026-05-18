package exelix11.sysdvr;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.libsdl.app.SDLActivity;

/**
 * SysDVR メインActivity
 * 画面認識 & Arduino Leonardo 自動操作機能を統合
 */
public class sysdvrActivity extends SDLActivity
{
    public static sysdvrActivity instance;

    private static final int PERM_REQUEST_BT = 200;

    // 自動操作オーバーレイ (SDL画面の上にFloating UI)
    private AutomationOverlayView overlayView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log("SysDVRActivity onCreate()");
        super.onCreate(savedInstanceState);
        instance = this;
        CheckPackageName();
        requestBluetoothPermissions();
        initAutomationOverlay();
        Log("SysDVRActivity created (with AutomationManager)");
    }

    @Override
    protected void onDestroy() {
        // 自動操作を安全に停止
        AutomationManager mgr = AutomationManager.getInstance();
        if (mgr.isRunning()) mgr.stop();
        super.onDestroy();
    }

    // ---- 自動操作オーバーレイ初期化 ----

    private void initAutomationOverlay() {
        runOnUiThread(() -> {
            try {
                // SDL の SurfaceView の上にオーバーレイを配置
                ViewGroup rootView = (ViewGroup) getWindow().getDecorView().getRootView();
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                overlayView = new AutomationOverlayView(this);
                rootView.addView(overlayView, params);
                Log("AutomationOverlay 追加完了");
            } catch (Exception e) {
                Log("AutomationOverlay 追加失敗: " + e.getMessage());
            }
        });
    }

    // ---- Bluetooth パーミッション ----

    private void requestBluetoothPermissions() {
        String[] perms;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            perms = new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        boolean needRequest = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERM_REQUEST_BT) {
            boolean allGranted = true;
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                Log("Bluetooth パーミッション許可済み");
            } else {
                runOnUiThread(() ->
                    Toast.makeText(this,
                        "Bluetooth パーミッションが必要です (Arduino 接続のため)",
                        Toast.LENGTH_LONG).show()
                );
            }
        }
    }

    // ---- パッケージ名チェック (元のまま) ----

    static boolean checkOnce = true;
    void CheckPackageName() {
        if (!checkOnce) return;
        checkOnce = false;

        if (getPackageName().equals("exelix" + ((Integer)11).toString() + getString(R.string.hello_txt).charAt(3) + "sysdvr"))
            return;

        AlertDialog.Builder dlgAlert = new AlertDialog.Builder(this);
        dlgAlert.setMessage("You're using a SyDVR-Client version that was not downloaded from the official GitHub repository. This is at your own risk.");
        dlgAlert.setTitle("Warning");
        dlgAlert.setPositiveButton("Dismiss", (dialog, id) -> dialog.cancel());
        dlgAlert.setNeutralButton("Open GitHub page", (dialog, which) ->
                SystemHelper.OpenURL("https://github.com/exelix11/SysDVR"));
        dlgAlert.setCancelable(true);
        dlgAlert.create().show();
    }

    public static void Log(String message) {
        Log.i("SysDVRJava", message);
    }
}
