package com.pasq96.headunit.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

class Contact(val name: String, val number: String, val starred: Boolean)
class CallEntry(val name: String?, val number: String, val type: Int, val date: Long)

/** Bluetooth phone data: contacts are synced into ContactsContract by the PBAP client, calls go through Telecom (HFP). */
object PhoneData {
    val PERMS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)

    fun granted(ctx: Context) = PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun contacts(ctx: Context): List<Contact> = runCatching {
        val out = ArrayList<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)?.trim().orEmpty()
                if (n.isNotEmpty()) out.add(Contact(c.getString(0)?.trim().orEmpty().ifEmpty { n }, n, c.getInt(2) == 1))
            }
        }
        out
    }.getOrDefault(emptyList())

    fun recents(ctx: Context): List<CallEntry> = runCatching {
        val out = ArrayList<CallEntry>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 100) out.add(CallEntry(c.getString(0), c.getString(1).orEmpty(), c.getInt(2), c.getLong(3)))
        }
        out
    }.getOrDefault(emptyList())

    /** Places the call; the stock dialer's InCallService still draws the in-call screen. */
    fun dial(ctx: Context, number: String): Boolean = runCatching {
        ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        true
    }.getOrDefault(false)
}
