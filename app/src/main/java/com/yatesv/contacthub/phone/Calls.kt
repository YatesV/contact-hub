package com.yatesv.contacthub.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.engine.ActionReceiver
import com.yatesv.contacthub.engine.Notifier
import kotlinx.coroutines.flow.MutableStateFlow

data class CallUi(
    val number: String,
    val state: Int,
    val connectedAt: Long,
    val muted: Boolean,
    val speaker: Boolean,
    val onHold: Boolean,
    val outgoing: Boolean,
    /** Carrier said the caller ID failed STIR/SHAKEN verification */
    val verificationFailed: Boolean = false,
)

/** Keeps track of the active call for the call screen. */
object CallManager {
    val ui = MutableStateFlow<CallUi?>(null)
    /** All calls (call waiting can mean two). The screen shows [call]: the ringing one, else the active one. */
    private val calls = mutableListOf<Call>()
    private val call: Call? get() =
        calls.firstOrNull { it.state == Call.STATE_RINGING } ?: calls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: calls.lastOrNull()
    internal var service: CallService? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) { call?.let { publish(it) } }
    }

    fun added(c: Call) {
        calls += c
        c.registerCallback(callback)
        call?.let { publish(it) }
    }

    fun removed(c: Call) {
        // Offer a call note for calls that connected
        lastConnected?.let { (num, at) ->
            lastConnected = null
            service?.let { svc -> runCatching { com.yatesv.contacthub.engine.CallNotes.afterCall(svc, num, (System.currentTimeMillis() - at) / 1000) } }
        }
        c.unregisterCallback(callback)
        calls -= c
        val next = call
        if (next == null) { ui.value = null; service?.let { NotificationManagerCompat.from(it).cancel(NOTIF_ID) } } else publish(next)
    }

    /** Number + time the current call connected (kept after the call screen clears, for the call note). */
    private var lastConnected: Pair<String, Long>? = null

    private fun publish(c: Call) {
        val d = c.details
        val number = d.handle?.schemeSpecificPart.orEmpty()
        val st = c.state
        val prev = ui.value
        val audio = service?.callAudioState
        ui.value = CallUi(
            number = number, state = st,
            // Keep the timer only for the same call (call waiting used to show the first call's time)
            connectedAt = (prev?.takeIf { it.number == number }?.connectedAt ?: 0).let { kept ->
                if (st == Call.STATE_ACTIVE) kept.takeIf { it > 0 } ?: System.currentTimeMillis() else kept
            },
            muted = audio?.isMuted == true,
            speaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
            onHold = st == Call.STATE_HOLDING,
            outgoing = d.callDirection == Call.Details.DIRECTION_OUTGOING,
            verificationFailed = android.os.Build.VERSION.SDK_INT >= 30 && d.callerNumberVerificationStatus == android.telecom.Connection.VERIFICATION_STATUS_FAILED,
        )
        ui.value?.let { if (it.connectedAt > 0 && it.number.isNotBlank()) lastConnected = it.number to it.connectedAt }
        service?.let { svc -> if (st == Call.STATE_RINGING) showRinging(svc, number) else NotificationManagerCompat.from(svc).cancel(NOTIF_ID) }
        if (st == Call.STATE_DISCONNECTED && calls.none { it.state != Call.STATE_DISCONNECTED }) {
            ui.value = null; service?.let { NotificationManagerCompat.from(it).cancel(NOTIF_ID) }
        }
    }

    fun answer() = call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun hangup() { call?.let { if (it.state == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }
    fun toggleHold() { call?.let { if (it.state == Call.STATE_HOLDING) it.unhold() else it.hold() } }
    fun dtmf(c: Char) { call?.playDtmfTone(c); call?.stopDtmfTone() }
    fun setMuted(on: Boolean) { service?.setMuted(on); call?.let { publish(it) } }
    fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        call?.let { publish(it) }
    }
    fun audioChanged() { call?.let { publish(it) } }

    private const val NOTIF_ID = 4242

    private fun showRinging(ctx: Context, number: String) {
        val known = Hub.contacts.cached(number)?.name
        val name = known ?: (if (Hub.orbit.isReportedSpam(number)) "⚠️ Spam · " else "") + com.yatesv.contacthub.data.Phone.pretty(number)
        val full = PendingIntent.getActivity(ctx, 1, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String) = PendingIntent.getBroadcast(ctx, a.hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Notifier.CH_CALLS)
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(Person.Builder().setName(name).build(),
                action(ActionReceiver.DECLINE), action(ActionReceiver.ANSWER)))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    fun place(ctx: Context, number: String, video: Boolean = false) {
        val uri = Uri.fromParts("tel", number, null)
        val canCall = ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canCall) {
            val extras = Bundle()
            if (video) extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            val ok = runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(uri, extras); true }.getOrDefault(false)
            if (ok) return
        }
        // No permission yet: open the dialer with the number filled in
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Android talks to this when Orbit is the default phone app. */
class CallService : InCallService() {
    override fun onCreate() { super.onCreate(); CallManager.service = this }
    override fun onDestroy() { if (CallManager.service === this) CallManager.service = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        CallManager.added(call)
        if (call.state != Call.STATE_RINGING) {
            startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // When the screen is on, show the call screen right away; the full-screen notification covers locked phones
            runCatching { startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onCallRemoved(call: Call) { CallManager.removed(call) }
    override fun onCallAudioStateChanged(audioState: CallAudioState?) { CallManager.audioChanged() }
}
