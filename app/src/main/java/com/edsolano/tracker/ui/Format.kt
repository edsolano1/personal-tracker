package com.edsolano.tracker.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val usd = NumberFormat.getCurrencyInstance(Locale.US)
    private val num = NumberFormat.getIntegerInstance(Locale.US)

    fun time(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFmt)
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun dateTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).let { "${it.format(dateFmt)} ${it.format(timeFmt)}" }
    fun money(v: Double): String = usd.format(v)
    fun int(v: Int): String = num.format(v)
}
