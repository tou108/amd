package exelix11.sysdvr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

/**
 * SysDVR キャプチャフレームを解析し、Switch の画面状態を判定するクラス
 *
 * 認識精度向上のためのカスタマイズ方法:
 *  - registerTemplate() でゲーム固有の画像テンプレートを登録
 *  - ColorRegion でカラー比較による状態検出を定義
 *  - getLastAnalysisResult() で最新の解析結果を取得
 */
public class ScreenAnalyzer {

    private static final String TAG = "ScreenAnalyzer";

    // Nintendo Switch 標準解像度
    public static final int SWITCH_WIDTH  = 1280;
    public static final int SWITCH_HEIGHT = 720;

    /** 解析結果 */
    public static class AnalysisResult {
        public long timestamp;
        public DetectedState state;
        public float confidence;   // 0.0 - 1.0
        public String details;

        public AnalysisResult(DetectedState state, float confidence, String details) {
            this.timestamp  = System.currentTimeMillis();
            this.state      = state;
            this.confidence = confidence;
            this.details    = details;
        }
    }

    /** 検出できる画面状態 */
    public enum DetectedState {
        UNKNOWN,
        HOME_MENU,          // ホームメニュー
        GAME_RUNNING,       // ゲーム中
        BATTLE_START,       // バトル開始演出
        BATTLE_END_WIN,     // 勝利画面
        BATTLE_END_LOSE,    // 敗北画面
        DIALOG_YES_NO,      // はい/いいえダイアログ
        LOADING_SCREEN,     // ロード中
        TITLE_SCREEN,       // タイトル画面
        CUSTOM_1,           // ユーザー定義 1
        CUSTOM_2,           // ユーザー定義 2
        CUSTOM_3,           // ユーザー定義 3
    }

    /** 色範囲定義 */
    public static class ColorRegion {
        public int x, y, width, height;
        public int targetColor;    // ARGB
        public int tolerance;      // 0-255
        public DetectedState resultState;
        public float minMatchRatio; // 0.0-1.0 (この割合以上一致で検出)

        public ColorRegion(int x, int y, int w, int h,
                           int targetColor, int tolerance,
                           DetectedState state, float minMatch) {
            this.x = x; this.y = y;
            this.width = w; this.height = h;
            this.targetColor  = targetColor;
            this.tolerance    = tolerance;
            this.resultState  = state;
            this.minMatchRatio = minMatch;
        }
    }

    /** テンプレート定義 */
    public static class TemplateInfo {
        public Bitmap template;
        public int searchX, searchY;      // 探索開始位置
        public int searchWidth, searchHeight; // 探索範囲
        public DetectedState resultState;
        public float threshold;           // 一致率閾値 (0.0-1.0)
        public String name;

        public TemplateInfo(Bitmap tpl, int sx, int sy, int sw, int sh,
                            DetectedState state, float threshold, String name) {
            this.template = tpl;
            this.searchX = sx; this.searchY = sy;
            this.searchWidth = sw; this.searchHeight = sh;
            this.resultState = state;
            this.threshold = threshold;
            this.name = name;
        }
    }

    // 登録されたルール
    private final java.util.List<ColorRegion> colorRules = new java.util.ArrayList<>();
    private final java.util.List<TemplateInfo> templates = new java.util.ArrayList<>();

    // 最新結果
    private volatile AnalysisResult lastResult = new AnalysisResult(DetectedState.UNKNOWN, 0, "初期化");

    // 解析スキップカウンタ (負荷軽減)
    private int frameSkipCount = 0;
    private int frameSkipInterval = 5; // N フレームに1回解析

    // デフォルトルール登録フラグ
    private boolean defaultRulesRegistered = false;

    public ScreenAnalyzer() {
        registerDefaultRules();
    }

    /**
     * フレームを解析 (JNIから呼び出し, またはメインループから定期呼び出し)
     * @param pixels ARGB_8888 ピクセルデータ
     * @param width  フレーム幅
     * @param height フレーム高さ
     * @return 解析結果
     */
    public AnalysisResult analyze(int[] pixels, int width, int height) {
        // フレームスキップ
        frameSkipCount++;
        if (frameSkipCount < frameSkipInterval) return lastResult;
        frameSkipCount = 0;

        try {
            Bitmap bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            lastResult = analyzeInternal(bmp);
            bmp.recycle();
        } catch (Exception e) {
            Log.e(TAG, "解析エラー: " + e.getMessage());
        }
        return lastResult;
    }

    /**
     * Bitmapを直接解析
     */
    public AnalysisResult analyze(Bitmap bmp) {
        frameSkipCount++;
        if (frameSkipCount < frameSkipInterval) return lastResult;
        frameSkipCount = 0;

        try {
            lastResult = analyzeInternal(bmp);
        } catch (Exception e) {
            Log.e(TAG, "解析エラー: " + e.getMessage());
        }
        return lastResult;
    }

    public AnalysisResult getLastResult() { return lastResult; }

    /** フレームスキップ間隔を設定 (1 = 毎フレーム) */
    public void setFrameSkipInterval(int interval) {
        frameSkipInterval = Math.max(1, interval);
    }

    /** 色範囲ルールを追加 */
    public void addColorRule(ColorRegion rule) {
        colorRules.add(rule);
    }

    /** テンプレートを登録 */
    public void registerTemplate(TemplateInfo info) {
        templates.add(info);
    }

    /** 登録されたルールをすべて削除 */
    public void clearRules() {
        colorRules.clear();
        templates.clear();
        defaultRulesRegistered = false;
    }

    // ---- 内部実装 ----

