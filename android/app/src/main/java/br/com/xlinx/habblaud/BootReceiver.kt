package br.com.xlinx.habblaud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(5_000) {
                    NotificationService.startIfConfigured(app, LocalStateStore(app))
                }
                if (completed == null) Log.w(TAG, "Timed out reading notification settings")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not restore notification service", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
