package com.iamfaizankhalid.epaper

import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

object NewspaperAvailability {
    private const val CHECK_START_HOUR = 9
    private val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)

    fun checksAreAllowed(date: String, now: Calendar = Calendar.getInstance()): Boolean =
        date != dateFormat.format(now.time) ||
            now.get(Calendar.HOUR_OF_DAY) >= CHECK_START_HOUR
}
