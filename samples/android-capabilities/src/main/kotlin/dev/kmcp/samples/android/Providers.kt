package dev.kmcp.samples.android

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract

/** A contact row returned by [ContactsProvider.search]. */
data class Contact(val name: String, val phone: String?)

/** A calendar event row returned by [CalendarProvider.upcomingEvents]. */
data class CalendarEvent(val title: String, val startEpochMillis: Long)

/** Read-only access to the device contact list. */
interface ContactsProvider {
    fun search(query: String, limit: Int): List<Contact>
}

/** Read-only access to the device calendar. */
interface CalendarProvider {
    fun upcomingEvents(days: Int): List<CalendarEvent>
}

/** Posts a local notification on the device. */
fun interface Notifier {
    fun post(title: String, body: String)
}

/** [ContactsProvider] backed by `ContactsContract` via the content resolver. */
class AndroidContactsProvider(private val context: Context) : ContactsProvider {
    override fun search(query: String, limit: Int): List<Contact> {
        val results = mutableListOf<Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$query%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < limit) {
                results += Contact(
                    name = cursor.getString(0) ?: continue,
                    phone = cursor.getString(1),
                )
            }
        }
        return results
    }
}

/** [CalendarProvider] backed by `CalendarContract` via the content resolver. */
class AndroidCalendarProvider(private val context: Context) : CalendarProvider {
    override fun upcomingEvents(days: Int): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60L * 60L * 1000L
        val results = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
        )
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            CalendarContract.Events.DTSTART + " >= ? AND " +
                CalendarContract.Events.DTSTART + " <= ?",
            arrayOf(now.toString(), end.toString()),
            CalendarContract.Events.DTSTART + " ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                results += CalendarEvent(
                    title = cursor.getString(0) ?: "(untitled)",
                    startEpochMillis = cursor.getLong(1),
                )
            }
        }
        return results
    }
}
