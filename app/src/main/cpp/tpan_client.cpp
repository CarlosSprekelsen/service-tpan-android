/**
 * tpan_client.cpp — Android NDK JNI entry points for the TPAN client.
 *
 * PORT TARGET: port the tpan-bt-client from the TNN tpan-over-bt prototype.
 * See AGENT.md for detailed porting guidance.
 *
 * Responsibilities:
 *  - Open an RFCOMM (SPP) connection to the Hub (tpan-bt-manager)
 *  - Perform TPAN Control Protocol handshake (identity + capabilities)
 *  - Establish mTLS session using the EUD's Root PKI certificate
 *  - Hand off to socks5h_server once the TPAN Application Protocol session
 *    is established
 *  - Expose JNI functions called by TpanManager.kt
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>

#define LOG_TAG "tpan_client"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_running{false};
static std::thread g_thread;

// Forward declaration
void runTpanClient(std::string hubBtAddr, int socks5hPort);

extern "C" {

JNIEXPORT void JNICALL
Java_com_katim_dts_tpan_TpanManager_nativeStart(
        JNIEnv* env, jobject /* thiz */,
        jstring hubBtAddress, jint socks5hPort) {

    const char* addr = env->GetStringUTFChars(hubBtAddress, nullptr);
    std::string hubAddr(addr);
    env->ReleaseStringUTFChars(hubBtAddress, addr);

    if (g_running.exchange(true)) {
        LOGI("already running");
        return;
    }

    LOGI("starting — hub=%s socks5h=:%d", hubAddr.c_str(), (int)socks5hPort);

    g_thread = std::thread([hubAddr, socks5hPort]() {
        runTpanClient(hubAddr, socks5hPort);
        g_running = false;
    });
    g_thread.detach();
}

JNIEXPORT void JNICALL
Java_com_katim_dts_tpan_TpanManager_nativeStop(
        JNIEnv* /* env */, jobject /* thiz */) {
    LOGI("stop requested");
    g_running = false;
    // TODO: interrupt blocking RFCOMM / SOCKS5H operations
}

} // extern "C"

// ─── TPAN Client Implementation (stub — port from tpan-over-bt) ──────────────

void runTpanClient(std::string hubBtAddr, int socks5hPort) {
    LOGI("runTpanClient(%s, %d)", hubBtAddr.c_str(), socks5hPort);

    // TODO (port from tpan-over-bt):
    //
    // 1. Open RFCOMM socket to hubBtAddr on SPP channel 1
    //      int fd = openRfcomm(hubBtAddr, /*channel=*/1);
    //
    // 2. TPAN Control Protocol handshake
    //      tpanHandshake(fd, deviceCert, rootCa);
    //
    // 3. Establish mTLS session
    //      SSL* ssl = tpanMtls(fd, certPath, keyPath, caPath);
    //
    // 4. Start SOCKS5H server on loopback
    //      startSocks5hServer(socks5hPort, ssl);
    //
    // 5. Loop until g_running is false

    LOGE("STUB — port tpan-bt-client from tpan-over-bt prototype (see AGENT.md)");
}
