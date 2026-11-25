package com.pasq96.headunit.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pasq96.headunit.R
import com.pasq96.headunit.phone.CallEntry
import com.pasq96.headunit.phone.Contact
import com.pasq96.headunit.phone.PhoneData
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PhoneTab(val label: Int) { KEYPAD(R.string.phone_keypad), RECENTS(R.string.phone_recents), CONTACTS(R.string.phone_contacts) }

/** Bluetooth phone: keypad, call log and contacts (PBAP sync) in one full-screen app; calls ride on HFP through Telecom. */
@Composable
internal fun PhoneScreen(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(PhoneTab.KEYPAD) }
    var number by remember { mutableStateOf("") }
    var starredOnly by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var recents by remember { mutableStateOf<List<CallEntry>>(emptyList()) }
    val ok = remember(vm.phoneTick) { PhoneData.granted(ctx) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(ok, tab) {
        if (ok) withContext(Dispatchers.IO) { contacts = PhoneData.contacts(ctx); recents = PhoneData.recents(ctx) }
    }
    val call = { n: String -> if (n.isNotBlank()) failed = !PhoneData.dial(ctx, n.trim()) }

    NativeFrame(vm, str(R.string.app_phone), actions = {
        PhoneTab.entries.forEach { t -> Chip(str(t.label), tab == t, accent) { tab = t } }
    }) {
        if (!ok) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Txt(str(R.string.phone_need_perm), 17, TextLo, align = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(bottom = 16.dp))
                Chip(str(R.string.phone_grant), true, accent, icon = G.PHONE) { vm.requestPhone() }
            }
            return@NativeFrame
        }
        if (failed) Txt(str(R.string.phone_call_fail), 14, Warn, modifier = Modifier.padding(top = 6.dp))
        when (tab) {
            PhoneTab.KEYPAD -> Keypad(number, accent, { number = it }, { call(number) })
            PhoneTab.RECENTS -> if (recents.isEmpty()) Empty(str(R.string.phone_no_recents)) else LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
                items(recents) { e -> RecentRow(e, accent) { call(e.number) } }
            }
            PhoneTab.CONTACTS -> Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(str(R.string.phone_favorites), starredOnly, accent) { starredOnly = !starredOnly }
                    Txt(str(R.string.phone_count, contacts.size), 14, TextLo)
                }
                val shown = if (starredOnly) contacts.filter { it.starred } else contacts
                if (shown.isEmpty()) Empty(str(R.string.phone_no_contacts), str(R.string.phone_sync)) {
                    ctx.packageManager.getLaunchIntentForPackage("com.imotor.contacts")?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(shown) { c -> ContactRow(c.name, c.number, c.starred, accent) { call(c.number) } }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Txt(text, 16, TextLo, align = TextAlign.Center, maxLines = 3)
        if (action != null) Box(Modifier.padding(top = 14.dp)) { Chip(action, false, Color.Unspecified, onClick = onAction) }
    }
}

@Composable
private fun ContactRow(name: String, sub: String, star: Boolean, accent: Color, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onCall).padding(horizontal = 8.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Surface2), Alignment.Center) { Txt(name.take(1).uppercase(), 18, if (star) accent else TextHi, bold = true) }
        Column(Modifier.weight(1f)) { Txt(name, 18, TextHi); Txt(sub, 13, TextLo) }
        Glyph(G.PHONE, accent, 26.dp)
    }
}

@Composable
private fun RecentRow(e: CallEntry, accent: Color, onCall: () -> Unit) {
    // CallLog.Calls types: 1 incoming, 2 outgoing, 3 missed
    val missed = e.type == 3 || e.type == 5
    val dir = when (e.type) { 1 -> "↙"; 2 -> "↗"; else -> "✕" }
    val whenTxt = remember(e.date) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.date)) }
    Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onCall).padding(horizontal = 8.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        Txt(dir, 20, if (missed) Warn else accent, bold = true, modifier = Modifier.size(28.dp), align = TextAlign.Center)
        Column(Modifier.weight(1f)) { Txt(e.name?.takeIf { it.isNotBlank() } ?: e.number, 18, if (missed) Warn else TextHi); Txt("${e.number}  ·  $whenTxt", 13, TextLo) }
        Glyph(G.PHONE, accent, 26.dp)
    }
}

@Composable
private fun Keypad(number: String, accent: Color, onChange: (String) -> Unit, onCall: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Row(Modifier.fillMaxSize().padding(top = 10.dp), Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), Arrangement.Center) {
            Row(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(16.dp)).background(Surface2).padding(horizontal = 20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Txt(number.ifEmpty { "–" }, 34, if (number.isEmpty()) TextLo else TextHi, bold = true) }
                Box(Modifier.clickable { onChange(number.dropLast(1)) }.padding(8.dp)) { Glyph(G.PREV, TextHi, 30.dp) }
            }
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(72.dp).clip(RoundedCornerShape(36.dp)).background(accent).clickable(onClick = onCall), Alignment.Center) {
                Glyph(G.PHONE, onAccent(accent), 36.dp)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(80.dp), Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Surface2).clickable { onChange(number + k) }, Alignment.Center) { Txt(k, 30, TextHi, bold = true) }
                    }
                }
            }
        }
    }
}
