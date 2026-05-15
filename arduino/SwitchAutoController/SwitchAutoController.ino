/**
 * SwitchAutoController.ino
 * Arduino Leonardo用 Nintendo Switch 自動操作スケッチ
 *
 * 接続構成:
 *   Arduino Leonardo USB → Nintendo Switch (HIDコントローラとして認識)
 *   Arduino Leonardo ハードウェアシリアル (TX1/RX1) → HC-05/HC-06 Bluetooth モジュール
 *   HC-05 Bluetooth ← Android アプリ (SysDVR 自動操作)
 *
 * HC-05 配線:
 *   HC-05 VCC  → Arduino 5V
 *   HC-05 GND  → Arduino GND
 *   HC-05 TXD  → Arduino RX1 (ピン0)
 *   HC-05 RXD  → Arduino TX1 (ピン1) ※3.3V変換推奨
 *
 * ライブラリ要件:
 *   NintendoSwitchControlLibrary
 *   https://github.com/celclow/SwitchControlLibrary
 *   Arduino IDE > ライブラリマネージャ > 「SwitchControlLibrary」で検索
 *
 * コマンドプロトコル (1バイト):
 *   0x00 = 全リリース
 *   0x01 = A
 *   0x02 = B
 *   0x03 = X
 *   0x04 = Y
 *   0x05 = L
 *   0x06 = R
 *   0x07 = ZL
 *   0x08 = ZR
 *   0x09 = Plus
 *   0x0A = Minus
 *   0x0B = Home
 *   0x0C = Capture
 *   0x0D = 上
 *   0x0E = 下
 *   0x0F = 左
 *   0x10 = 右
 *   0x11 = Lスティック押込
 *   0x12 = Rスティック押込
 *   0xF0 = Lスティック 上
 *   0xF1 = Lスティック 下
 *   0xF2 = Lスティック 左
 *   0xF3 = Lスティック 右
 *   0xF4 = Rスティック 上
 *   0xF5 = Rスティック 下
 *   0xF6 = Rスティック 左
 *   0xF7 = Rスティック 右
 *   0xFE = スティック中立
 *   0xFF = 全リリース + スティック中立
 */

#include <SwitchControlLibrary.h>

// ----- 設定 -----
#define BT_SERIAL      Serial1       // HC-05接続シリアル
#define BT_BAUD        9600          // HC-05デフォルトボーレート
#define PRESS_DURATION 100           // ボタン押下持続時間 (ms)
#define HOLD_PREFIX    0xE0          // 長押しフラグ (未使用予約)
#define DEBUG_SERIAL   Serial        // デバッグ用USB Serial

#define LED_PIN        13            // 動作確認LED

// ボタン状態
bool isHolding = false;
unsigned long holdStart = 0;
uint8_t lastCmd = 0xFF;

// --------- セットアップ ---------
void setup() {
  DEBUG_SERIAL.begin(115200);
  BT_SERIAL.begin(BT_BAUD);

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Switchに認識されるまで待機
  delay(3000);
  releaseAll();

  DEBUG_SERIAL.println("[SwitchAutoController] Ready");
  blinkLed(3);
}

// --------- メインループ ---------
void loop() {
  if (BT_SERIAL.available() > 0) {
    uint8_t cmd = (uint8_t)BT_SERIAL.read();
    handleCommand(cmd);
  }
}

// --------- コマンドハンドラ ---------
void handleCommand(uint8_t cmd) {
  DEBUG_SERIAL.print("[CMD] 0x");
  DEBUG_SERIAL.println(cmd, HEX);

  // 全リリース
  if (cmd == 0xFF || cmd == 0x00) {
    releaseAll();
    return;
  }

  // スティック中立
  if (cmd == 0xFE) {
    SwitchControlLibrary().MoveHat(Hat::CENTER);
    SwitchControlLibrary().moveLeftStick(128, 128);
    SwitchControlLibrary().moveRightStick(128, 128);
    SwitchControlLibrary().sendReport();
    return;
  }

  // Lスティック操作
  if (cmd >= 0xF0 && cmd <= 0xF3) {
    uint8_t lx = 128, ly = 128;
    switch (cmd) {
      case 0xF0: ly = 0;   break; // 上
      case 0xF1: ly = 255; break; // 下
      case 0xF2: lx = 0;   break; // 左
      case 0xF3: lx = 255; break; // 右
    }
    SwitchControlLibrary().moveLeftStick(lx, ly);
    SwitchControlLibrary().sendReport();
    delay(PRESS_DURATION);
    SwitchControlLibrary().moveLeftStick(128, 128);
    SwitchControlLibrary().sendReport();
    return;
  }

  // Rスティック操作
  if (cmd >= 0xF4 && cmd <= 0xF7) {
    uint8_t rx = 128, ry = 128;
    switch (cmd) {
      case 0xF4: ry = 0;   break; // 上
      case 0xF5: ry = 255; break; // 下
      case 0xF6: rx = 0;   break; // 左
      case 0xF7: rx = 255; break; // 右
    }
    SwitchControlLibrary().moveRightStick(rx, ry);
    SwitchControlLibrary().sendReport();
    delay(PRESS_DURATION);
    SwitchControlLibrary().moveRightStick(128, 128);
    SwitchControlLibrary().sendReport();
    return;
  }

  // ボタン操作
  pressButton(cmd);
}

