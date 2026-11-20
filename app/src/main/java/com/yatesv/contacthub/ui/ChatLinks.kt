package com.yatesv.contacthub.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Links
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.engine.LinkPreview
import com.yatesv.contacthub.phone.CallManager

/** Message text with tappable links, emails, phone numbers and map coordinates. */
@Composable
fun LinkText(text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier, onPhone: (String) -> Unit) {
    val ctx = LocalContext.current
    val links = remember(text) { Links.find(text) }
    if (links.isEmpty()) { Text(text, modifier, color = color, fontSize = fontSize); return }
    val styled: AnnotatedString = remember(text, color) {
        buildAnnotatedString {
            var i = 0
            for (l in links) {
                if (l.start > i) append(text.substring(i, l.start))
                withLink(LinkAnnotation.Clickable(l.target, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium))) {
                    if (l.kind == Links.Kind.PHONE) onPhone(l.text) else openLink(ctx, l)
                }) { append(text.substring(l.start, l.end)) }
                i = l.end
            }
            if (i < text.length) append(text.substring(i))
        }
    }
    Text(styled, modifier, color = color, fontSize = fontSize)
}

fun openLink(ctx: Context, l: Links.Link) {
    val intent = when (l.kind) {
        Links.Kind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(l.target))
        Links.Kind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse(l.target))
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(l.target))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) {
        // No maps app for geo: links → open Google Maps on the web
        if (l.kind == Links.Kind.GEO) runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(l.text.replace(" ", ""))))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else Hub.banner.value = "No app on this phone can open that link."
    }
}

fun openUrl(ctx: Context, url: String) = openLink(ctx, Links.Link(0, url.length, Links.Kind.URL, url))

/** Tap a phone number in a message: call, text, add to contacts, copy. */
@Composable
fun PhoneActions(number: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val known = Hub.contacts.cached(number)?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(known ?: Phone.pretty(number)) },
        text = {
            Column {
                if (known != null) Text(Phone.pretty(number), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ onDismiss(); CallManager.place(ctx, number) }) {
                    androidx.compose.material3.Icon(Icons.Default.Call, null); Text("  Call")
                }
                TextButton({ onDismiss(); Nav.go(Screen.Chat(numberKey(number))) }) {
                    androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.Message, null); Text("  Send a message")
                }
                if (known == null) TextButton({
                    onDismiss()
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { androidx.compose.material3.Icon(Icons.Default.PersonAdd, null); Text("  Add to contacts") }
                TextButton({ copy(ctx, number); onDismiss() }) {
                    androidx.compose.material3.Icon(Icons.Default.ContentCopy, null); Text("  Copy number")
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** Title + picture card for the first link in a message. Tap to open it. */
@Composable
fun LinkPreviewCard(url: String, fg: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var info by remember(url) { mutableStateOf(LinkPreview.cached(url)?.takeUnless { it.empty }) }
    LaunchedEffect(url) { if (info == null) info = LinkPreview.get(ctx, url) }
    AnimatedVisibility(info != null, enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec())) {
        val i = info ?: return@AnimatedVisibility
        Column(modifier.widthIn(max = 300.dp).clickable { openUrl(ctx, url) }.background(Color.Black.copy(alpha = 0.08f))) {
            if (i.image.isNotBlank()) AsyncImage(i.image, null, Modifier.fillMaxWidth().heightIn(max = 170.dp), contentScale = ContentScale.Crop)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (i.title.isNotBlank()) Text(i.title, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(i.site, color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Pick part of a message to copy. */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select text") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                Text("Long-press and drag to select, then tap Copy.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}
