package com.fintap

// Pure functions, no Android imports, so they run in plain JVM unit tests.

const val MAX_RUPEES_DIGITS = 10 // ₹99,99,99,99,999 — far beyond any real expense

// "120" -> 12000, "12.5" -> 1250, "1,200.05" -> 120005. Returns null on anything else.
fun parsePaise(input: String): Long? {
    val s = input.trim().replace(",", "")
    if (s.isEmpty()) return null
    val dot = s.indexOf('.')
    val whole = if (dot < 0) s else s.substring(0, dot)
    val frac = if (dot < 0) "" else s.substring(dot + 1)
    if (whole.isEmpty() && frac.isEmpty()) return null
    if (frac.length > 2 || whole.length > MAX_RUPEES_DIGITS) return null
    for (ch in whole) if (ch !in '0'..'9') return null
    for (ch in frac) if (ch !in '0'..'9') return null
    val rupees = if (whole.isEmpty()) 0L else whole.toLong()
    val paise = if (frac.isEmpty()) 0L else frac.padEnd(2, '0').toLong()
    return rupees * 100 + paise
}

// 12345650 -> "₹1,23,456.50", 500 -> "₹5". Indian digit grouping.
fun formatPaise(paise: Long): String {
    check(paise >= 0)
    val rupees = paise / 100
    val rem = paise % 100
    val r = groupIndian(rupees.toString())
    return if (rem == 0L) "₹$r" else "₹$r." + rem.toString().padStart(2, '0')
}

// Plain amount for an edit field: 1250 -> "12.50", 1200 -> "12".
fun editPaise(paise: Long): String {
    val rem = paise % 100
    return if (rem == 0L) (paise / 100).toString() else "${paise / 100}." + rem.toString().padStart(2, '0')
}

private fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val sb = StringBuilder()
    val head = digits.substring(0, digits.length - 3)
    // Head is grouped in pairs from the right.
    val first = head.length % 2
    if (first > 0) sb.append(head, 0, first)
    var i = first
    while (i < head.length) {
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(head, i, i + 2)
        i += 2
    }
    sb.append(',').append(digits, digits.length - 3, digits.length)
    return sb.toString()
}
