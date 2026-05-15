# SysDVR 自動操作機能 セットアップガイド

## 概要

SysDVR の画面キャプチャ映像を**リアルタイム解析**し、
**Arduino Leonardo** (HC-05 Bluetooth経由) で Nintendo Switch を自動操作する機能を追加したビルドです。

---

## ハードウェア構成

```
[Nintendo Switch]
      │ USB (HIDコントローラとして認識)
      ▼
[Arduino Leonardo]
      │ ハードウェアシリアル (TX1/RX1)
      ▼
[HC-05 Bluetooth モジュール]
      │ Bluetooth SPP
      ▼
[Android スマートフォン]
  ├─ SysDVR APK (画面認識 + 操作指示)
  └─ WiFi ──────────────────────────────── [Nintendo Switch] (SysDVR映像受信)
```

### 配線図 (Arduino Leonardo + HC-05)

| Arduino Leonardo | HC-05 |
|-----------------|-------|
| 5V              | VCC   |
| GND             | GND   |
| TX1 (ピン1)     | RXD ※ 3.3V変換推奨 |
| RX1 (ピン0)     | TXD   |
| 13番ピン        | LED (動作確認用) |

**注意**: HC-05のRXDピンは3.3V仕様のため、
Arduino(5V) → HC-05(3.3V) の間に **電圧分圧回路** または **レベル変換IC** を入れることを推奨します。

---

## セットアップ手順

### Step 1: Arduino のセットアップ

1. **ライブラリをインストール**
   - Arduino IDE を開く
   - スケッチ > ライブラリをインクルード > ライブラリを管理
   - "SwitchControlLibrary" を検索してインストール

2. **スケッチを書き込む**
   ```
   arduino/SwitchAutoController/SwitchAutoController.ino
   ```
   を Arduino IDE で開き、Leonardo ボードを選択して書き込む

3. **動作確認**
   - Arduino を Switch に USB 接続
   - LEDが3回点滅すれば準備完了
   - Switch でコントローラとして認識されることを確認

4. **HC-05 のボーレート設定** (デフォルト9600bpsの場合不要)
   ```
   AT+BAUD4   (9600bps に設定)
   ```

### Step 2: Android APK のビルド

#### 前提条件
- Android Studio (最新版)
- Android NDK r21 以降
- Java 11 以降

#### ビルド手順

```bash
cd Client/Platform/Android

# NDK パスを設定
export ANDROID_NDK=/path/to/ndk

# 依存ライブラリを準備 (既存SysDVRのビルド手順に従う)
# - SDL2 の AAR を app/libs/ に配置
# - SysDVR-Client ネイティブライブラリを配置

# APK ビルド
./gradlew assembleDebug
# または
./gradlew assembleRelease
```

生成物: `app/build/outputs/apk/debug/app-debug.apk`

### Step 3: Android アプリの設定

1. APK をインストール
2. **Bluetooth 設定** でスマートフォンと HC-05 をペアリング
   - PIN: `1234` または `0000`
3. SysDVR アプリを起動
4. Switch の WiFi ストリームに接続
5. 画面右上の **🎮 ボタン** をタップしてコントロールパネルを開く
6. **🔵 Arduino接続** をタップして HC-05 を選択
7. **▶ 自動操作開始** で有効化

---

## 新規追加ファイル一覧

| ファイル | 説明 |
|---------|------|
| `app/src/main/java/.../SwitchButton.java` | ボタンコマンド定義 |
| `app/src/main/java/.../ArduinoBluetoothController.java` | Bluetooth通信 |
| `app/src/main/java/.../ScreenAnalyzer.java` | 画面認識エンジン |
| `app/src/main/java/.../AutomationManager.java` | 自動操作統合管理 |
| `app/src/main/java/.../AutomationOverlayView.java` | オーバーレイUI |
| `app/jni/src/auto_capture.c` | SDL2フレームキャプチャ (JNI) |
| `arduino/SwitchAutoController/SwitchAutoController.ino` | Arduino スケッチ |

---

## ゲーム固有の自動操作スクリプトの書き方

`AutomationManager.AutomationScript` を継承して実装します:

