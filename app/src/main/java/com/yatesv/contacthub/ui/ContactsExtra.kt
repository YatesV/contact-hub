package com.yatesv.contacthub.ui

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.VCard
import com.yatesv.contacthub.engine.HubSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finds the same person saved more than once and joins them into one contact (Android "link"). */
object ContactMerge {
    data class Entry(val contactId: Long, val rawIds: Set<Long>, val name: String, val numbers: Set<String>)

    /** Raw rows for every contact that has a phone number. */
    private fun entries(ctx: Context): List<Entry> {
        val byContact = LinkedHashMap<Long, Triple<MutableSet<Long>, String, MutableSet<String>>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val e = byContact.getOrPut(id) { Triple(mutableSetOf(), c.getString(2).orEmpty(), mutableSetOf()) }
                e.first += c.getLong(1)
                Phone.norm(c.getString(3).orEmpty()).takeIf { it.length >= 7 }?.let { e.third += it }
            }
        }
        return byContact.map { (id, t) -> Entry(id, t.first, t.second, t.third) }
    }

    /** Groups of contacts that share a phone number or have exactly the same name. */
    fun findDuplicates(ctx: Context): List<List<Entry>> {
        val all = entries(ctx)
        // Union-find over contacts
        val parent = all.indices.toMutableList()
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun join(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[rb] = ra }
        val firstByNumber = HashMap<String, Int>()
        val firstByName = HashMap<String, Int>()
        all.forEachIndexed { i, e ->
            e.numbers.forEach { n -> firstByNumber[n]?.let { join(it, i) } ?: firstByNumber.put(n, i) }
            val nm = e.name.trim().lowercase().replace(Regex("\\s+"), " ")
            if (nm.length >= 3 && !nm.any { it.isDigit() }) firstByName[nm]?.let { join(it, i) } ?: firstByName.put(nm, i)
        }
        return all.indices.groupBy { root(it) }.values.filter { it.size > 1 }.map { g -> g.map { all[it] } }
            .sortedBy { it.first().name.lowercase() }
    }

    /** Links every raw contact in the group so Android shows them as one person. Nothing is deleted. */
    fun merge(ctx: Context, group: List<Entry>): Boolean = runCatching {
        val raws = group.flatMap { it.rawIds }.distinct()
        if (raws.size < 2) return true
        val first = raws.first()
        val ops = ArrayList<ContentProviderOperation>()
        raws.drop(1).forEach { other ->
            ops += ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, first)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, other)
                .build()
        }
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        true
    }.getOrDefault(false)
}

@Composable
fun DuplicatesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<List<ContactMerge.Entry>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { groups = withContext(Dispatchers.IO) { runCatching { ContactMerge.findDuplicates(ctx) }.getOrDefault(emptyList()) } }
    fun mergeAll(list: List<List<ContactMerge.Entry>>) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { list.count { ContactMerge.merge(ctx, it) } }
            busy = false
            Hub.banner.value = if (ok == list.size) "Merged $ok" else "Merged $ok of ${list.size}. Some contacts couldn't be changed."
            runCatching { Hub.contacts.all() }
            reload++
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicate contacts") },
        text = {
            val g = groups
            when {
                g == null || busy -> Box0 { CircularProgressIndicator() }
                g.isEmpty() -> Text("No duplicates found. Everyone is saved once.")
                else -> Column {
                    Text("Same number or same name. Merging joins them into one contact; nothing is deleted.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(g, key = { it.first().contactId }) { grp ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    grp.forEach { e ->
                                        Text(e.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Medium)
                                        Text(e.numbers.joinToString(", ") { Phone.pretty(it) }, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton({ mergeAll(listOf(grp)) }) { Text("Merge") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            val g = groups.orEmpty()
            if (g.size > 1 && !busy) TextButton({ mergeAll(g) }) { Text("Merge all ${g.size}") } else TextButton(onDismiss) { Text("Done") }
        },
        dismissButton = { if (groups.orEmpty().size > 1) TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Box0(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { content() }

object Qr {
    fun bitmap(text: String, size: Int = 720): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

/** A QR code someone can scan with their camera to save you as a contact. */
@Composable
fun MyQrDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = Hub.settings.value
    var name by remember { mutableStateOf(s.myName) }
    var number by remember { mutableStateOf(HubSms.myNumbers(ctx).firstOrNull()?.let { Phone.pretty(it) }.orEmpty()) }
    val card = remember(name, number) {
        if (name.isBlank() && number.isBlank()) null
        else VCard.build(name.trim(), listOfNotNull(number.trim().takeIf { it.isNotEmpty() }?.let { "CELL" to it }), emptyList())
    }
    val bmp = remember(card) { card?.let { Qr.bitmap(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My contact QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                    contentAlignment = Alignment.Center) {
                    if (bmp != null) Image(bmp.asImageBitmap(), "QR code with your contact info", Modifier.fillMaxWidth())
                    else Text("Add your name or number", color = Color.Black)
                }
                Text("They point their camera at this to save you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(number, { number = it }, label = { Text("Your number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton({
                if (name.isNotBlank() && name != s.myName) Hub.settings.update { it.copy(myName = name.trim()) }
                onDismiss()
            }) { Text("Done") }
        },
    )
}
