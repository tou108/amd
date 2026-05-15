package exelix11.sysdvr;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.libsdl.app.SDLActivity;

public class sysdvrActivity extends SDLActivity
{
    public static sysdvrActivity instance;

    private static final int PERM_REQUEST_BT = 200;

    private AutomationOverlayView overlayView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log("SysDVRActivity onCreate()");
        super.onCreate(savedInstanceState);
        instance = this;
        CheckPackageName();
        requestBluetoothPermissions();
        // SDL完全初期化後にオーバーレイを追加するため2秒遅延
        new Handler(Looper.getMainLooper()).postDelayed(this::initAutomationOverlay, 2000);
        Log("SysDVRActivity created (with AutomationManager)");
    }

    @Override
    protected void onDestroy() {
        AutomationManager mgr = AutomationManager.getInstance();
        if (mgr.isRunning()) mgr.stop();
        super.onDestroy();
    }

    private void initAutomationOverlay() {
        try {
            FrameLayout rootView = (FrameLayout) getWindow().getDecorView();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            overlayView = new AutomationOverlayView(this);
            rootView.addView(overlayView, params);
            overlayView.bringToFront();  // SDL画面の最前面に表示
            overlayView.setZ(9999f);
            Log("AutomationOverlay 追加完了");
        } catch (Exception e) {
            Log("AutomationOverlay 追加失敗: " + e.getMessage());
        }
    }

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
            if (!allGranted) {
                runOnUiThread(() ->
                    Toast.makeText(this,
                        "Bluetooth パーミッションが必要です (Arduino 接続のため)",
                        Toast.LENGTH_LONG).show()
                );
            }
        }
    }

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
