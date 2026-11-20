package com.yatesv.contacthub.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.engine.ActionReceiver
import kotlinx.coroutines.launch

/** Home screen widget: up to 4 favorite contacts + the auto-reply switch. */
class HubWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Hub.scope.launch {
            try { draw(ctx, mgr, ids) } finally { pending.finish() }
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, HubWidget::class.java))
            if (ids.isNotEmpty()) Hub.scope.launch { draw(ctx, mgr, ids) }
        }

        private suspend fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val priority = Hub.db.dao().allExtras().filter { it.priority }.map { it.phoneKey }
            val contacts = runCatching { Hub.contacts.all() }.getOrDefault(emptyList())
            val favs = (priority.mapNotNull { k -> contacts.firstOrNull { Phone.norm(it.number) == k } } +
                contacts.filter { it.starred }).distinctBy { it.id }.take(4)
            val views = RemoteViews(ctx.packageName, R.layout.widget_hub)
            val slots = listOf(R.id.fav0, R.id.fav1, R.id.fav2, R.id.fav3)
            slots.forEachIndexed { i, id ->
                val c = favs.getOrNull(i)
                if (c == null) views.setViewVisibility(id, View.GONE)
                else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(id, c.name.substringBefore(' '))
                    val key = Phone.convKey(listOf(c.number))
                    views.setOnClickPendingIntent(id, PendingIntent.getActivity(ctx, key.hashCode(),
                        Intent(ctx, MainActivity::class.java).putExtra("conv", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            val on = Hub.settings.value.autoReplyOn
            views.setTextViewText(R.id.autoreply, if (on) "Auto-reply ON" else "Auto-reply off")
            views.setOnClickPendingIntent(R.id.autoreply, PendingIntent.getBroadcast(ctx, 99,
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.AUTO_REPLY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}
