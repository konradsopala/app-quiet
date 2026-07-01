package com.booking.persistence

/**
 * Pretty-prints a [JsonValue] tree to a JSON string.
 *
 * The output is deterministic — object keys come out in insertion order
 * (LinkedHashMap-backed), arrays in list order — so snapshot files diff
 * cleanly between runs. Indentation is 2 spaces by default; pass
 * `indent = ""` to emit a compact single-line form.
 */
class JsonWriter(private val indent: String = "  ") {

    fun write(value: JsonValue): String {
        val sb = StringBuilder()
        write(sb, value, depth = 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: JsonValue, depth: Int) {
        when (value) {
            is JsonValue.JsonString  -> sb.append('"').append(escape(value.value)).append('"')
            is JsonValue.JsonNumber  -> sb.append(value.raw)
            is JsonValue.JsonBoolean -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JsonNull    -> sb.append("null")

            is JsonValue.JsonArray   -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                for ((i, item) in value.items.withIndex()) {
                    if (indent.isNotEmpty()) {
                        sb.append('\n').append(indent.repeat(depth + 1))
                    }
                    write(sb, item, depth + 1)
                    if (i < value.items.size - 1) sb.append(',')
                }
                if (indent.isNotEmpty()) sb.append('\n').append(indent.repeat(depth))
                sb.append(']')
            }

            is JsonValue.JsonObject  -> {
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                val keys = value.entries.keys.toList()
                for ((i, key) in keys.withIndex()) {
                    if (indent.isNotEmpty()) {
                        sb.append('\n').append(indent.repeat(depth + 1))
                    }
                    sb.append('"').append(escape(key)).append('"').append(':')
                    if (indent.isNotEmpty()) sb.append(' ')
                    write(sb, value.entries.getValue(key), depth + 1)
                    if (i < keys.size - 1) sb.append(',')
                }
                if (indent.isNotEmpty()) sb.append('\n').append(indent.repeat(depth))
                sb.append('}')
            }
        }
    }

    /**
     * Escape a string per RFC 8259 §7. Control characters below 0x20 are
     * always escaped; the high bit is left as-is (UTF-8 in, UTF-8 out)
     * since callers write the result to disk as UTF-8.
     */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
