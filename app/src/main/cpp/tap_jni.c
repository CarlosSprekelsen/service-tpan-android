/**
 * TAP device JNI helper for service-tpan-android.
 *
 * Creates a TAP (Layer 2) network interface via the Linux TUNSETIFF ioctl.
 * Requires root or CAP_NET_ADMIN — the KATIM platform build runs this
 * service as root, so no special capability grants are needed.
 *
 * TAP is preferred over TUN: it supports native ARP, L2 multicast, and
 * broadcast without any route injection workarounds — required for
 * SITAWARE/STC COP distribution.
 *
 * Reference: svc-tpan-architecture.md §Solution Strategy (AD-TPAN-01)
 */
#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/if_tun.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>

#define TAG "TapJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_katim_dts_service_tpan_tap_TapEngine_nativeCreateTap(
        JNIEnv *env, jobject thiz, jstring device_name)
{
    int fd = open("/dev/net/tun", O_RDWR);
    if (fd < 0) {
        LOGE("open /dev/net/tun failed: %s", strerror(errno));
        return -1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_flags = IFF_TAP | IFF_NO_PI;  /* TAP mode, no packet info header */

    const char *name = (*env)->GetStringUTFChars(env, device_name, NULL);
    if (name == NULL) {
        close(fd);
        return -1;
    }
    strncpy(ifr.ifr_name, name, IFNAMSIZ - 1);
    (*env)->ReleaseStringUTFChars(env, device_name, name);

    if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
        LOGE("TUNSETIFF failed for %s: %s", ifr.ifr_name, strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("Created TAP device: %s (fd=%d)", ifr.ifr_name, fd);
    return fd;
}
