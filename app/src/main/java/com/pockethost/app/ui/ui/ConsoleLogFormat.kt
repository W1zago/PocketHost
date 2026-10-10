package com.pockethost.app.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.pockethost.app.R

/**
 * Console log rendering: every line starts at column 0, no paragraph indent.
 * Timestamp (if present) stays inline as `HH:mm `, otherwise no prefix.
 * Wrapped lines are NOT indented — flat left edge.
 *
 * Input lines are raw `server.log` rows; nothing is dropped or reordered.
 */
object ConsoleLogFormat {

    /** Fixed 5-char column + 1 trailing space, so every line starts the same way. */
    const val TIME_COLUMN_WIDTH = 6

    private val LEADING_TIME =
        Regex("""^\s*(?:\[?\d{4}-\d{2}-\d{2}[T ])?\[?(\d{2}):(\d{2})(?::(\d{2})(?:[,.]\d{1,3})?)?\]?[\s\-.:\]|,]*""")
    private val ERROR_PATTERN =
        Regex("error|exception|failed|severe|fatal|caused by", RegexOption.IGNORE_CASE)
    private val WARN_PATTERN = Regex("warn", RegexOption.IGNORE_CASE)
    private val LEVEL_TOKEN =
        Regex("""\[(INFO|WARN|WARNING|ERROR|ERR|DEBUG|TRACE)[^\]]*]|\b(INFO|WARN|WARNING|ERROR|ERR)\b""",
            RegexOption.IGNORE_CASE)

    fun levelOf(line: String): String = when {
        ERROR_PATTERN.containsMatchIn(line) -> "ERROR"
        WARN_PATTERN.containsMatchIn(line) -> "WARN"
        else -> "INFO"
    }

    /** Splits a raw line into (time "HH:mm" or "", message without the time prefix). */
    fun splitTime(raw: String): Pair<String, String> {
        val m = LEADING_TIME.find(raw) ?: return "" to raw
        val hh = m.groupValues[1].toIntOrNull() ?: return "" to raw
        val mm = m.groupValues[2].toIntOrNull() ?: return "" to raw
        if (hh > 23 || mm > 59) return "" to raw
        val time = "%02d:%02d".format(hh, mm)
        val rest = raw.substring(m.value.length)
        return time to rest
    }

    /**
     * Formats lines into a single [Spanned] with inline timestamp and level
     * coloring. [hangingIndentPx] is kept for API compat but ignored —
     * no hanging indent by design (flat left edge).
     */
    fun formatLines(context: Context, lines: List<String>, hangingIndentPx: Int): Spanned {
        val out = SpannableStringBuilder()
        val tsColor = ContextCompat.getColor(context, R.color.log_timestamp)
        val bodyColor = ContextCompat.getColor(context, R.color.on_surface)
        val infoColor = ContextCompat.getColor(context, R.color.secondary)
        val warnColor = ContextCompat.getColor(context, R.color.warning)
        val errorColor = ContextCompat.getColor(context, R.color.rose)
        lines.forEachIndexed { index, raw ->
            if (index > 0) out.append("\n")
            val lineStart = out.length
            val (time, message) = splitTime(raw)
            // No paragraph indent: time inline or nothing.
            val prefix = if (time.isEmpty()) "" else "$time "
            out.append(prefix)
            if (prefix.isNotEmpty()) {
                out.setSpan(
                    ForegroundColorSpan(tsColor), lineStart, lineStart + prefix.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val msgStart = out.length
            out.append(if (message.isEmpty()) " " else withBreakOpportunities(message))
            out.setSpan(
                ForegroundColorSpan(bodyColor), msgStart, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val levelColor = when (levelOf(raw)) {
                "ERROR" -> errorColor
                "WARN" -> warnColor
                else -> infoColor
            }
            // Color only the inline level tag; the rest of the message stays neutral.
            // NOTE: search in the displayed text (with break opportunities),
            // not the raw message, so spans land on the right offsets.
            val displayed = out.substring(msgStart, out.length)
            LEVEL_TOKEN.find(displayed)?.let { token ->
                val s = msgStart + token.range.first
                val e = msgStart + token.range.last + 1
                out.setSpan(ForegroundColorSpan(levelColor), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // No hanging indent — wrapped lines start at column 0 (flat left edge).
        }
        return out
    }

    /**
     * Invisible break opportunities after '/', '=' and ':' so long paths
     * (e.g. /data/user/0/.../libproot_loader.so) wrap at boundaries
     * instead of mid-token. Raw log in the buffer stays untouched —
     * this affects only the displayed span, copy/share keep the original.
     */
    private fun withBreakOpportunities(message: String): String {
        if (message.length < 40) return message
        if (message.indexOf('/') < 0 && message.indexOf('=') < 0 && message.indexOf(':') < 0) {
            return message
        }
        val sb = StringBuilder(message.length + 16)
        for (c in message) {
            sb.append(c)
            if (c == '/' || c == '=' || c == ':') sb.append('\u200B')
        }
        return sb.toString()
    }
}
