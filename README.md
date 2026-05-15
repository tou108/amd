# SysDVR 画面認識 & Arduino Leonardo 自動操作

SysDVR の画面キャプチャ映像をリアルタイム解析し、
Arduino Leonardo (HC-05 Bluetooth経由) で Nintendo Switch を自動操作するための改造版です。

## 新機能

- **ScreenAnalyzer** — SysDVRフレームの色範囲・テンプレートマッチング画面認識
- **ArduinoBluetoothController** — HC-05 経由の Bluetooth シリアル通信
- **AutomationManager** — 認識→操作の統合管理・カスタムスクリプトAPI
- **AutomationOverlayView** — SDL画面上のフローティングコントロールUI
- **SwitchAutoController.ino** — Arduino Leonardo スケッチ

## セットアップ

詳細は [AUTOMATION_SETUP.md](./AUTOMATION_SETUP.md) を参照してください。

## ハードウェア構成

```
Switch ←[USB HID]← Arduino Leonardo ←[TX/RX]← HC-05 ←[BT]← Android
                                                                   ↑
                                                              SysDVR APK
                                                          (WiFiで映像受信)
```

## ベースプロジェクト

[SysDVR v6.3](https://github.com/exelix11/SysDVR) by exelix11
