package com.booking.membership

/**
 * Minimal RFC 4180 CSV reader/writer shared by the membership importer and
 * exporter. Handles quoted fields, embedded commas/quotes/newlines and both
 * LF and CRLF record terminators.
 */
object MembershipCsv {

    /** Parse [text] into records of raw field values. Blank lines are skipped. */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var i = 0

        fun endField() {
            fields.add(if (fieldWasQuoted) field.toString() else field.toString().trim())
            field.setLength(0)
            fieldWasQuoted = false
        }

        fun endRecord() {
            endField()
            if (!(fields.size == 1 && fields[0].isEmpty())) records.add(fields)
            fields = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> field.append(c)
                c == '"' && field.isBlank() -> {
                    field.setLength(0)
                    inQuotes = true
                    fieldWasQuoted = true
                }
                c == ',' -> endField()
                c == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    endRecord()
                }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        require(!inQuotes) { "Unterminated quoted field in CSV input." }
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldWasQuoted) endRecord()
        return records
    }

    /** Quote [value] only when it needs it. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun row(vararg cells: String): String = cells.joinToString(",") { escape(it) }
}
