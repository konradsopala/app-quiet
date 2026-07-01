package com.booking.persistence

/**
 * Recursive-descent JSON parser. Produces a [JsonValue] tree mirroring
 * what [JsonWriter] emits.
 *
 * The parser is intentionally strict: it does not accept trailing
 * commas, single-quoted strings, comments, or any of the JSON5
 * extensions. Anything that round-trips through [JsonWriter] parses
 * back without loss.
 *
 * Errors surface as [JsonParseException] with the offending offset so
 * a corrupt snapshot file is diagnosable from the message alone.
 */
class JsonParser(private val input: String) {

    private var pos = 0

    class JsonParseException(message: String, val offset: Int) :
        RuntimeException("JSON parse error at offset $offset: $message")

    fun parse(): JsonValue {
        skipWs()
        val v = readValue()
        skipWs()
        if (pos != input.length) throw JsonParseException("Trailing content", pos)
        return v
    }

    private fun readValue(): JsonValue {
        skipWs()
        if (pos >= input.length) throw JsonParseException("Unexpected end of input", pos)
        return when (val c = input[pos]) {
            '"'  -> JsonValue.JsonString(readString())
            '{'  -> readObject()
            '['  -> readArray()
            't', 'f' -> readBoolean()
            'n'  -> readNull()
            '-', in '0'..'9' -> JsonValue.JsonNumber(readNumberRaw())
            else -> throw JsonParseException("Unexpected character '$c'", pos)
        }
    }

    private fun readObject(): JsonValue.JsonObject {
        expect('{')
        val entries = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') {
            pos++
            return JsonValue.JsonObject(entries)
        }
        while (true) {
            skipWs()
            if (peek() != '"') throw JsonParseException("Expected string key", pos)
            val key = readString()
            skipWs()
            expect(':')
            val value = readValue()
            entries[key] = value
            skipWs()
            when (val c = peek()) {
                ',' -> { pos++; continue }
                '}' -> { pos++; return JsonValue.JsonObject(entries) }
                else -> throw JsonParseException("Expected ',' or '}', got '$c'", pos)
            }
        }
    }

    private fun readArray(): JsonValue.JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWs()
        if (peek() == ']') {
            pos++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            items.add(readValue())
            skipWs()
            when (val c = peek()) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return JsonValue.JsonArray(items) }
                else -> throw JsonParseException("Expected ',' or ']', got '$c'", pos)
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < input.length) {
            val c = input[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= input.length) throw JsonParseException("Unterminated escape", pos)
                    when (val e = input[pos++]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/'  -> sb.append('/')
                        'b'  -> sb.append('\b')
                        'f'  -> sb.append('\u000C')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        'u'  -> {
                            if (pos + 4 > input.length) throw JsonParseException("Truncated \\u escape", pos)
                            val hex = input.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw JsonParseException("Invalid escape '\\$e'", pos - 1)
                    }
                }
                else -> sb.append(c)
            }
        }
        throw JsonParseException("Unterminated string", pos)
    }

    private fun readNumberRaw(): String {
        val start = pos
        if (peek() == '-') pos++
        while (pos < input.length && input[pos] in '0'..'9') pos++
        if (pos < input.length && input[pos] == '.') {
            pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        if (pos == start) throw JsonParseException("Empty number", pos)
        return input.substring(start, pos)
    }

    private fun readBoolean(): JsonValue.JsonBoolean {
        if (input.startsWith("true", pos)) {
            pos += 4
            return JsonValue.JsonBoolean(true)
        }
        if (input.startsWith("false", pos)) {
            pos += 5
            return JsonValue.JsonBoolean(false)
        }
        throw JsonParseException("Expected true/false", pos)
    }

    private fun readNull(): JsonValue {
        if (input.startsWith("null", pos)) {
            pos += 4
            return JsonValue.JsonNull
        }
        throw JsonParseException("Expected null", pos)
    }

    private fun expect(c: Char) {
        skipWs()
        if (pos >= input.length || input[pos] != c) {
            throw JsonParseException("Expected '$c'", pos)
        }
        pos++
    }

    private fun peek(): Char {
        skipWs()
        if (pos >= input.length) throw JsonParseException("Unexpected end of input", pos)
        return input[pos]
    }

    private fun skipWs() {
        while (pos < input.length) {
            val c = input[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
        }
    }
}
