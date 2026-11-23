package com.yatesv.contacthub.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.ui.MainActivity

object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_PRIORITY = "priority"
    const val CH_CALLS = "calls"
    const val CH_STATUS = "status"
    const val KEY_REPLY = "reply_text"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_PRIORITY, "Priority contacts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Messages from priority contacts. These can come through Do Not Disturb."
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "App status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    /** A channel per contact with a custom sound. Android only allows the sound to be set when the channel is made. */
    fun channelFor(ctx: Context, key: String, soundUri: String, priority: Boolean): String {
        if (soundUri.isBlank()) return if (priority) CH_PRIORITY else CH_MESSAGES
        val id = "c_" + (key + soundUri).hashCode().toUInt().toString(16)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "Custom: " + displayName(key), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(Uri.parse(soundUri), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                if (priority) setBypassDnd(true)
                group = null
            })
        }
        return id
    }

    fun displayName(key: String): String {
        if (Phone.isNameKey(key)) return Phone.nameOf(key)
        return key.split(',').joinToString(", ") { Hub.contacts.cached(it)?.name ?: Phone.pretty(it) }
    }

    fun openIntent(ctx: Context, key: String): PendingIntent =
        PendingIntent.getActivity(ctx, key.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("conv", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * A long-lived conversation shortcut per chat: puts Orbit's chats in Android's "Conversations"
     * notification section with the contact's photo, and lets them become bubbles.
     */
    private fun conversationShortcut(ctx: Context, key: String, title: String, isGroup: Boolean): String {
        val id = "conv_" + Integer.toHexString(key.hashCode())
        runCatching {
            val photo = if (!isGroup) Phone.addresses(key).firstOrNull()?.let { Hub.contacts.cached(it)?.photo } else null
            val icon = photo?.let { p ->
                runCatching { ctx.contentResolver.openInputStream(android.net.Uri.parse(p))?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }?.let { androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(it) }
                ?: androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
            val person = Person.Builder().setName(title).setKey(key).setIcon(icon).build()
            val sc = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(title.ifBlank { "Chat" }).setLongLived(true).setIcon(icon)
                .apply { if (!isGroup) setPerson(person) }
                .setLocusId(androidx.core.content.LocusIdCompat(id))
                .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("conv", key))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(ctx, sc)
        }.onFailure { com.yatesv.contacthub.data.HubLog.w("Notify", "shortcut failed", it) }
        return id
    }

    suspend fun showMessage(ctx: Context, key: String, title: String, isGroup: Boolean, lines: List<Pair<String, String>>, mention: Boolean = false,
                            image: android.net.Uri? = null) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val s = Hub.settings.value
        val meta = Hub.db.dao().meta(key)
        val extra = if (!Phone.isGroupKey(key)) Hub.db.dao().extra(key) else null
        if (meta?.muted == true) return
        val hidden = meta?.hidden == true || s.hidePreviews
        // A chat's own sound wins, then a contact group's sound
        val groupSound = if (!Phone.isGroupKey(key)) Hub.orbit.groupsOf(key).firstOrNull { it.soundUri.isNotBlank() }?.soundUri.orEmpty() else ""
        val channel = channelFor(ctx, key, meta?.soundUri.orEmpty().ifBlank { groupSound }, extra?.priority == true || mention)

        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_tile)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, key))
            .setColor(s.accent)
            // Feeds the unread number on the app icon badge
            .setNumber(lines.count { it.first != "You" }.coerceAtLeast(1))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)

        if (hidden) {
            b.setContentTitle("New message").setContentText("Open Orbit to read it")
        } else {
            val me = Person.Builder().setName("You").build()
            val style = NotificationCompat.MessagingStyle(me)
                .setConversationTitle(if (isGroup) title else null)
                .setGroupConversation(isGroup)
            val now = System.currentTimeMillis()
            lines.forEachIndexed { i, (who, text) ->
                val p = if (who == "You") null else Person.Builder().setName(who).build()
                val msg = NotificationCompat.MessagingStyle.Message(text, now - (lines.size - i), p)
                // A received photo shows in the notification itself
                if (image != null && i == lines.lastIndex) msg.setData("image/jpeg", image)
                style.addMessage(msg)
            }
            b.setStyle(style)
            // Conversation: own section, contact photo, and (if you allow it) a floating bubble
            val sc = conversationShortcut(ctx, key, title, isGroup)
            b.setShortcutId(sc).setLocusId(androidx.core.content.LocusIdCompat(sc))
            if (!s.appLock && meta?.hidden != true) runCatching {
                val bubbleIntent = PendingIntent.getActivity(ctx, ("b" + key).hashCode(),
                    Intent(ctx, com.yatesv.contacthub.ui.BubbleActivity::class.java).putExtra("conv", key).setAction("bubble:$key"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                b.setBubbleMetadata(NotificationCompat.BubbleMetadata.Builder(bubbleIntent,
                    androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setDesiredHeight(640).setSuppressNotification(Hub.openConv == key).build())
            }
            // One-time codes: copy straight from the notification
            com.yatesv.contacthub.data.Otp.find(lines.lastOrNull()?.second.orEmpty())?.let { code ->
                val copy = PendingIntent.getBroadcast(ctx, ("c" + key).hashCode(),
                    Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.COPY).putExtra("conv", key).putExtra("code", code),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_tile, "Copy $code", copy).build())
            }

            val replyIntent = PendingIntent.getBroadcast(ctx, ("r" + key).hashCode(),
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.REPLY).putExtra("conv", key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            // Quick-reply choices (also shown on a Galaxy Watch), from on-device Smart Reply
            val choices = if (s.smartReplies) runCatching {
                kotlinx.coroutines.withTimeoutOrNull(1500) {
                    Ai.onDeviceReplies(lines.mapIndexed { i, (who, text) ->
                        com.yatesv.contacthub.data.Msg("n$i", text, now - (lines.size - i) * 1000L, who == "You", sender = who)
                    })
                }
            }.getOrNull().orEmpty() else emptyList()
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_tile, "Reply", replyIntent)
                .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").apply { if (choices.isNotEmpty()) setChoices(choices.toTypedArray()) }.build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(true).build())
        }
        val readIntent = PendingIntent.getBroadcast(ctx, ("m" + key).hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.READ).putExtra("conv", key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_tile, "Mark as read", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())

        runCatching { NotificationManagerCompat.from(ctx).notify(key.hashCode(), b.build()) }
    }

    /** Unread lines per chat for Orbit-mode notifications (cleared when the chat is opened or read). */
    val recent = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, String>>>()

    fun cancel(ctx: Context, key: String) { recent.remove(key); NotificationManagerCompat.from(ctx).cancel(key.hashCode()) }
    fun cancelStatus(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    fun status(ctx: Context, id: Int, title: String, text: String) {
        val b = NotificationCompat.Builder(ctx, CH_STATUS).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }
}
