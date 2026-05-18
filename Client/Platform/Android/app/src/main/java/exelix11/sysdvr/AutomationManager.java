package exelix11.sysdvr;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 画面認識 + Arduino制御 の統合マネージャ
 *
 * 使い方:
 *   AutomationManager mgr = AutomationManager.getInstance();
 *   mgr.setArduinoDevice("HC-05");           // Bluetoothデバイス名
 *   mgr.setScript(new MyGameScript(mgr));    // ゲーム固有スクリプト
 *   mgr.start();                             // 自動操作開始
 *   mgr.stop();                              // 停止
 *
 * フレームの供給:
 *   JNIネイティブ側から onNewFrame() が定期呼び出しされる。
 *   または手動で pushFrame(bitmap) を呼び出す。
 */
public class AutomationManager {

    private static final String TAG = "AutomationMgr";
    private static AutomationManager instance;

    public static AutomationManager getInstance() {
        if (instance == null) instance = new AutomationManager();
        return instance;
    }

    // コンポーネント
    private final ScreenAnalyzer screenAnalyzer = new ScreenAnalyzer();
    private ArduinoBluetoothController arduinoController;
    private AutomationScript currentScript;

    // 状態
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused  = new AtomicBoolean(false);

    // 解析スレッド
    private HandlerThread analysisThread;
    private Handler analysisHandler;

    // 統計
    private long totalFramesReceived = 0;
    private long totalFramesAnalyzed = 0;
    private long lastActionTime      = 0;

    // コールバック
    public interface StatusListener {
        void onStateChanged(String status);
        void onActionTaken(SwitchButton button, String reason);
        void onArduinoConnected(String deviceName);
        void onArduinoDisconnected();
        void onError(String message);
    }
    private StatusListener statusListener;

    private AutomationManager() {
        arduinoController = null;
    }

    // ===== 設定 API =====

    public void setStatusListener(StatusListener l) { this.statusListener = l; }
    public ScreenAnalyzer getScreenAnalyzer() { return screenAnalyzer; }
    public ArduinoBluetoothController getArduinoController() { return arduinoController; }

    /**
     * Bluetoothデバイス名でArduinoを設定
     * @param deviceName ペアリング済みデバイス名 (例: "HC-05")
     */
    public void setArduinoDevice(String deviceName) {
        Context ctx = sysdvrActivity.instance;
        arduinoController = new ArduinoBluetoothController(ctx);
        arduinoController.setCallback(new ArduinoBluetoothController.ConnectionCallback() {
            @Override public void onConnected(String name) {
                notify("Arduino接続成功: " + name);
                if (statusListener != null) statusListener.onArduinoConnected(name);
            }
            @Override public void onDisconnected() {
                notify("Arduino切断");
                if (statusListener != null) statusListener.onArduinoDisconnected();
            }
            @Override public void onError(String message) {
                notify("Arduinoエラー: " + message);
                if (statusListener != null) statusListener.onError(message);
            }
        });
        arduinoController.connect(deviceName);
    }

    /**
     * ゲーム固有の自動操作スクリプトを設定
     */
    public void setScript(AutomationScript script) {
        this.currentScript = script;
    }

    // ===== 操作 API =====

    public void start() {
        if (isRunning.getAndSet(true)) return;
        isPaused.set(false);

        // 解析スレッド起動
        analysisThread = new HandlerThread("ScreenAnalysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());

        if (currentScript != null) currentScript.onStart();
        notify("自動操作開始");
        Log.i(TAG, "AutomationManager started");
    }

    public void stop() {
        if (!isRunning.getAndSet(false)) return;

        if (currentScript != null) currentScript.onStop();

        // 全ボタンリリース
        if (arduinoController != null && arduinoController.isConnected()) {
            arduinoController.releaseAll();
        }

        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
            analysisHandler = null;
        }
        notify("自動操作停止");
    }

    public void pause()  { isPaused.set(true);  notify("一時停止"); }
    public void resume() { isPaused.set(false); notify("再開"); }
    public boolean isRunning() { return isRunning.get(); }
    public boolean isPaused()  { return isPaused.get(); }

    // ===== フレーム受信 (JNIから呼び出し) =====

    /**
     * JNIネイティブから呼び出されるフレームコールバック
     * @param pixels ARGB_8888 ピクセル配列
     * @param width  フレーム幅
     * @param height フレーム高さ
     */
    public static void onNewFrameJni(int[] pixels, int width, int height) {
        if (instance == null) return;
        instance.pushFramePixels(pixels, width, height);
    }

    /**
     * Bitmapフレームを受け取り解析キューに投入
     */
    public void pushFrame(final Bitmap bmp) {
        if (!isRunning.get() || isPaused.get()) return;
        totalFramesReceived++;

        if (analysisHandler == null) return;
        analysisHandler.post(() -> processFrame(bmp));
    }

    public void pushFramePixels(final int[] pixels, final int width, final int height) {
        if (!isRunning.get() || isPaused.get()) return;
        totalFramesReceived++;

        if (analysisHandler == null) return;
        analysisHandler.post(() -> {
            ScreenAnalyzer.AnalysisResult result = screenAnalyzer.analyze(pixels, width, height);
            dispatchResult(result);
        });
    }

