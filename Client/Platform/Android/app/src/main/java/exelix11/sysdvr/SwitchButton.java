package exelix11.sysdvr;

/**
 * Nintendo Switch ボタン定義
 * Arduino側のコマンドプロトコルと完全同期
 */
public enum SwitchButton {
    RELEASE_ALL (0x00, "全リリース"),
    A           (0x01, "A"),
    B           (0x02, "B"),
    X           (0x03, "X"),
    Y           (0x04, "Y"),
    L           (0x05, "L"),
    R           (0x06, "R"),
    ZL          (0x07, "ZL"),
    ZR          (0x08, "ZR"),
    PLUS        (0x09, "+"),
    MINUS       (0x0A, "-"),
    HOME        (0x0B, "Home"),
    CAPTURE     (0x0C, "Capture"),
    DPAD_UP     (0x0D, "↑"),
    DPAD_DOWN   (0x0E, "↓"),
    DPAD_LEFT   (0x0F, "←"),
    DPAD_RIGHT  (0x10, "→"),
    L_STICK     (0x11, "Lスティック押込"),
    R_STICK     (0x12, "Rスティック押込"),
    LSTICK_UP   (0xF0, "Lスティック↑"),
    LSTICK_DOWN (0xF1, "Lスティック↓"),
    LSTICK_LEFT (0xF2, "Lスティック←"),
    LSTICK_RIGHT(0xF3, "Lスティック→"),
    RSTICK_UP   (0xF4, "Rスティック↑"),
    RSTICK_DOWN (0xF5, "Rスティック↓"),
    RSTICK_LEFT (0xF6, "Rスティック←"),
    RSTICK_RIGHT(0xF7, "Rスティック→"),
    STICK_NEUTRAL(0xFE, "スティック中立"),
    FULL_RELEASE (0xFF, "全リリース+中立");

    public final int code;
    public final String label;

    SwitchButton(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public byte toByte() {
        return (byte)(code & 0xFF);
    }
}