```java
import exelix11.sysdvr.AutomationManager;
import exelix11.sysdvr.ScreenAnalyzer;
import exelix11.sysdvr.SwitchButton;
import android.graphics.Bitmap;
import android.graphics.Color;

public class MyGameScript extends AutomationManager.AutomationScript {

    public MyGameScript(AutomationManager mgr) {
        super(mgr);
    }

    @Override
    public void onStart() {
        // カスタム色範囲ルールを登録
        // 例: 画面左上 (0,0)-(200,50) がオレンジ色 → バトル開始と判定
        manager.getScreenAnalyzer().addColorRule(new ScreenAnalyzer.ColorRegion(
            0, 0, 200, 50,
            Color.rgb(255, 140, 0),  // ターゲット色 (オレンジ)
            30,                       // 許容誤差
            ScreenAnalyzer.DetectedState.BATTLE_START,
            0.5f                      // 50%以上一致で検出
        ));
    }

    @Override
    public void onFrameAnalyzed(ScreenAnalyzer.AnalysisResult result) {
        switch (result.state) {
            case BATTLE_START:
                // バトル開始 → Aボタン
                manager.pressButton(SwitchButton.A, "バトル開始");
                break;

            case BATTLE_END_WIN:
                // 勝利 → Aボタン連打
                SwitchButton[] seq = {SwitchButton.A, SwitchButton.A, SwitchButton.A};
                manager.executeSequence(seq, 500);
                break;

            case DIALOG_YES_NO:
                // はい/いいえ → Aで確認
                manager.pressButton(SwitchButton.A, "ダイアログ確認");
                break;
        }
    }
}
```

登録方法:
```java
AutomationManager.getInstance().setScript(new MyGameScript(AutomationManager.getInstance()));
```

---

## テンプレートマッチングの使い方

特定の画像が画面内に表示されたことを検出する場合:

```java
// ゲーム内の特定UI画像 (事前にキャプチャしてassets等に保存)
Bitmap template = BitmapFactory.decodeResource(getResources(), R.drawable.my_template);

ScreenAnalyzer.TemplateInfo info = new ScreenAnalyzer.TemplateInfo(
    template,
    400, 300,  // 探索開始位置 (x, y)
    480, 180,  // 探索範囲 (width, height)
    ScreenAnalyzer.DetectedState.CUSTOM_1,
    0.85f,     // 85%以上一致で検出
    "カスタムUI"
);

AutomationManager.getInstance().getScreenAnalyzer().registerTemplate(info);
```

---

## コマンドプロトコル (Arduino ↔ Android)

Bluetooth SPP でシリアル通信 (9600bps, 1バイトコマンド):

| バイト値 | 操作 |
|---------|------|
| 0x00    | 全リリース |
| 0x01    | A ボタン |
| 0x02    | B ボタン |
| 0x03    | X ボタン |
| 0x04    | Y ボタン |
| 0x05    | L ボタン |
| 0x06    | R ボタン |
| 0x07    | ZL ボタン |
| 0x08    | ZR ボタン |
| 0x09    | + (Plus) |
| 0x0A    | - (Minus) |
| 0x0D    | 上 |
| 0x0E    | 下 |
| 0x0F    | 左 |
| 0x10    | 右 |
| 0xF0-F3 | Lスティック 上/下/左/右 |
| 0xF4-F7 | Rスティック 上/下/左/右 |
| 0xFF    | 全リリース + スティック中立 |

---

## トラブルシューティング

**Arduino が Switch に認識されない**
- Switch 本体の設定 > コントローラとセンサー > USB コントローラとの有線通信 が OFF になっていないか確認
- Arduino のリセットボタンを押してから再接続

**Bluetooth に繋がらない**
- HC-05 の電源LEDが点滅 (未接続) → 接続中は LED が遅い点滅になる
- Android の Bluetooth を OFF/ON して再試行
- HC-05 のペアリングをいったん削除して再ペアリング

**画面認識の精度が低い**
- `ScreenAnalyzer.setFrameSkipInterval(1)` で全フレーム解析に変更
- 色範囲の tolerance を広げる
- テンプレートマッチングのしきい値を下げる (0.7 程度)

**「Arduino未接続」と表示される**
- コントロールパネルの 🔵 Arduino接続 から接続済みか確認
- 接続後に ▶ 自動操作開始 を押す
