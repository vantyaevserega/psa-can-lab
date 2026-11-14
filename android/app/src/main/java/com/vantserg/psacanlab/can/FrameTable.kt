package com.vantserg.psacanlab.can

import java.util.TreeMap

/** Что известно об одном идентификаторе: последние данные, период, число кадров. */
class FrameStat(val id: Int) {
    var dlc = 0
    val data = IntArray(8)

    /** Когда последний раз менялся каждый байт, мс; 0 — не менялся с первого кадра. */
    val changedAt = LongArray(8)
    var count = 0L
    var lastAt = 0L
    var period = 0L

    fun copy(): FrameStat {
        val other = FrameStat(id)
        other.dlc = dlc
        data.copyInto(other.data)
        changedAt.copyInto(other.changedAt)
        other.count = count
        other.lastAt = lastAt
        other.period = period
        return other
    }
}

/** Сводная таблица кадров по идентификаторам. Потокобезопасна: пишет поток связи, читает экран. */
class FrameTable {
    private val stats = TreeMap<Int, FrameStat>()

    @Synchronized
    fun onFrame(frame: CanFrame, now: Long) {
        val stat = stats.getOrPut(frame.id) { FrameStat(frame.id) }
        val first = stat.count == 0L
        for (i in 0 until frame.dlc) {
            if (!first && (i >= stat.dlc || stat.data[i] != frame.data[i])) stat.changedAt[i] = now
            stat.data[i] = frame.data[i]
        }
        stat.dlc = frame.dlc
        if (!first) stat.period = now - stat.lastAt
        stat.lastAt = now
        stat.count++
    }

    /** Копия таблицы, упорядоченная по идентификатору. */
    @Synchronized
    fun snapshot(): List<FrameStat> = stats.values.map { it.copy() }

    @Synchronized
    fun clear() = stats.clear()
}
