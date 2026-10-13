package com.turbovoice.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs background loads on a single worker thread and delivers their results on
 * the main thread, dropping any result that a newer load has superseded.
 *
 * Used for the update check and download, which must stay off the main thread.
 */
class BackgroundLoader {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    /**
     * Runs [produce] off the main thread, then [consume] on the main thread with
     * its result — but only if no newer [load] has started in the meantime (and
     * the loader hasn't been cancelled). Exceptions in [produce] are swallowed so
     * one failed load can't crash the app; the result is simply not delivered.
     */
    fun <T> load(produce: () -> T, consume: (T) -> Unit) {
        val gen = generation.incrementAndGet()
        executor.execute {
            val result = try {
                produce()
            } catch (e: Exception) {
                return@execute
            }
            mainHandler.post {
                if (gen == generation.get()) consume(result)
            }
        }
    }

    /** Invalidates in-flight loads and stops the worker thread. Idempotent. */
    fun cancel() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }
}
