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
    /** People in a merged (conference) call */
    val participants: List<String> = emptyList(),
    /** The other call (on hold / waiting), if there are two */
    val other: OtherCall? = null,
    val canMerge: Boolean = false,
    val canAddCall: Boolean = false,
    /** Where the sound is going right now */
    val route: AudioRoute.Kind = AudioRoute.Kind.PHONE,
)

data class OtherCall(val number: String, val state: Int, val participants: Int)

/** A place call audio can go: the phone's earpiece, speaker, a Bluetooth device or a wired headset. */
data class AudioRoute(val id: String, val label: String, val kind: Kind, val active: Boolean, internal val ref: Any? = null) {
    enum class Kind { PHONE, SPEAKER, BLUETOOTH, WIRED, STREAMING }
}

/** Keeps track of the active call for the call screen. */
object CallManager {
    val ui = MutableStateFlow<CallUi?>(null)
    /** All calls (call waiting can mean two). The screen shows [call]: the ringing one, else the active one. */
    private val calls = mutableListOf<Call>()
    /** Calls shown as their own line (people inside a merged call are hidden behind the merged call) */
    private val top: List<Call> get() = calls.filter { it.parent == null && it.state != Call.STATE_DISCONNECTED }
    private val call: Call? get() =
        top.firstOrNull { it.state == Call.STATE_RINGING } ?: top.firstOrNull { it.state == Call.STATE_ACTIVE } ?: top.lastOrNull() ?: calls.lastOrNull()

