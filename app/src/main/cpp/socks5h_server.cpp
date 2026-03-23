/**
 * socks5h_server.cpp — SOCKS5H proxy server (loopback).
 *
 * PORT TARGET: port the SOCKS5H server from the TNN tpan-over-bt prototype.
 *
 * Listens on localhost:1080 (or configured port). Each incoming SOCKS5H
 * connection is relayed over the established TPAN mTLS session to the Hub.
 * The Hub's tpan-bt-manager then routes the connection to the target IP
 * within the Hub LAN (192.168.101.0/24).
 *
 * Applications on the EUD that need to reach Hub container services should be
 * configured to use SOCKS5H proxy at 127.0.0.1:1080.
 *
 * Future: Per-App VPN mode (Android VpnService) will replace explicit SOCKS5H
 * configuration by transparently intercepting all outbound traffic.
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "socks5h_server"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// TODO (port from tpan-over-bt):
//
// void startSocks5hServer(int port, SSL* tpanSession) {
//     // 1. Bind TCP socket on 127.0.0.1:port
//     // 2. For each accepted connection:
//     //    a. SOCKS5 handshake (RFC 1928 + SOCKS5H hostname resolution)
//     //    b. Forward CONNECT target address to Hub via TPAN Application Protocol
//     //    c. Relay bytes bidirectionally: client socket <-> TPAN mTLS session
// }
