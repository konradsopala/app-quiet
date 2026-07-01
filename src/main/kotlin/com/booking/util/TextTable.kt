package com.booking.util

/**
 * A tiny fixed-width text table renderer for console output.
 *
 * Columns auto-size to their widest cell (header included), with per-column
 * alignment. The renderer is deliberately dependency-free so it can be used
 * across the reporting and analytics menus without pulling in a formatting
 * library.
 *
 * ```
 * val table = TextTable(listOf("Name", "Count"))
 *     .align(1, TextTable.Align.RIGHT)
 *     .row("Alice", "12")
 *     .row("Bob", "3")
 * println(table.render())
 * ```
 */
class TextTable(private val headers: List<String>) {

    enum class Align { LEFT, RIGHT, CENTER }

    private val rows = mutableListOf<List<String>>()
    private val alignments = MutableList(headers.size) { Align.LEFT }

    init {
        require(headers.isNotEmpty()) { "A table needs at least one column." }
    }

    /** Set the alignment for column [index]. Returns this for chaining. */
    fun align(index: Int, align: Align): TextTable {
        require(index in headers.indices) { "Column $index out of range." }
        alignments[index] = align
        return this
    }

    /** Append a row. Cells beyond the header count are ignored; short rows pad. */
    fun row(vararg cells: String): TextTable {
        val normalized = (0 until headers.size).map { cells.getOrElse(it) { "" } }
        rows.add(normalized)
        return this
    }

    fun rowCount(): Int = rows.size

    /** Compute the rendered width of each column. */
    private fun columnWidths(): IntArray {
        val widths = IntArray(headers.size) { headers[it].length }
        for (r in rows) {
            for (c in headers.indices) {
                widths[c] = maxOf(widths[c], r[c].length)
            }
        }
        return widths
    }

    private fun pad(text: String, width: Int, align: Align): String {
        val gap = (width - text.length).coerceAtLeast(0)
        return when (align) {
            Align.LEFT -> text + " ".repeat(gap)
            Align.RIGHT -> " ".repeat(gap) + text
            Align.CENTER -> {
                val left = gap / 2
                val right = gap - left
                " ".repeat(left) + text + " ".repeat(right)
            }
        }
    }

    private fun renderRow(cells: List<String>, widths: IntArray): String =
        headers.indices.joinToString(" | ") { c ->
            pad(cells[c], widths[c], alignments[c])
        }

    /** Render the full table, including a header separator, as a single string. */
    fun render(): String {
        val widths = columnWidths()
        val separator = headers.indices.joinToString("-+-") { "-".repeat(widths[it]) }
        return buildString {
            appendLine(renderRow(headers, widths))
            appendLine(separator)
            for ((i, r) in rows.withIndex()) {
                append(renderRow(r, widths))
                if (i != rows.lastIndex) appendLine()
            }
        }
    }

    override fun toString(): String = render()
}
