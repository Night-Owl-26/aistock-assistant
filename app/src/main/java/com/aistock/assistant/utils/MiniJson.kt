package com.aistock.assistant.utils

/**
 * A small, strict JSON reader for model replies (no dependency on android's org.json, so it runs in plain unit tests).
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Double, plus String, Boolean and null.
 */
object MiniJson {
    class Bad(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw Bad("trailing text at ${p.i}")
        return v
    }

    /** The first complete JSON object in a reply that may wrap it in prose or ``` fences; null if there is none. */
    fun firstObject(reply: String): Map<String, Any?>? {
        var start = reply.indexOf('{')
        while (start >= 0) {
            val end = matchingBrace(reply, start)
            if (end > start) {
                val v = try { parse(reply.substring(start, end + 1)) } catch (e: Bad) { null }
                @Suppress("UNCHECKED_CAST")
                if (v is Map<*, *>) return v as Map<String, Any?>
            }
            start = reply.indexOf('{', start + 1)
        }
        return null
    }

    private fun matchingBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            if (depth > 40) throw Bad("too deep")
            if (i >= s.length) throw Bad("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Bad("unexpected '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad("bad literal at $i")
            i += word.length
            return v
        }
        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw Bad("bad number at $st")
        }
        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw Bad("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw Bad("bad unicode escape"))
                                i += 4
                            }
                            else -> throw Bad("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad("expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad("expected ':' at $i")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw Bad("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw Bad("expected ',' or '}' at ${i - 1}")
                }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                if (i >= s.length) throw Bad("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw Bad("expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }
}
