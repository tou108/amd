package exelix11.sysdvr;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 画面認識＆自動操作コントロールのオーバーレイUI
 * SDL画面の上にフローティング表示
 */
public class AutomationOverlayView extends FrameLayout {

    private TextView statusText;
    private TextView analysisText;
    private Button toggleButton;
    private Button connectButton;
    private LinearLayout panel;
    private boolean panelVisible = false;

    // ドラッグ用
    private float dragStartX, dragStartY;
    private float viewStartX, viewStartY;

    private final AutomationManager mgr = AutomationManager.getInstance();

    public AutomationOverlayView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public AutomationOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    private void init(Context ctx) {
        setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        ));

        // パネル本体
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(220, 20, 20, 20));
        panel.setPadding(20, 20, 20, 20);
        panel.setVisibility(GONE);

        // タイトル
        TextView title = new TextView(ctx);
        title.setText("🎮 自動操作");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        panel.addView(title);

        // 状態テキスト
        statusText = new TextView(ctx);
        statusText.setText("状態: 停止中");
        statusText.setTextColor(Color.LTGRAY);
        statusText.setTextSize(11);
        panel.addView(statusText);

        // 解析結果テキスト
        analysisText = new TextView(ctx);
        analysisText.setText("解析: -");
        analysisText.setTextColor(Color.CYAN);
        analysisText.setTextSize(10);
        panel.addView(analysisText);

        // ボタン: Bluetooth接続
        connectButton = makeButton(ctx, "🔵 Arduino接続", Color.rgb(30, 100, 200));
        connectButton.setOnClickListener(v -> showBluetoothDialog());
        panel.addView(connectButton);

        // ボタン: 開始/停止
        toggleButton = makeButton(ctx, "▶ 自動操作開始", Color.rgb(40, 160, 40));
        toggleButton.setOnClickListener(v -> toggleAutomation());
        panel.addView(toggleButton);

        // ボタン: テスト (Aボタン送信)
        Button testBtn = makeButton(ctx, "テスト (Aボタン)", Color.rgb(160, 80, 20));
        testBtn.setOnClickListener(v -> {
            mgr.pressButton(SwitchButton.A, "テスト");
            Toast.makeText(ctx, "A ボタン送信", Toast.LENGTH_SHORT).show();
        });
        panel.addView(testBtn);

        addView(panel);

        // フローティングタブ (パネル表示切り替え)
        Button tab = new Button(ctx);
        tab.setText("🎮");
        tab.setBackgroundColor(Color.argb(200, 30, 30, 120));
        tab.setTextColor(Color.WHITE);
        tab.setLayoutParams(new FrameLayout.LayoutParams(100, 100, Gravity.TOP | Gravity.END));
        tab.setOnClickListener(v -> {
            panelVisible = !panelVisible;
            panel.setVisibility(panelVisible ? VISIBLE : GONE);
        });
        addView(tab);

        // ドラッグ移動
        setOnTouchListener((v, event) -> handleDrag(event));

        // AutomationManager コールバック登録
        mgr.setStatusListener(new AutomationManager.StatusListener() {
            @Override public void onStateChanged(String status) {
                post(() -> {
                    statusText.setText("状態: " + status);
                    updateToggleButton();
                });
            }
            @Override public void onActionTaken(SwitchButton button, String reason) {
                post(() -> analysisText.setText("操作: " + button.label + " [" + reason + "]"));
            }
            @Override public void onArduinoConnected(String deviceName) {
                post(() -> {
                    connectButton.setText("✅ " + deviceName);
                    connectButton.setBackgroundColor(Color.rgb(20, 160, 20));
                    Toast.makeText(ctx, "Arduino 接続: " + deviceName, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onArduinoDisconnected() {
                post(() -> {
                    connectButton.setText("🔵 Arduino接続");
                    connectButton.setBackgroundColor(Color.rgb(30, 100, 200));
                });
            }
            @Override public void onError(String message) {
                post(() -> Toast.makeText(ctx, "エラー: " + message, Toast.LENGTH_LONG).show());
            }
        });

        // デフォルトスクリプト設定
        mgr.setScript(new AutomationManager.DefaultScript(mgr));
    }

    private Button makeButton(Context ctx, String text, int bgColor) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setBackgroundColor(bgColor);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 80);
        lp.setMargins(0, 6, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void toggleAutomation() {
        if (mgr.isRunning()) {
            mgr.stop();
        } else {
            mgr.start();
        }
        updateToggleButton();
    }

    private void updateToggleButton() {
        if (mgr.isRunning()) {
            toggleButton.setText("⏹ 自動操作停止");
            toggleButton.setBackgroundColor(Color.rgb(180, 40, 40));
        } else {
            toggleButton.setText("▶ 自動操作開始");
            toggleButton.setBackgroundColor(Color.rgb(40, 160, 40));
        }
    }

    private void showBluetoothDialog() {
        Context ctx = getContext();

        // ペアリング済みデバイス一覧を取得
        Set<BluetoothDevice> paired = mgr.getPairedArduinoDevices();
        if (paired == null || paired.isEmpty()) {
            Toast.makeText(ctx, "ペアリング済みBluetoothデバイスがありません\n先にHC-05とペアリングしてください", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> names = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        for (BluetoothDevice d : paired) {
            String name = d.getName() != null ? d.getName() : "不明";
            names.add(name + "\n" + d.getAddress());
            addresses.add(d.getAddress());
        }

        new AlertDialog.Builder(ctx)
            .setTitle("Arduino接続先を選択")
            .setItems(names.toArray(new String[0]), (dialog, which) -> {
                mgr.setArduinoDevice(names.get(which).split("\n")[0]);
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    private boolean handleDrag(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = event.getRawX();
                dragStartY = event.getRawY();
                viewStartX = getX();
                viewStartY = getY();
                return false; // 子ビューにもイベントを渡す
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - dragStartX;
                float dy = event.getRawY() - dragStartY;
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                    setX(viewStartX + dx);
                    setY(viewStartY + dy);
                    return true;
                }
                break;
        }
        return false;
    }
}
