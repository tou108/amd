/**
 * auto_capture.c
 * SDL2 フレームバッファのキャプチャと Java 側 AutomationManager への転送
 *
 * このファイルは既存のネイティブビルドに組み込まれます。
 * Android.mk に追加必要: LOCAL_SRC_FILES += auto_capture.c
 */

#include "JniHelper.h"
#include <SDL.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "AutoCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// キャプチャ設定
#define CAPTURE_SCALE_W  320   // キャプチャ縮小幅 (解析精度と速度のバランス)
#define CAPTURE_SCALE_H  180   // キャプチャ縮小高さ

static jclass  g_automationClass   = NULL;
static jmethodID g_onNewFrameMethod = NULL;
static bool    g_initialized        = false;

// フレームカウンタ (スキップ制御)
static int s_frameCount     = 0;
static int s_captureInterval = 6; // 6フレームに1回キャプチャ

/**
 * JNI 初期化 - SysInit() から呼び出す
 */
void AutoCaptureInit(void)
{
    JNIEnv* env = GetJNIEnv();
    if (!env) {
        LOGE("AutoCaptureInit: JNI env が取得できません");
        return;
    }

    jclass cls = (*env)->FindClass(env, "exelix11/sysdvr/AutomationManager");
    if (!cls) {
        LOGE("AutoCaptureInit: AutomationManager クラスが見つかりません");
        (*env)->ExceptionClear(env);
        return;
    }

    g_automationClass = (*env)->NewGlobalRef(env, cls);
    g_onNewFrameMethod = (*env)->GetStaticMethodID(
        env, g_automationClass,
        "onNewFrameJni", "([III)V");

    if (!g_onNewFrameMethod) {
        LOGE("AutoCaptureInit: onNewFrameJni メソッドが見つかりません");
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_automationClass);
        g_automationClass = NULL;
        return;
    }

    g_initialized = true;
    LOGI("AutoCaptureInit: 初期化成功 (キャプチャサイズ: %dx%d)", CAPTURE_SCALE_W, CAPTURE_SCALE_H);
}

/**
 * キャプチャ間隔を設定
 * @param interval フレームスキップ数 (1=毎フレーム, 6=6フレームに1回)
 */
void AutoCaptureSetInterval(int interval)
{
    s_captureInterval = interval > 0 ? interval : 1;
}

/**
 * SDL レンダラーからフレームをキャプチャして Java 側に転送
 * SDL_RenderPresent() の直前に呼び出す。
 *
 * @param renderer SDL_Renderer ポインタ
 */
void AutoCaptureFrame(SDL_Renderer* renderer)
{
    if (!g_initialized || !g_automationClass) return;

    // フレームスキップ
    s_frameCount++;
    if (s_frameCount < s_captureInterval) return;
    s_frameCount = 0;

    // レンダラーの現在の描画ターゲットサイズを取得
    int renderW = 0, renderH = 0;
    SDL_RenderGetLogicalSize(renderer, &renderW, &renderH);
    if (renderW == 0 || renderH == 0) {
        SDL_GetRendererOutputSize(renderer, &renderW, &renderH);
    }
    if (renderW == 0 || renderH == 0) return;

    // フレームバッファ読み取り用 Surface 作成 (縮小サイズ)
    // 注: SDL_RenderReadPixels はフルサイズで読む必要があるため、後でスケール
    SDL_Surface* surface = SDL_CreateRGBSurfaceWithFormat(
        0, renderW, renderH, 32, SDL_PIXELFORMAT_ARGB8888);
    if (!surface) {
        LOGE("Surface 作成失敗: %s", SDL_GetError());
        return;
    }

    if (SDL_RenderReadPixels(renderer, NULL,
                              SDL_PIXELFORMAT_ARGB8888,
                              surface->pixels,
                              surface->pitch) != 0) {
        LOGE("RenderReadPixels 失敗: %s", SDL_GetError());
        SDL_FreeSurface(surface);
        return;
    }

    // スケールダウン (解析負荷軽減)
    SDL_Surface* scaled = SDL_CreateRGBSurfaceWithFormat(
        0, CAPTURE_SCALE_W, CAPTURE_SCALE_H, 32, SDL_PIXELFORMAT_ARGB8888);
    if (!scaled) {
        SDL_FreeSurface(surface);
        return;
    }

    SDL_BlitScaled(surface, NULL, scaled, NULL);
    SDL_FreeSurface(surface);

    // Java に転送
    int pixelCount = CAPTURE_SCALE_W * CAPTURE_SCALE_H;
    JNIEnv* env = GetJNIEnv();
    if (!env) {
        SDL_FreeSurface(scaled);
        return;
    }

    jintArray jPixels = (*env)->NewIntArray(env, pixelCount);
    if (!jPixels) {
        SDL_FreeSurface(scaled);
        return;
    }

    (*env)->SetIntArrayRegion(env, jPixels, 0, pixelCount, (jint*)scaled->pixels);
    SDL_FreeSurface(scaled);

    (*env)->CallStaticVoidMethod(env, g_automationClass, g_onNewFrameMethod,
                                  jPixels,
                                  (jint)CAPTURE_SCALE_W,
                                  (jint)CAPTURE_SCALE_H);

    (*env)->DeleteLocalRef(env, jPixels);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("onNewFrameJni 例外発生");
        (*env)->ExceptionClear(env);
    }
}

/**
 * JNI: Java から直接キャプチャを要求する場合
 * Java: AutoCapture.requestCapture()
 */
JNIEXPORT void JNICALL
Java_exelix11_sysdvr_AutoCapture_requestCapture(JNIEnv* env, jclass cls)
{
    // SDL_GetRenderer が利用可能な場合のみ呼び出し
    // 通常は AutoCaptureFrame() が自動的に呼ばれるため不要
    LOGI("手動キャプチャ要求");
}
