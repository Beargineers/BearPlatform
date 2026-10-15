package nl.beargineers.platform.gradle

/**
 * Reads the robot's JSON answers: objects become maps, arrays lists, numbers doubles. Just enough for what the
 * robot sends, so the plugin needs no library that might clash with another plugin's.
 */
internal object Json {
    fun parse(text: String): Any? = Reader(text).run {
        val value = value()
        skipSpaces()
        if (at < text.length) fail("unexpected '${text[at]}'")
        value
    }

    private class Reader(val text: String) {
        var at = 0

        fun value(): Any? {
            skipSpaces()
            if (at >= text.length) fail("unexpected end")
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            at++
            skipSpaces()
            if (peek() == '}') return result.also { at++ }
            while (true) {
                skipSpaces()
                if (peek() != '"') fail("expected a key")
                val key = string()
                skipSpaces()
                expect(':')
                result[key] = value()
                skipSpaces()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            at++
            skipSpaces()
            if (peek() == ']') return result.also { at++ }
            while (true) {
                result += value()
                skipSpaces()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            at++
            val result = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return result.toString()
                    '\\' -> when (val e = next()) {
                        'n' -> result.append('\n')
                        't' -> result.append('\t')
                        'r' -> result.append('\r')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'u' -> {
                            if (at + 4 > text.length) fail("unexpected end")
                            result.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> result.append(e)
                    }
                    else -> result.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDoubleOrNull() ?: fail("bad number '${text.substring(start, at)}'")
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) fail("unexpected '${text[at]}'")
            at += word.length
            return value
        }

        fun skipSpaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char? = text.getOrNull(at)

        private fun next(): Char = text.getOrNull(at++) ?: fail("unexpected end")

        private fun expect(c: Char) {
            if (next() != c) fail("expected '$c'")
        }

        fun fail(message: String): Nothing = throw IllegalArgumentException("Bad JSON at $at: $message")
    }
}
