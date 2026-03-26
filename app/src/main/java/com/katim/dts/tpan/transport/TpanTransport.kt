package com.katim.dts.tpan.transport

import java.io.InputStream
import java.io.OutputStream

/**
 * Pluggable transport interface for TPAN bearer connections.
 *
 * Each transport module opens a reliable byte-stream connection to the Hub
 * and exposes read/write streams to the Frame Codec. Only one transport is
 * active at a time, selected by the Bearer Monitor.
 *
 * Implementations: [BtTransport] (RFCOMM), future UWB and WiFi transports.
 *
 * See svc-tpan-architecture.md §Level 2 service-tpan-android.
 */
interface TpanTransport {

    /** Connect to the Hub. Throws [java.io.IOException] on failure. */
    fun connect()

    /** Disconnect from the Hub. Safe to call multiple times. */
    fun disconnect()

    /** Input stream from Hub (framed data). Only valid while [isConnected]. */
    val input: InputStream

    /** Output stream to Hub (framed data). Only valid while [isConnected]. */
    val output: OutputStream

    /** True if the transport byte-stream is open. */
    val isConnected: Boolean
}
