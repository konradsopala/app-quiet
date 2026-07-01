package com.booking.persistence

/**
 * Tree representation of a JSON document. Sealed so [JsonWriter] and
 * [JsonParser] both stay exhaustive.
 *
 * The booking system has no external dependencies; rolling our own JSON
 * keeps it that way and gives us full control over date/time
 * formatting, ordering, and error reporting. The shape is the standard
 * JSON-RFC subset:
 *
 *   * Numbers are kept as a `String` to preserve precision and avoid the
 *     int-vs-double dance — [JsonNumber.toInt] / [JsonNumber.toDouble]
 *     parse on demand.
 *   * Object keys preserve insertion order so saved snapshots diff
 *     cleanly across runs.
 */
sealed class JsonValue {

    data class JsonString(val value: String) : JsonValue()

    /** Stored as text so we don't lose precision; parse on demand. */
    data class JsonNumber(val raw: String) : JsonValue() {
        constructor(value: Int) : this(value.toString())
        constructor(value: Long) : this(value.toString())
        constructor(value: Double) : this(value.toString())

        fun toInt(): Int = raw.toInt()
        fun toLong(): Long = raw.toLong()
        fun toDouble(): Double = raw.toDouble()
    }

    data class JsonBoolean(val value: Boolean) : JsonValue()

    object JsonNull : JsonValue() {
        override fun toString(): String = "JsonNull"
    }

    data class JsonArray(val items: List<JsonValue>) : JsonValue() {
        operator fun get(index: Int): JsonValue = items[index]
        val size: Int get() = items.size
    }

    data class JsonObject(val entries: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]

        /** Returns the string at [key] or throws — preferred over chained casts in encoders. */
        fun string(key: String): String {
            val v = entries[key] ?: error("Missing key '$key'")
            return (v as? JsonString)?.value ?: error("Expected string at '$key', got $v")
        }

        /** Returns the string at [key] or null when missing/JsonNull. */
        fun stringOrNull(key: String): String? {
            val v = entries[key] ?: return null
            if (v is JsonNull) return null
            return (v as? JsonString)?.value ?: error("Expected string-or-null at '$key', got $v")
        }

        fun int(key: String): Int = (entries[key] as? JsonNumber)?.toInt()
            ?: error("Expected number at '$key'")

        fun double(key: String): Double = (entries[key] as? JsonNumber)?.toDouble()
            ?: error("Expected number at '$key'")

        fun bool(key: String): Boolean = (entries[key] as? JsonBoolean)?.value
            ?: error("Expected boolean at '$key'")

        fun array(key: String): JsonArray = entries[key] as? JsonArray
            ?: error("Expected array at '$key'")

        fun obj(key: String): JsonObject = entries[key] as? JsonObject
            ?: error("Expected object at '$key'")
    }

    companion object {
        /** Build a [JsonObject] from a vararg of pairs, preserving order. */
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject =
            JsonObject(LinkedHashMap<String, JsonValue>().apply { pairs.forEach { put(it.first, it.second) } })

        fun arr(items: List<JsonValue>): JsonArray = JsonArray(items)

        fun stringOrNull(value: String?): JsonValue = value?.let { JsonString(it) } ?: JsonNull
    }
}
