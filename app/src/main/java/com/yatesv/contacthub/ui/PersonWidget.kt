package com.yatesv.contacthub.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.ContactInfo
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.phone.CallManager
import kotlinx.coroutines.launch

/** Home screen widget for one person: photo, name, your latest message, tap to text, call button. */
class PersonWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = goAsync()
        Hub.scope.launch { try { ids.forEach { draw(ctx, mgr, it) } } finally { p.finish() } }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) { ids.forEach { Hub.orbit.setWidgetNumber(it, null) } }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == CALL) intent.getStringExtra("n")?.let { CallManager.place(ctx, it) } else super.onReceive(ctx, intent)
    }

    companion object {
        private const val CALL = "com.yatesv.contacthub.WIDGET_CALL"

        /** Redraw every person widget (after new messages). */
        fun refreshAll(ctx: Context) {
            Hub.scope.launch {
                val mgr = AppWidgetManager.getInstance(ctx)
                mgr.getAppWidgetIds(ComponentName(ctx, PersonWidget::class.java)).forEach { runCatching { draw(ctx, mgr, it) } }
            }
        }

        suspend fun draw(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val number = Hub.orbit.widgetNumber(id) ?: return
            val c = Hub.contacts.cached(number) ?: Hub.contacts.all().firstOrNull { Phone.norm(it.number) == Phone.norm(number) }
            val name = c?.name ?: Phone.pretty(number)
            val key = Phone.convKey(listOf(number))
            val thread = Hub.sms.threadIdFor(key)
            val sms = Hub.sms.messages(thread, limit = 1).firstOrNull()
            val local = Hub.db.dao().local(key).lastOrNull()
            val last = if (local != null && local.date > (sms?.date ?: 0)) (if (local.outgoing) "You: " else "") + local.body
                else sms?.let { (if (it.outgoing) "You: " else "") + it.body.ifBlank { "📷 Photo" } } ?: "Tap to text"
            val v = RemoteViews(ctx.packageName, R.layout.widget_person)
            v.setTextViewText(R.id.name, name)
            v.setTextViewText(R.id.last, last.take(120))
            v.setImageViewBitmap(R.id.photo, avatar(ctx, name, c?.photo))
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, id,
                Intent(ctx, MainActivity::class.java).putExtra("conv", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.call, PendingIntent.getBroadcast(ctx, id + 100_000,
                Intent(ctx, PersonWidget::class.java).setAction(CALL).putExtra("n", number), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(id, v)
        }

        /** Round photo, or initials on their color. */
        private fun avatar(ctx: Context, name: String, photo: String?): Bitmap {
            val size = (48 * ctx.resources.displayMetrics.density).toInt()
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val src = photo?.let { runCatching { ctx.contentResolver.openInputStream(Uri.parse(it))?.use { s -> android.graphics.BitmapFactory.decodeStream(s) } }.getOrNull() }
            if (src != null) {
                val scaled = Bitmap.createScaledBitmap(src, size, size, true)
                cv.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
            } else {
                val bg = memberColor(name)
                cv.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb((bg.alpha * 255).toInt(), (bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt())
                })
                val initials = name.split(' ').filter { it.isNotBlank() && it.first().isLetter() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "#" }
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = size * 0.38f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                cv.drawText(initials, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
            }
            return out
        }
    }
}

/** Shown when you add the widget: pick who it's for. */
class PersonWidgetSetup : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            HubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var list by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
                    var q by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) { list = Hub.contacts.all().distinctBy { it.id }.sortedWith(compareByDescending<ContactInfo> { it.starred }.thenBy { it.name }) }
                    Column(Modifier.statusBarsPadding()) {
                        Text("Who is this widget for?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search") }, singleLine = true)
                        LazyColumn {
                            items(list.filter { q.isBlank() || it.name.contains(q, true) }) { c ->
                                Row(Modifier.fillMaxWidth().clickable { pick(id, c.number) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(c.name, c.photo, 40.dp); Spacer(Modifier.width(12.dp)); Text(c.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(id: Int, number: String) {
        Hub.orbit.setWidgetNumber(id, number)
        Hub.scope.launch {
            PersonWidget.draw(this@PersonWidgetSetup, AppWidgetManager.getInstance(this@PersonWidgetSetup), id)
            runOnUiThread { setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish() }
        }
    }
}
