package com.fintap

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// Views are built in code: no XML inflation on the startup path, and the whole
// screen is readable top to bottom in one place.

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.text(s: String, sp: Float, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
    return t
}

// Name + price fields. Shared by the quick-add popup and the edit dialog.
class ExpenseForm(ctx: Context, name: String, paise: Long?) {
    val root = LinearLayout(ctx)
    val nameField = EditText(ctx)
    val priceField = EditText(ctx)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))

        nameField.hint = "What (e.g. coffee)"
        nameField.setText(name)
        nameField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        nameField.imeOptions = EditorInfo.IME_ACTION_NEXT
        nameField.isSingleLine = true

        priceField.hint = "Price ₹"
        if (paise != null) priceField.setText(editPaise(paise))
        priceField.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        priceField.imeOptions = EditorInfo.IME_ACTION_DONE
        priceField.isSingleLine = true

        root.addView(nameField)
        root.addView(priceField)
    }

    // Validated name and paise, or null after flagging the bad field.
    fun read(): Pair<String, Long>? {
        val paise = parsePaise(priceField.text.toString())
        if (paise == null) {
            priceField.error = "Enter an amount like 120 or 45.50"
            priceField.requestFocus()
            return null
        }
        var name = nameField.text.toString().trim()
        if (name.isEmpty()) name = "Expense"
        return Pair(name, paise)
    }
}

// Time ranges as [from, to) epoch millis in the phone's local zone. Weeks start Monday.
const val RANGE_TODAY = 0
const val RANGE_WEEK = 1
const val RANGE_MONTH = 2
const val RANGE_ALL = 3

val RANGE_TITLES = arrayOf("Today", "This week", "This month", "All expenses")

fun rangeOf(kind: Int): LongArray {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = start(today.plusDays(1))
    return when (kind) {
        RANGE_TODAY -> longArrayOf(start(today), end)
        RANGE_WEEK -> longArrayOf(start(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))), end)
        RANGE_MONTH -> longArrayOf(start(today.withDayOfMonth(1)), end)
        RANGE_ALL -> longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)
        else -> throw IllegalArgumentException("range $kind")
    }
}

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")

fun formatWhen(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(WHEN_FORMAT)