void pressButton(uint8_t cmd) {
  // ボタン押下
  switch (cmd) {
    case 0x01: SwitchControlLibrary().PressButtonA();       break;
    case 0x02: SwitchControlLibrary().PressButtonB();       break;
    case 0x03: SwitchControlLibrary().PressButtonX();       break;
    case 0x04: SwitchControlLibrary().PressButtonY();       break;
    case 0x05: SwitchControlLibrary().PressButtonL();       break;
    case 0x06: SwitchControlLibrary().PressButtonR();       break;
    case 0x07: SwitchControlLibrary().PressButtonZL();      break;
    case 0x08: SwitchControlLibrary().PressButtonZR();      break;
    case 0x09: SwitchControlLibrary().PressButtonPlus();    break;
    case 0x0A: SwitchControlLibrary().PressButtonMinus();   break;
    case 0x0B: SwitchControlLibrary().PressButtonHome();    break;
    case 0x0C: SwitchControlLibrary().PressButtonCapture(); break;
    case 0x0D: SwitchControlLibrary().MoveHat(Hat::TOP);    break;
    case 0x0E: SwitchControlLibrary().MoveHat(Hat::BTM);    break;
    case 0x0F: SwitchControlLibrary().MoveHat(Hat::LEFT);   break;
    case 0x10: SwitchControlLibrary().MoveHat(Hat::RIGHT);  break;
    case 0x11: SwitchControlLibrary().PressButtonLStick();  break;
    case 0x12: SwitchControlLibrary().PressButtonRStick();  break;
    default: return;
  }
  SwitchControlLibrary().sendReport();
  digitalWrite(LED_PIN, HIGH);
  delay(PRESS_DURATION);

  // ボタンリリース
  switch (cmd) {
    case 0x01: SwitchControlLibrary().ReleaseButtonA();       break;
    case 0x02: SwitchControlLibrary().ReleaseButtonB();       break;
    case 0x03: SwitchControlLibrary().ReleaseButtonX();       break;
    case 0x04: SwitchControlLibrary().ReleaseButtonY();       break;
    case 0x05: SwitchControlLibrary().ReleaseButtonL();       break;
    case 0x06: SwitchControlLibrary().ReleaseButtonR();       break;
    case 0x07: SwitchControlLibrary().ReleaseButtonZL();      break;
    case 0x08: SwitchControlLibrary().ReleaseButtonZR();      break;
    case 0x09: SwitchControlLibrary().ReleaseButtonPlus();    break;
    case 0x0A: SwitchControlLibrary().ReleaseButtonMinus();   break;
    case 0x0B: SwitchControlLibrary().ReleaseButtonHome();    break;
    case 0x0C: SwitchControlLibrary().ReleaseButtonCapture(); break;
    case 0x0D: case 0x0E: case 0x0F: case 0x10:
               SwitchControlLibrary().MoveHat(Hat::CENTER);  break;
    case 0x11: SwitchControlLibrary().ReleaseButtonLStick();  break;
    case 0x12: SwitchControlLibrary().ReleaseButtonRStick();  break;
  }
  SwitchControlLibrary().sendReport();
  digitalWrite(LED_PIN, LOW);
}

void releaseAll() {
  SwitchControlLibrary().ReleaseButtonA();
  SwitchControlLibrary().ReleaseButtonB();
  SwitchControlLibrary().ReleaseButtonX();
  SwitchControlLibrary().ReleaseButtonY();
  SwitchControlLibrary().ReleaseButtonL();
  SwitchControlLibrary().ReleaseButtonR();
  SwitchControlLibrary().ReleaseButtonZL();
  SwitchControlLibrary().ReleaseButtonZR();
  SwitchControlLibrary().ReleaseButtonPlus();
  SwitchControlLibrary().ReleaseButtonMinus();
  SwitchControlLibrary().ReleaseButtonHome();
  SwitchControlLibrary().ReleaseButtonCapture();
  SwitchControlLibrary().ReleaseButtonLStick();
  SwitchControlLibrary().ReleaseButtonRStick();
  SwitchControlLibrary().MoveHat(Hat::CENTER);
  SwitchControlLibrary().moveLeftStick(128, 128);
  SwitchControlLibrary().moveRightStick(128, 128);
  SwitchControlLibrary().sendReport();
}

void blinkLed(int times) {
  for (int i = 0; i < times; i++) {
    digitalWrite(LED_PIN, HIGH);
    delay(200);
    digitalWrite(LED_PIN, LOW);
    delay(200);
  }
}
