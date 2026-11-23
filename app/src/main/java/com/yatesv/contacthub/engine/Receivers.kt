package com.yatesv.contacthub.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.RemoteInput
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.HubApp
import com.yatesv.contacthub.data.ConvMeta
import com.yatesv.contacthub.phone.CallManager
import com.yatesv.contacthub.ui.HubWidget
import kotlinx.coroutines.launch

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { com.yatesv.contacthub.data.safely("Receivers") {
        val key = intent.getStringExtra("conv").orEmpty()
        when (intent.action) {
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString().orEmpty()
                if (text.isBlank()) return
                // Sending can take several seconds, longer than a broadcast is allowed to run
                Notifier.cancel(ctx, key)
                ReplyWorker.enqueue(ctx, key, text)
            }
            READ -> {
                val pending = goAsync()
                Hub.scope.launch {
                    try { runCatching { markRead(key); Notifier.cancel(ctx, key) } } finally { pending.finish() }
                }
            }
            COPY -> intent.getStringExtra("code")?.let { code ->
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Code", code))
                android.widget.Toast.makeText(ctx, "Copied $code", android.widget.Toast.LENGTH_SHORT).show()
            }
            CALL -> intent.getStringExtra("n")?.takeIf { it.isNotBlank() }?.let { CallManager.place(ctx, it) }
            ANSWER -> CallManager.answer()
            DECLINE -> CallManager.hangup()
            AUTO_REPLY -> {
                Hub.settings.update { it.copy(autoReplyOn = !it.autoReplyOn) }
                val pending = goAsync()
                Hub.scope.launch { try { runCatching { HubWidget.draw(ctx) } } finally { pending.finish() } }
            }
        }
    }
    }

    companion object {
        const val REPLY = "com.yatesv.contacthub.REPLY"
        const val READ = "com.yatesv.contacthub.READ"
        const val ANSWER = "com.yatesv.contacthub.ANSWER"
        const val CALL = "com.yatesv.contacthub.CALL"
        const val DECLINE = "com.yatesv.contacthub.DECLINE"
        const val AUTO_REPLY = "com.yatesv.contacthub.AUTO_REPLY"
        const val COPY = "com.yatesv.contacthub.COPY_CODE"

        /** Marks read in Orbit and taps "Mark as read" in the Messages app too, so both stay in sync. */
        suspend fun markRead(key: String) {
            val dao = Hub.db.dao()
            val m = dao.meta(key) ?: ConvMeta(key)
            dao.putMeta(m.copy(lastRead = System.currentTimeMillis()))
            Hub.replies[key]?.markRead?.let { runCatching { it.send() } }
            HubSms.markThreadRead(Hub.app, Hub.sms.threadIdFor(key))
            Hub.changed()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { com.yatesv.contacthub.data.safely("Receivers") {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                HubApp.scheduleWork(ctx)
                ScheduleWorker.enqueue(ctx, 0)
                MockStore.onBoot(ctx)
                Arrivals.syncAll(ctx)
                DailyWorker.schedule(ctx)
            }
            // An update clears Android's location alerts for Orbit: register them again
            Intent.ACTION_MY_PACKAGE_REPLACED -> { Arrivals.syncAll(ctx); DailyWorker.schedule(ctx) }
        }
    }
    }
}

/** Quick Settings tile that turns auto-reply on and off. */
class AutoReplyTile : TileService() {
    override fun onStartListening() { update() }
    override fun onClick() { com.yatesv.contacthub.data.safely("Receivers") {
        Hub.settings.update { it.copy(autoReplyOn = !it.autoReplyOn) }
        HubWidget.refresh(this)
        update()
    }
    }
    private fun update() {
        val on = Hub.settings.value.autoReplyOn
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Auto-reply"
            subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
