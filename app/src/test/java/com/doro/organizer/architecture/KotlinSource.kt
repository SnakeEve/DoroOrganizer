package com.doro.organizer.architecture

/**
 * A Kotlin file as the boundary rules read it (architecture.md § Boundary test).
 *
 * [code] has the length and line breaks of [text], with comments, string-literal text and char literals blanked
 * out, so a match in [code] is real code and its offset maps to the original line. The expressions of templates
 * (`${…}`) stay in [code] because they are code; their `${` and `}` delimiters are blanked, so brace matching sees
 * only real blocks. [literals] holds the text of every string literal, for the SQL-text rule.
 *
 * @param path the path reported in violations, relative to the app module
 * @param sourceSet the source set of the file, such as `main`, `debug` or `release`
 */
class KotlinSource(val path: String, val sourceSet: String, val text: String) {
    private val lineStarts = lineStartsOf(text)
    val code: String
    val literals: List<Literal>

    init {
        val (stripped, found) = Lexer(text).run()
        code = stripped
        literals = found.map { (offset, content) -> Literal(lineAt(offset), content) }
    }

    /** The `package` of the file, or null when it has none. */
    val packageName: String? = PACKAGE.find(code)?.groupValues?.get(1)

    fun lineAt(offset: Int): Int {
        val index = lineStarts.binarySearch(offset)
        return if (index >= 0) index + 1 else -index - 1
    }

    fun violation(offset: Int, message: String) = Violation(path, lineAt(offset), message)

    class Literal(val line: Int, val text: String)

    private companion object {
        val PACKAGE = Regex("""^[ \t]*package\s+([\w.]+)""", RegexOption.MULTILINE)

        fun lineStartsOf(text: String): IntArray {
            val starts = mutableListOf(0)
            text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
            return starts.toIntArray()
        }
    }
}

class Violation(val path: String, val line: Int, val message: String) {
    override fun toString() = "$path:$line: $message"
}

/** One pass over the text with a stack of open string literals and the template expressions inside them. */
private class Lexer(private val text: String) {
    private val out = StringBuilder(text)
    private val literals = mutableListOf<Pair<Int, String>>()
    private val frames = ArrayDeque<Frame>()
    private var i = 0

    private sealed interface Frame
    private class StringFrame(val raw: Boolean, val start: Int) : Frame {
        val content = StringBuilder()
    }
    private class TemplateFrame(var braces: Int = 0) : Frame

    fun run(): Pair<String, List<Pair<Int, String>>> {
        while (i < text.length) {
            when (val frame = frames.lastOrNull()) {
                is StringFrame -> stringStep(frame)
                is TemplateFrame -> codeStep(frame)
                null -> codeStep(null)
            }
        }
        return out.toString() to literals
    }

    private fun codeStep(template: TemplateFrame?) {
        val c = text[i]
        when {
            text.startsWith("//", i) -> blankUntil(text.indexOf('\n', i).let { if (it < 0) text.length else it })
            text.startsWith("/*", i) -> blankUntil(blockCommentEnd())
            text.startsWith("\"\"\"", i) -> { frames.addLast(StringFrame(raw = true, start = i)); i += 3 }
            c == '"' -> { frames.addLast(StringFrame(raw = false, start = i)); i++ }
            c == '\'' -> blankUntil(charLiteralEnd())
            template != null && c == '{' -> { template.braces++; i++ }
            template != null && c == '}' -> {
                if (template.braces == 0) {
                    blank(i)
                    frames.removeLast()
                } else {
                    template.braces--
                }
                i++
            }
            else -> i++
        }
    }

    private fun stringStep(frame: StringFrame) {
        val c = text[i]
        when {
            !frame.raw && c == '\\' && i + 1 < text.length -> {
                frame.content.append(c).append(text[i + 1])
                blank(i); blank(i + 1); i += 2
            }
            frame.raw && text.startsWith("\"\"\"", i) -> {
                // A raw string ends at the last quote of a run of three or more; the extra quotes are content.
                var run = 0
                while (i + run < text.length && text[i + run] == '"') run++
                repeat(run - 3) { frame.content.append('"'); blank(i + it) }
                i += run
                closeString(frame)
            }
            !frame.raw && (c == '"' || c == '\n') -> { i++; closeString(frame) } // a newline ends a broken literal
            c == '$' && text.startsWith("\${", i) -> {
                frame.content.append("\${}")
                frames.addLast(TemplateFrame())
                blank(i); blank(i + 1); i += 2
            }
            else -> { frame.content.append(c); blank(i); i++ }
        }
    }

    private fun closeString(frame: StringFrame) {
        frames.removeLast()
        literals += frame.start to frame.content.toString()
    }

    private fun blockCommentEnd(): Int {
        var depth = 0
        var j = i
        while (j < text.length) {
            when {
                text.startsWith("/*", j) -> { depth++; j += 2 }
                text.startsWith("*/", j) -> { depth--; j += 2; if (depth == 0) return j }
                else -> j++
            }
        }
        return text.length
    }

    private fun charLiteralEnd(): Int {
        var j = i + 1
        while (j < text.length && text[j] != '\'' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
        return minOf(j + 1, text.length)
    }

    private fun blankUntil(end: Int) {
        while (i < end) { blank(i); i++ }
    }

    private fun blank(index: Int) {
        if (out[index] != '\n') out.setCharAt(index, ' ')
    }
}
