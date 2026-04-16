package com.katim.dts.service.tpan

import android.util.Log
import com.katim.dts.service.tpan.codec.Frame
import com.katim.dts.service.tpan.codec.FrameCodec
import com.katim.dts.service.tpan.transport.TpanTransport
import com.katim.dts.service.tpan.tap.TapEngine
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the active data-path connection: keepalive timer, link-dead
 * detection, graceful shutdown, and reconnection with exponential backoff.
 *
 * See svc-tpan-architecture.md §Data Path (lines 220–261),
 * §Reconnection (lines 719–744).
 */
class ConnectionManager(
    private val tapEngine: TapEngine
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val KEEPALIVE_INTERVAL_MS = 1000L
        private const val KEEPALIVE_TIMEOUT_MS = 3000L
        private const val SHUTDOWN_WAIT_MS = 500L
        private val BACKOFF_DELAYS_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
    }

    /** Called when link-dead is detected (3 missed keepalives). */
    var onLinkDead: (() -> Unit)? = null

    /** Called when reconnection should be attempted. */
    var onReconnect: (() -> Unit)? = null

    private var scheduler: ScheduledExecutorService? = null
    private var keepaliveFuture: ScheduledFuture<*>? = null
    private var linkCheckFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null

    private val lastReceivedTimestamp = AtomicLong(0)

    @Volatile
    private var activeOutput: OutputStream? = null

    @Volatile
    private var running = false

    private var reconnectAttempt = 0

    // ── Data path (A701) ────────────────────────────────────────────────────

    /**
     * Start the data path: TAP Engine read/write loops + keepalive timer.
     */
    fun startDataPath(transport: TpanTransport) {
        if (running) stopDataPath()

        running = true
        activeOutput = transport.output
        reconnectAttempt = 0

        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = Executors.newScheduledThreadPool(2) { r ->
                Thread(r, "tpan-conn-mgr").apply { isDaemon = true }
            }
        }

        // Register keepalive callback on TAP engine
        lastReceivedTimestamp.set(System.currentTimeMillis())
        tapEngine.onKeepaliveReceived = { onFrameReceived() }

        // Start TAP Engine data-path loops
        tapEngine.startDataPath(transport.input, transport.output)

        // Start keepalive sender and link-dead checker
        startKeepaliveTimer()

        Log.i(TAG, "Data path started")
    }

    /**
     * Stop the data path. Does NOT close the TAP device (stays up for reconnect).
     */
    fun stopDataPath() {
        if (!running) return
        running = false

        stopKeepaliveTimer()
        cancelReconnect()
        tapEngine.stopDataPath()
        tapEngine.onKeepaliveReceived = null
        activeOutput = null

        Log.i(TAG, "Data path stopped")
    }

    // ── KEEPALIVE timer (A702) ──────────────────────────────────────────────

    private fun startKeepaliveTimer() {
        // Send KEEPALIVE every 1s
        keepaliveFuture = scheduler?.scheduleAtFixedRate({
            val out = activeOutput ?: return@scheduleAtFixedRate
            try {
                FrameCodec.writeFrame(out, Frame.keepalive())
            } catch (e: IOException) {
                Log.w(TAG, "KEEPALIVE send failed", e)
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)

        // Check for link-dead every 1s
        linkCheckFuture = scheduler?.scheduleAtFixedRate({
            val elapsed = System.currentTimeMillis() - lastReceivedTimestamp.get()
            if (elapsed > KEEPALIVE_TIMEOUT_MS) {
                Log.w(TAG, "Link dead — ${elapsed}ms since last received frame")
                stopKeepaliveTimer()
                onLinkDead?.invoke()
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopKeepaliveTimer() {
        keepaliveFuture?.cancel(false)
        linkCheckFuture?.cancel(false)
        keepaliveFuture = null
        linkCheckFuture = null
    }

    /**
     * Called when any frame (DATA or KEEPALIVE) is received from the peer.
     * Resets the link-dead timer.
     */
    fun onFrameReceived() {
        lastReceivedTimestamp.set(System.currentTimeMillis())
    }

    // ── Graceful shutdown (A704) ────────────────────────────────────────────

    /**
     * Send SHUTDOWN frame and wait briefly for peer acknowledgement.
     */
    fun gracefulShutdown() {
        val out = activeOutput
        if (out != null) {
            try {
                FrameCodec.writeFrame(out, Frame.shutdown())
                Log.i(TAG, "SHUTDOWN frame sent, waiting ${SHUTDOWN_WAIT_MS}ms")
                Thread.sleep(SHUTDOWN_WAIT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Error during graceful shutdown", e)
            }
        }
        stopDataPath()
    }

    // ── Reconnection with exponential backoff (A703) ────────────────────────

    /**
     * Begin reconnection attempts with exponential backoff.
     * TAP device stays active — apps see timeouts, not config changes.
     */
    fun startReconnect() {
        if (!running && scheduler?.isShutdown != false) {
            scheduler = Executors.newScheduledThreadPool(1) { r ->
                Thread(r, "tpan-reconnect").apply { isDaemon = true }
            }
        }
        scheduleNextReconnect()
    }

    private fun scheduleNextReconnect() {
        val delayIdx = reconnectAttempt.coerceAtMost(BACKOFF_DELAYS_MS.size - 1)
        val delayMs = BACKOFF_DELAYS_MS[delayIdx]
        reconnectAttempt++

        Log.i(TAG, "Reconnect attempt $reconnectAttempt in ${delayMs}ms")
        reconnectFuture = scheduler?.schedule({
            onReconnect?.invoke()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Called when reconnection succeeds — resets the backoff counter.
     */
    fun reconnectSucceeded() {
        reconnectAttempt = 0
    }

    /**
     * Called when reconnection fails — schedule next attempt.
     */
    fun reconnectFailed() {
        scheduleNextReconnect()
    }

    fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectAttempt = 0
    }

    /**
     * Shut down all executor threads.
     */
    fun destroy() {
        stopDataPath()
        scheduler?.shutdownNow()
        scheduler = null
    }
}