    /** Every place the audio can go right now (for the audio picker on the call screen) */
    val routes = MutableStateFlow<List<AudioRoute>>(emptyList())
    internal var endpoints: List<Any> = emptyList()   // CallEndpoint on Android 14+
    internal var endpoint: Any? = null
    internal var service: CallService? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) { call?.let { publish(it) } }
        override fun onChildrenChanged(c: Call, children: MutableList<Call>) { call?.let { publish(it) } }
        override fun onParentChanged(c: Call, parent: Call?) { call?.let { publish(it) } }
        override fun onConferenceableCallsChanged(c: Call, list: MutableList<Call>) { call?.let { publish(it) } }
        override fun onDetailsChanged(c: Call, details: Call.Details) { call?.let { publish(it) } }
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
        if (next == null) {
            ui.value = null; service?.let { NotificationManagerCompat.from(it).cancel(NOTIF_ID) }
            service?.let { CallRecorder.stop(it) }
        } else publish(next)
    }

    /** Number + time the current call connected (kept after the call screen clears, for the call note). */
    private var lastConnected: Pair<String, Long>? = null

    private fun numberOf(c: Call) = c.details.handle?.schemeSpecificPart.orEmpty()

    private fun publish(c: Call) {
        val d = c.details
        val kids = c.children.filter { it.state != Call.STATE_DISCONNECTED }.map { numberOf(it) }.filter { it.isNotBlank() }
        val number = numberOf(c).ifBlank { kids.firstOrNull().orEmpty() }
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
            participants = kids,
            other = top.firstOrNull { it !== c }?.let { o -> OtherCall(numberOf(o).ifBlank { o.children.firstOrNull()?.let(::numberOf).orEmpty() }, o.state, o.children.size) },
            canMerge = c.conferenceableCalls.isNotEmpty() || d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) ||
                (top.size >= 2 && top.any { it.state == Call.STATE_HOLDING } && top.any { it.conferenceableCalls.isNotEmpty() }),
            canAddCall = st == Call.STATE_ACTIVE && top.size < 2,
            route = currentKind(),
        )
        refreshRoutes()
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
        if (!on) routes.value.firstOrNull { it.kind == AudioRoute.Kind.BLUETOOTH && it.active }?.let { return }
        val target = routes.value.firstOrNull { it.kind == if (on) AudioRoute.Kind.SPEAKER else AudioRoute.Kind.PHONE }
            ?: routes.value.firstOrNull { !on && it.kind == AudioRoute.Kind.WIRED }
        if (target != null) selectRoute(target)
        else service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        call?.let { publish(it) }
    }
    fun audioChanged() { call?.let { publish(it) } ?: refreshRoutes() }

    // ---------------- Two calls: merge / swap ----------------

    /** Joins the two calls into one (3-way). */
    fun merge(): Boolean {
        val active = top.firstOrNull { it.state == Call.STATE_ACTIVE } ?: call ?: return false
        val other = active.conferenceableCalls.firstOrNull() ?: top.firstOrNull { it !== active && it in active.conferenceableCalls }
        return runCatching {
            when {
                other != null -> active.conference(other)
                active.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) -> active.mergeConference()
                else -> top.firstOrNull { it !== active }?.let { o -> o.conferenceableCalls.firstOrNull()?.let { o.conference(it) } ?: o.mergeConference() } ?: return false
            }
            true
        }.getOrDefault(false)
    }

    /** Talk to the call that's on hold (the current one goes on hold). */
    fun swap() {
        val active = top.firstOrNull { it.state == Call.STATE_ACTIVE }
        val held = top.firstOrNull { it.state == Call.STATE_HOLDING }
        when {
            active != null && active.details.can(Call.Details.CAPABILITY_SWAP_CONFERENCE) -> active.swapConference()
            held != null -> held.unhold()   // Telecom puts the active one on hold
            else -> active?.hold()
        }
    }

    /** Hang up one person in a merged call. */
    fun dropFromConference(number: String) {
        call?.children?.firstOrNull { numberOf(it) == number }?.disconnect()
    }

    // ---------------- Audio routes (phone / speaker / Bluetooth / wired) ----------------

    private fun currentKind(): AudioRoute.Kind {
        if (android.os.Build.VERSION.SDK_INT >= 34) (endpoint as? android.telecom.CallEndpoint)?.let { return kindOf(it.endpointType) }
        return when (service?.callAudioState?.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioRoute.Kind.SPEAKER
            CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.Kind.BLUETOOTH
            CallAudioState.ROUTE_WIRED_HEADSET -> AudioRoute.Kind.WIRED
            CallAudioState.ROUTE_STREAMING -> AudioRoute.Kind.STREAMING
            else -> AudioRoute.Kind.PHONE
        }
    }

    private fun kindOf(type: Int): AudioRoute.Kind = when (type) {
        android.telecom.CallEndpoint.TYPE_SPEAKER -> AudioRoute.Kind.SPEAKER
        android.telecom.CallEndpoint.TYPE_BLUETOOTH -> AudioRoute.Kind.BLUETOOTH
        android.telecom.CallEndpoint.TYPE_WIRED_HEADSET -> AudioRoute.Kind.WIRED
        android.telecom.CallEndpoint.TYPE_STREAMING -> AudioRoute.Kind.STREAMING
        else -> AudioRoute.Kind.PHONE
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun refreshRoutes() {
        val list = ArrayList<AudioRoute>()
        if (android.os.Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) {
            val cur = endpoint as? android.telecom.CallEndpoint
            endpoints.filterIsInstance<android.telecom.CallEndpoint>().forEach { e ->
                val k = kindOf(e.endpointType)
                list += AudioRoute(e.identifier.toString(), when (k) {
                    AudioRoute.Kind.PHONE -> "Phone"; AudioRoute.Kind.SPEAKER -> "Speaker"; AudioRoute.Kind.WIRED -> "Wired headset"
                    else -> e.endpointName.toString().ifBlank { if (k == AudioRoute.Kind.BLUETOOTH) "Bluetooth" else "Other device" }
                }, k, cur?.identifier == e.identifier, e)
            }
        } else {
            val a = service?.callAudioState
            if (a != null) {
                val mask = a.supportedRouteMask
                if (mask and CallAudioState.ROUTE_EARPIECE != 0) list += AudioRoute("earpiece", "Phone", AudioRoute.Kind.PHONE, a.route == CallAudioState.ROUTE_EARPIECE)
                if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) list += AudioRoute("wired", "Wired headset", AudioRoute.Kind.WIRED, a.route == CallAudioState.ROUTE_WIRED_HEADSET)
                if (mask and CallAudioState.ROUTE_SPEAKER != 0) list += AudioRoute("speaker", "Speaker", AudioRoute.Kind.SPEAKER, a.route == CallAudioState.ROUTE_SPEAKER)
                if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) {
                    val devs = runCatching { a.supportedBluetoothDevices.toList() }.getOrDefault(emptyList())
                    if (devs.isEmpty()) list += AudioRoute("bt", "Bluetooth", AudioRoute.Kind.BLUETOOTH, a.route == CallAudioState.ROUTE_BLUETOOTH)
                    devs.forEach { d ->
                        val nm = runCatching { d.alias ?: d.name }.getOrNull() ?: "Bluetooth device"
                        list += AudioRoute("bt:" + d.address, nm, AudioRoute.Kind.BLUETOOTH,
                            a.route == CallAudioState.ROUTE_BLUETOOTH && runCatching { a.activeBluetoothDevice?.address == d.address }.getOrDefault(devs.size == 1), d)
                    }
                }
            }
        }
        routes.value = list
    }

    fun selectRoute(r: AudioRoute) {
        val svc = service ?: return
        if (android.os.Build.VERSION.SDK_INT >= 34 && r.ref is android.telecom.CallEndpoint) {
            svc.requestCallEndpointChange(r.ref, svc.mainExecutor, object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: android.telecom.CallEndpointException) { Hub.banner.value = "Couldn't switch to ${r.label}" }
            })
            return
        }
        when (r.kind) {
            AudioRoute.Kind.BLUETOOTH -> (r.ref as? android.bluetooth.BluetoothDevice)?.let { svc.requestBluetoothAudio(it) } ?: svc.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            AudioRoute.Kind.SPEAKER -> svc.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            AudioRoute.Kind.WIRED -> svc.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
            AudioRoute.Kind.PHONE -> svc.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            AudioRoute.Kind.STREAMING -> svc.setAudioRoute(CallAudioState.ROUTE_STREAMING)
        }
    }

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

    // Android 14+: the proper list of audio devices (each Bluetooth headset/car/earbud by name)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<android.telecom.CallEndpoint>) {
        CallManager.endpoints = availableEndpoints.toList(); CallManager.audioChanged()
    }
    override fun onCallEndpointChanged(callEndpoint: android.telecom.CallEndpoint) {
        CallManager.endpoint = callEndpoint; CallManager.audioChanged()
    }
}
