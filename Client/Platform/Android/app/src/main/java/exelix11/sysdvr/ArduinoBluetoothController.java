package exelix11.sysdvr;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Arduino Leonardo (HC-05/HC-06 Bluetooth) との通信を管理するクラス
 *
 * 接続方法:
 *  1. HC-05モジュールをAndroidとペアリング (PIN: 1234 または 0000)
 *  2. ArduinoController.connect("HC-05") を呼び出す
 *  3. sendButton() でボタンコマンドを送信
 */
public class ArduinoBluetoothController {

    private static final String TAG = "ArduinoBT";
    // Bluetooth SPP (Serial Port Profile) UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // 接続状態
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private OutputStream outputStream;
    private volatile State state = State.DISCONNECTED;
    private String connectedDeviceName = null;

    // コマンドキュー (送信スレッドが処理)
    private final BlockingQueue<Byte> commandQueue = new ArrayBlockingQueue<>(64);
    private Thread sendThread;

    // コールバック
    public interface ConnectionCallback {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
    }

    private ConnectionCallback callback;

    public ArduinoBluetoothController(Context context) {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setCallback(ConnectionCallback cb) {
        this.callback = cb;
    }

    public State getState() { return state; }
    public boolean isConnected() { return state == State.CONNECTED; }
    public String getConnectedDeviceName() { return connectedDeviceName; }

    /**
     * デバイス名でBluetoothデバイスに接続
     * ペアリング済みデバイスから "HC-05" または "HC-06" を検索
     * @param targetName デバイス名の部分一致 (例: "HC-05")
     */
    public void connect(final String targetName) {
        if (btAdapter == null) {
            notifyError("Bluetoothが利用できません");
            return;
        }
        if (!btAdapter.isEnabled()) {
            notifyError("Bluetoothが無効です。設定から有効にしてください");
            return;
        }

        state = State.CONNECTING;

        new Thread(() -> {
            try {
                BluetoothDevice targetDevice = findPairedDevice(targetName);
                if (targetDevice == null) {
                    notifyError("デバイス '" + targetName + "' が見つかりません。先にペアリングしてください");
                    return;
                }

                Log.i(TAG, "接続中: " + targetDevice.getName() + " [" + targetDevice.getAddress() + "]");
                btSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                btAdapter.cancelDiscovery();
                btSocket.connect();

                outputStream = btSocket.getOutputStream();
                connectedDeviceName = targetDevice.getName();
                state = State.CONNECTED;

                startSendThread();

                Log.i(TAG, "接続成功: " + connectedDeviceName);
                if (callback != null) {
                    callback.onConnected(connectedDeviceName);
                }
            } catch (IOException e) {
                Log.e(TAG, "接続失敗: " + e.getMessage());
                state = State.ERROR;
                cleanupSocket();
                notifyError("接続失敗: " + e.getMessage());
            }
        }, "ArduinoBT-Connect").start();
    }

    /**
     * MACアドレスで直接接続
     * @param macAddress "XX:XX:XX:XX:XX:XX" 形式
     */
    public void connectByAddress(final String macAddress) {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            notifyError("Bluetoothが利用できません");
            return;
        }

        state = State.CONNECTING;
        new Thread(() -> {
            try {
                BluetoothDevice device = btAdapter.getRemoteDevice(macAddress);
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                btAdapter.cancelDiscovery();
                btSocket.connect();

                outputStream = btSocket.getOutputStream();
                connectedDeviceName = device.getName() != null ? device.getName() : macAddress;
                state = State.CONNECTED;
                startSendThread();

                if (callback != null) callback.onConnected(connectedDeviceName);
            } catch (IOException e) {
                state = State.ERROR;
                cleanupSocket();
                notifyError("接続失敗: " + e.getMessage());
            }
        }, "ArduinoBT-ConnectAddr").start();
    }

    /**
     * ボタンコマンドをキューに追加 (スレッドセーフ)
     */
    public boolean sendButton(SwitchButton button) {
        if (state != State.CONNECTED) return false;
        return commandQueue.offer(button.toByte());
    }

    /**
     * 生バイトコマンドを送信
     */
    public boolean sendRaw(byte cmd) {
        if (state != State.CONNECTED) return false;
        return commandQueue.offer(cmd);
    }

    /**
     * 全ボタンリリースコマンドを即時送信
     */
    public void releaseAll() {
        sendButton(SwitchButton.FULL_RELEASE);
    }

    /**
     * 切断
     */
    public void disconnect() {
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        cleanupSocket();
        state = State.DISCONNECTED;
        if (callback != null) callback.onDisconnected();
    }

    /**
     * ペアリング済みデバイス一覧を返す
     */
    public Set<BluetoothDevice> getPairedDevices() {
        if (btAdapter == null) return null;
        return btAdapter.getBondedDevices();
    }

    // ---- 内部実装 ----

    private BluetoothDevice findPairedDevice(String nameHint) {
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        if (paired == null) return null;
        for (BluetoothDevice d : paired) {
            if (d.getName() != null && d.getName().contains(nameHint)) {
                return d;
            }
        }
        return null;
    }

    private void startSendThread() {
        sendThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && state == State.CONNECTED) {
                try {
                    byte cmd = commandQueue.take();
                    if (outputStream != null) {
                        outputStream.write(cmd);
                        outputStream.flush();
                        Log.d(TAG, "送信: 0x" + String.format("%02X", cmd));
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "送信エラー: " + e.getMessage());
                    state = State.ERROR;
                    cleanupSocket();
                    notifyError("通信エラー: 切断されました");
                    break;
                }
            }
        }, "ArduinoBT-Send");
        sendThread.setDaemon(true);
        sendThread.start();
    }

    private void cleanupSocket() {
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}
        btSocket = null;
        outputStream = null;
    }

    private void notifyError(String msg) {
        state = State.ERROR;
        Log.e(TAG, msg);
        if (callback != null) {
            // UIスレッドで通知
            sysdvrActivity.instance.runOnUiThread(() -> callback.onError(msg));
        }
    }
}
