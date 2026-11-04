package com.netmonitor.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers. Digits are always Western (0-9), like the web version. */
object Fmt {
    private val arLatin: Locale = Locale.forLanguageTag("ar-u-nu-latn")

    fun toWestern(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun time(ts: Long = System.currentTimeMillis()): String =
        toWestern(SimpleDateFormat("hh:mm:ss a", arLatin).format(Date(ts)))

    fun date(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy/M/d", Locale.US).format(Date(ts))

    fun fileDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun duration(secIn: Long): String {
        val sec = maxOf(0L, secIn)
        if (sec < 60) return "$sec ث"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val r = sec % 60
        if (h > 0) return "$h س $m د $r ث"
        return if (r > 0) "$m د $r ث" else "$m د"
    }
}
