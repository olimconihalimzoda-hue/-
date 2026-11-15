package com.evdiag.profile

/**
 * Мини-вычислитель формул для профилей. Поддерживает + - * /, скобки, числа,
 * переменную i (номер повтора) и функции над байтами ответа:
 *   b(n)   байт n без знака        s8(n)   байт со знаком
 *   u16(n) big-endian без знака    s16(n)  big-endian со знаком
 *   u24(n) big-endian без знака
 * Индекс n считается от начала ответа (bytes[0] = SID ответа, напр. 0x41 или 0x61).
 * Любая ошибка (выход за границы, синтаксис) → null.
 */
class Formula(private val src: String) {
    private var pos = 0

    fun eval(bytes: IntArray, i: Int = 0): Double? = try {
        pos = 0
        val v = expr(bytes, i)
        skip()
        if (pos == src.length) v else null
    } catch (e: Exception) {
        null
    }

    private fun skip() {
        while (pos < src.length && src[pos] == ' ') pos++
    }

    private fun expr(b: IntArray, i: Int): Double {
        var v = term(b, i)
        while (true) {
            skip()
            if (pos < src.length && src[pos] == '+') { pos++; v += term(b, i) }
            else if (pos < src.length && src[pos] == '-') { pos++; v -= term(b, i) }
            else return v
        }
    }

    private fun term(b: IntArray, i: Int): Double {
        var v = factor(b, i)
        while (true) {
            skip()
            if (pos < src.length && src[pos] == '*') { pos++; v *= factor(b, i) }
            else if (pos < src.length && src[pos] == '/') { pos++; v /= factor(b, i) }
            else return v
        }
    }

    private fun factor(b: IntArray, i: Int): Double {
        skip()
        val c = src[pos]
        return when {
            c == '(' -> { pos++; val v = expr(b, i); skip(); require(src[pos] == ')'); pos++; v }
            c == '-' -> { pos++; -factor(b, i) }
            c.isDigit() || c == '.' -> {
                val st = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                src.substring(st, pos).toDouble()
            }
            c.isLetter() -> {
                val st = pos
                while (pos < src.length && src[pos].isLetterOrDigit()) pos++
                val name = src.substring(st, pos)
                skip()
                if (name == "i" && (pos >= src.length || src[pos] != '(')) return i.toDouble()
                require(src[pos] == '('); pos++
                val n = expr(b, i).toInt()
                skip(); require(src[pos] == ')'); pos++
                when (name) {
                    "b" -> b[n].toDouble()
                    "s8" -> b[n].toByte().toDouble()
                    "u16" -> ((b[n] shl 8) or b[n + 1]).toDouble()
                    "s16" -> ((b[n] shl 8) or b[n + 1]).toShort().toDouble()
                    "u24" -> ((b[n] shl 16) or (b[n + 1] shl 8) or b[n + 2]).toDouble()
                    else -> error("unknown function $name")
                }
            }
            else -> error("unexpected '$c'")
        }
    }
}