    private AnalysisResult analyzeInternal(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        // 1. テンプレートマッチング (精度高)
        for (TemplateInfo tpl : templates) {
            float score = templateMatch(bmp, tpl, w, h);
            if (score >= tpl.threshold) {
                return new AnalysisResult(tpl.resultState, score,
                        "テンプレート一致: " + tpl.name + " (" + String.format("%.2f", score) + ")");
            }
        }

        // 2. 色範囲マッチング (高速)
        for (ColorRegion rule : colorRules) {
            float matchRatio = colorRegionMatch(bmp, rule, w, h);
            if (matchRatio >= rule.minMatchRatio) {
                return new AnalysisResult(rule.resultState, matchRatio,
                        "色範囲一致: " + rule.resultState.name() + " (" + String.format("%.2f", matchRatio) + ")");
            }
        }

        // 3. 画面輝度による判定 (ロード画面検出)
        float brightness = averageBrightness(bmp, w, h);
        if (brightness < 0.05f) {
            return new AnalysisResult(DetectedState.LOADING_SCREEN, 0.8f, "暗画面 (ロード中の可能性)");
        }

        return new AnalysisResult(DetectedState.UNKNOWN, 0, "未検出");
    }

    /**
     * 簡易テンプレートマッチング (正規化相互相関)
     * 探索領域内でスライドして最大一致率を返す
     */
    private float templateMatch(Bitmap src, TemplateInfo tpl, int srcW, int srcH) {
        if (tpl.template == null) return 0;

        int tplW = tpl.template.getWidth();
        int tplH = tpl.template.getHeight();

        // 探索領域クリップ
        int ex = Math.min(tpl.searchX + tpl.searchWidth,  srcW) - tplW;
        int ey = Math.min(tpl.searchY + tpl.searchHeight, srcH) - tplH;
        if (ex < tpl.searchX || ey < tpl.searchY) return 0;

        float bestScore = 0;

        // ストライドを大きくして高速化 (精度とのトレードオフ)
        int stride = Math.max(1, tplW / 8);

        for (int y = tpl.searchY; y <= ey; y += stride) {
            for (int x = tpl.searchX; x <= ex; x += stride) {
                float score = calcNCC(src, tpl.template, x, y, tplW, tplH);
                if (score > bestScore) bestScore = score;
                if (bestScore >= tpl.threshold) return bestScore; // 早期終了
            }
        }
        return bestScore;
    }

    /** 正規化相互相関スコア (0.0-1.0) */
    private float calcNCC(Bitmap src, Bitmap tpl, int offX, int offY, int w, int h) {
        long sumMatch = 0;
        long sumTotal = 0;

        // サンプリング間隔 (速度優先)
        int step = Math.max(1, w / 16);

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int srcPx  = src.getPixel(offX + x, offY + y);
                int tplPx  = tpl.getPixel(x, y);

                int dr = Math.abs(Color.red(srcPx)   - Color.red(tplPx));
                int dg = Math.abs(Color.green(srcPx) - Color.green(tplPx));
                int db = Math.abs(Color.blue(srcPx)  - Color.blue(tplPx));

                int diff = (dr + dg + db) / 3; // 0-255
                sumMatch += (255 - diff);
                sumTotal += 255;
            }
        }

        return sumTotal == 0 ? 0 : (float) sumMatch / sumTotal;
    }

    /** 色範囲一致率 (0.0-1.0) */
    private float colorRegionMatch(Bitmap bmp, ColorRegion rule, int bmpW, int bmpH) {
        int x1 = Math.min(rule.x, bmpW - 1);
        int y1 = Math.min(rule.y, bmpH - 1);
        int x2 = Math.min(rule.x + rule.width,  bmpW);
        int y2 = Math.min(rule.y + rule.height, bmpH);

        int matched = 0, total = 0;
        int step = Math.max(1, rule.width / 20); // 最大20点サンプル

        int tr = Color.red(rule.targetColor);
        int tg = Color.green(rule.targetColor);
        int tb = Color.blue(rule.targetColor);

        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int px = bmp.getPixel(x, y);
                if (colorInTolerance(px, tr, tg, tb, rule.tolerance)) matched++;
                total++;
            }
        }

        return total == 0 ? 0 : (float) matched / total;
    }

    private boolean colorInTolerance(int pixel, int tr, int tg, int tb, int tol) {
        return Math.abs(Color.red(pixel)   - tr) <= tol
            && Math.abs(Color.green(pixel) - tg) <= tol
            && Math.abs(Color.blue(pixel)  - tb) <= tol;
    }

    /** 画面全体の平均輝度 (0.0-1.0) */
    private float averageBrightness(Bitmap bmp, int w, int h) {
        long total = 0;
        int samples = 0;
        int step = Math.max(1, Math.min(w, h) / 20);

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int px = bmp.getPixel(x, y);
                total += (Color.red(px) + Color.green(px) + Color.blue(px));
                samples++;
            }
        }
        return samples == 0 ? 0 : (float)(total) / (samples * 3 * 255);
    }

    /**
     * ホームメニュー検出用デフォルトルール登録
     * ゲームに応じてオーバーライド可能
     */
    private void registerDefaultRules() {
        if (defaultRulesRegistered) return;
        defaultRulesRegistered = true;

        // Nintendo Switchホームメニュー: 上部バーの濃紺色を検出
        // (実際のゲームに合わせて調整してください)
        addColorRule(new ColorRegion(
                0, 0, SWITCH_WIDTH, 50,     // 上部バー領域
                Color.rgb(32, 32, 64),       // ホームメニューの濃紺
                40,                          // 許容誤差
                DetectedState.HOME_MENU,
                0.6f                         // 60%以上一致
        ));

        // タイトル画面: 画面中央が明るい場合
        // 必要に応じて追加
    }
}
