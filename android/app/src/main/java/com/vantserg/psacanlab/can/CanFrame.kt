package com.vantserg.psacanlab.can

/** Кадр шины: идентификатор и до восьми байт данных. */
class CanFrame(val id: Int, val data: IntArray) {
    val dlc: Int get() = data.size
}

/** Разбор строки прошивки вида `<ID>\t<DLC>\t<B0>\t<B1>…` (HEX без ведущих нулей). */
object CanLineParser {
    /** null — строка не является кадром (это ответ на команду или служебное сообщение). */
    fun parse(line: String): CanFrame? {
        if (line.indexOf('\t') < 0) return null
        val tokens = line.trim().split('\t')
        if (tokens.size < 2) return null
        val id = hex(tokens[0], 3) ?: return null
        val dlc = hex(tokens[1], 1) ?: return null
        if (id > 0x7FF || dlc > 8 || tokens.size != dlc + 2) return null
        val data = IntArray(dlc)
        for (i in 0 until dlc) {
            data[i] = hex(tokens[i + 2], 2) ?: return null
        }
        return CanFrame(id, data)
    }

    private fun hex(token: String, maxLength: Int): Int? {
        if (token.isEmpty() || token.length > maxLength) return null
        var value = 0
        for (c in token) {
            val digit = Character.digit(c, 16)
            if (digit < 0) return null
            value = value * 16 + digit
        }
        return value
    }
}