    // ===== ボタン送信ヘルパー (スクリプトから呼び出し) =====

    public boolean pressButton(SwitchButton btn) {
        return pressButton(btn, "手動送信");
    }

    public boolean pressButton(SwitchButton btn, String reason) {
        if (arduinoController == null || !arduinoController.isConnected()) {
            Log.w(TAG, "Arduino未接続: " + btn.label);
            return false;
        }
        boolean sent = arduinoController.sendButton(btn);
        lastActionTime = System.currentTimeMillis();
        Log.d(TAG, "ボタン送信: " + btn.label + " [" + reason + "]");
        if (sent && statusListener != null) {
            sysdvrActivity.instance.runOnUiThread(() ->
                    statusListener.onActionTaken(btn, reason));
        }
        return sent;
    }

    /**
     * ボタンを指定時間後に押す (遅延実行)
     */
    public void pressButtonDelayed(SwitchButton btn, long delayMs) {
        if (analysisHandler != null) {
            analysisHandler.postDelayed(() -> pressButton(btn, "遅延実行"), delayMs);
        }
    }

    /**
     * ボタンシーケンスを実行
     * @param sequence 送信するボタンの配列
     * @param intervalMs ボタン間隔 (ms)
     */
    public void executeSequence(SwitchButton[] sequence, long intervalMs) {
        if (analysisHandler == null) return;
        for (int i = 0; i < sequence.length; i++) {
            final SwitchButton btn = sequence[i];
            analysisHandler.postDelayed(() -> pressButton(btn, "シーケンス実行"), intervalMs * i);
        }
    }

    // ===== ペアリング済みデバイス一覧 =====
    public Set<BluetoothDevice> getPairedArduinoDevices() {
        Context ctx = sysdvrActivity.instance;
        ArduinoBluetoothController tmp = new ArduinoBluetoothController(ctx);
        return tmp.getPairedDevices();
    }

    // ===== 統計 =====
    public long getTotalFramesReceived()  { return totalFramesReceived; }
    public long getTotalFramesAnalyzed()  { return totalFramesAnalyzed; }
    public long getLastActionTime()       { return lastActionTime; }

    // ===== 内部 =====

    private void processFrame(Bitmap bmp) {
        ScreenAnalyzer.AnalysisResult result = screenAnalyzer.analyze(bmp);
        dispatchResult(result);
    }

    private void dispatchResult(ScreenAnalyzer.AnalysisResult result) {
        totalFramesAnalyzed++;
        if (result == null) return;

        Log.d(TAG, "検出状態: " + result.state + " (信頼度: " + String.format("%.2f", result.confidence) + ")");

        if (currentScript != null && isRunning.get() && !isPaused.get()) {
            currentScript.onFrameAnalyzed(result);
        }
    }

    private void notify(String msg) {
        Log.i(TAG, msg);
        if (statusListener != null) {
            sysdvrActivity.instance.runOnUiThread(() -> statusListener.onStateChanged(msg));
        }
    }

    // ===== スクリプトインターフェース =====

    /**
     * ゲーム固有の自動操作ロジックを実装するための基底インターフェース
     *
     * 実装例:
     * <pre>
     * public class PokeAutoScript extends AutomationScript {
     *     @Override
     *     public void onFrameAnalyzed(ScreenAnalyzer.AnalysisResult result) {
     *         if (result.state == ScreenAnalyzer.DetectedState.DIALOG_YES_NO) {
     *             manager.pressButton(SwitchButton.A, "ダイアログ確認");
     *         }
     *     }
     * }
     * </pre>
     */
    public static abstract class AutomationScript {
        protected AutomationManager manager;

        public AutomationScript(AutomationManager mgr) {
            this.manager = mgr;
        }

        /** 自動操作開始時に呼び出される */
        public void onStart() {}

        /** 自動操作停止時に呼び出される */
        public void onStop() {}

        /**
         * フレーム解析完了後に呼び出される (バックグラウンドスレッド)
         * @param result 最新の解析結果
         */
        public abstract void onFrameAnalyzed(ScreenAnalyzer.AnalysisResult result);
    }

    /**
     * サンプル汎用スクリプト: ダイアログ自動確認 + ホーム遷移対応
     */
    public static class DefaultScript extends AutomationScript {
        private long lastActionMs = 0;
        private static final long ACTION_INTERVAL = 2000; // 2秒クールダウン

        public DefaultScript(AutomationManager mgr) { super(mgr); }

        @Override
        public void onFrameAnalyzed(ScreenAnalyzer.AnalysisResult result) {
            long now = System.currentTimeMillis();
            if (now - lastActionMs < ACTION_INTERVAL) return;

            switch (result.state) {
                case DIALOG_YES_NO:
                    manager.pressButton(SwitchButton.A, "ダイアログ: Aボタン");
                    lastActionMs = now;
                    break;
                case LOADING_SCREEN:
                    // ロード中は何もしない
                    break;
                case HOME_MENU:
                    // ホームメニューへの遷移は手動操作に委ねる
                    Log.i(TAG, "ホームメニュー検出 - 自動操作を一時停止");
                    manager.pause();
                    break;
                default:
                    break;
            }
        }
    }
}
