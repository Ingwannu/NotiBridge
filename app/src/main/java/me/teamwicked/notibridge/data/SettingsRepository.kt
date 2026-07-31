package me.teamwicked.notibridge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lightweight key-value settings that don't deserve a table.
 *
 * - maxConcurrentDeliveries: upper bound of parallel webhook sends.
 * - dedupeWindowMs: how long an identical notification is suppressed per hook.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("notibridge_settings", Context.MODE_PRIVATE)

    var maxConcurrentDeliveries: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT)
            .coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
        set(value) {
            prefs.edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)).apply()
        }

    var dedupeWindowMs: Long
        get() = prefs.getLong(KEY_DEDUPE_WINDOW, DEFAULT_DEDUPE_WINDOW_MS)
        set(value) {
            prefs.edit().putLong(KEY_DEDUPE_WINDOW, value.coerceAtLeast(0L)).apply()
        }

    fun observeMaxConcurrent(): Flow<Int> = callbackFlow {
        trySend(maxConcurrentDeliveries)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MAX_CONCURRENT) trySend(maxConcurrentDeliveries)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val KEY_MAX_CONCURRENT = "max_concurrent_deliveries"
        const val KEY_DEDUPE_WINDOW = "dedupe_window_ms"
        const val DEFAULT_MAX_CONCURRENT = 4
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 16
        const val DEFAULT_DEDUPE_WINDOW_MS = 60_000L
    }
}
