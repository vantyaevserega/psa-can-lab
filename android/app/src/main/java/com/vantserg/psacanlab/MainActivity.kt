package com.vantserg.psacanlab

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.vantserg.psacanlab.can.CanLineParser
import com.vantserg.psacanlab.can.FrameStat
import com.vantserg.psacanlab.can.FrameTable
import com.vantserg.psacanlab.can.InfoMessages
import com.vantserg.psacanlab.can.KnownFrames
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Пульт адаптера PSA CAN Lab: выбор режима (шина или стенд), таблица кадров
 * с подсветкой изменившихся байтов и записью в файл, управление эмуляцией на стенде.
 */
class MainActivity : Activity(), LabLink.Listener {

    private enum class Mode { IDLE, SNIFF, BENCH }

    /** Ползунок стенда: команда прошивки, смещение значения и подпись. */
    private class Slider(val command: String, val offset: Int, val labelRes: Int, val label: TextView, val bar: SeekBar)

    private val ui = Handler(Looper.getMainLooper())
    private val table = FrameTable()
    private val logLines = ArrayDeque<String>()
    private lateinit var link: LabLink

    private lateinit var linkStatus: TextView
    private lateinit var idlePanel: View
    private lateinit var sniffPanel: View
    private lateinit var benchPanel: View
    private lateinit var sniffTable: TextView
    private lateinit var benchTable: TextView
    private lateinit var filterView: EditText
    private lateinit var pauseButton: Button
    private lateinit var recordBox: CheckBox
    private lateinit var lostView: TextView
    private lateinit var logView: TextView
    private lateinit var modeButtons: Map<Mode, Button>

    private val switches = HashMap<String, Switch>()
    private val sliders = HashMap<String, Slider>()

    private var mode = Mode.IDLE
    private var paused = false

    /** true, пока элементы стенда обновляются по ответу прошивки, — команды в это время не шлются. */
    private var syncing = false

    @Volatile
    private var recorder: BufferedWriter? = null
    private var recordFile: File? = null
    private var recordStarted = 0L

    private val refresh = object : Runnable {
        override fun run() {
            renderTables()
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        linkStatus = findViewById(R.id.link_status)
        idlePanel = findViewById(R.id.idle_panel)
        sniffPanel = findViewById(R.id.sniff_panel)
        benchPanel = findViewById(R.id.bench_panel)
        sniffTable = findViewById(R.id.sniff_table)
        filterView = findViewById(R.id.filter)
        pauseButton = findViewById(R.id.pause)
        recordBox = findViewById(R.id.record)
        lostView = findViewById(R.id.lost)
        logView = findViewById(R.id.log)
        val idleButton: Button = findViewById(R.id.mode_idle)
        val sniffButton: Button = findViewById(R.id.mode_sniff)
        val benchButton: Button = findViewById(R.id.mode_bench)
        modeButtons = mapOf(Mode.IDLE to idleButton, Mode.SNIFF to sniffButton, Mode.BENCH to benchButton)
        for ((target, button) in modeButtons) {
            button.setOnClickListener { link.send("M ${target.name}") }
        }

        pauseButton.setOnClickListener {
            paused = !paused
            pauseButton.setText(if (paused) R.string.resume else R.string.pause)
        }
        findViewById<Button>(R.id.clear).setOnClickListener {
            table.clear()
            lostView.visibility = View.GONE
            renderTables()
        }
        recordBox.setOnCheckedChangeListener { _, checked -> if (checked) startRecording() else stopRecording() }

        buildBenchControls(findViewById(R.id.bench_controls))
        linkStatus.setText(R.string.link_searching)
        showMode(Mode.IDLE)

        link = LabLink(this, this)
    }

    override fun onStart() {
        super.onStart()
        link.start()
        ui.post(refresh)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(refresh)
        // Уходя с экрана, возвращаем адаптер в безопасный режим: он перестаёт передавать
        link.send("M IDLE")
        ui.postDelayed({ link.stop() }, 300)
        recordBox.isChecked = false
    }

    // ---- Связь ----

    override fun onSearching(waitingPermission: Boolean) {
        ui.post {
            linkStatus.setText(if (waitingPermission) R.string.link_permission else R.string.link_searching)
        }
    }

    override fun onConnected(deviceName: String) {
        ui.post {
            linkStatus.text = getString(R.string.link_connected, deviceName)
            showMode(Mode.IDLE)
        }
        // При открытии порта Arduino перезагружается; на случай, если приветствие
        // потерялось, состояние запрашивается ещё раз
        ui.postDelayed({ link.send("?") }, HELLO_DELAY_MS)
    }

    override fun onDisconnected(reason: String) {
        ui.post {
            linkStatus.text = getString(R.string.link_error, reason)
            showMode(Mode.IDLE)
        }
    }

    override fun onLine(line: String) {
        recorder?.let { writer ->
            try {
                writer.write("${SystemClock.elapsedRealtime() - recordStarted}\t$line\n")
            } catch (e: Exception) {
                // Ошибку записи покажет остановка записи
            }
        }
        val frame = CanLineParser.parse(line)
        if (frame != null) {
            if (!paused) table.onFrame(frame, SystemClock.elapsedRealtime())
        } else {
            ui.post { onTextLine(line) }
        }
    }

    /** Ответ прошивки, не являющийся кадром. */
    private fun onTextLine(line: String) {
        log("< $line")
        val parts = line.split(' ')
        when (parts[0]) {
            "LAB" -> linkStatus.text = getString(R.string.link_ready, parts.drop(1).joinToString(" "))
            "MODE" -> showMode(Mode.values().firstOrNull { it.name == parts.getOrNull(1) } ?: Mode.IDLE)
            "STATE" -> syncBench(parts.drop(1))
            "LOST" -> {
                lostView.text = getString(R.string.lost_frames, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                lostView.visibility = View.VISIBLE
            }
            "ERR" -> if (parts.getOrNull(1) == "BSI_PRESENT") {
                AlertDialog.Builder(this).setMessage(R.string.bench_refused).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun command(text: String) {
        log("> $text")
        link.send(text)
    }

    private fun log(text: String) {
        logLines.addLast(text)
        while (logLines.size > LOG_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    // ---- Режимы ----

    private fun showMode(newMode: Mode) {
        if (newMode != mode) table.clear()
        mode = newMode
        idlePanel.visibility = if (mode == Mode.IDLE) View.VISIBLE else View.GONE
        sniffPanel.visibility = if (mode == Mode.SNIFF) View.VISIBLE else View.GONE
        benchPanel.visibility = if (mode == Mode.BENCH) View.VISIBLE else View.GONE
        for ((target, button) in modeButtons) button.isEnabled = target != mode
        lostView.visibility = View.GONE
        renderTables()
    }

    // ---- Таблица кадров ----

    private fun renderTables() {
        when (mode) {
            Mode.SNIFF -> sniffTable.text = renderTable(parseFilter(filterView.text.toString()))
            Mode.BENCH -> benchTable.text = renderTable(null)
            Mode.IDLE -> Unit
        }
    }

    /** Идентификаторы из строки фильтра; null — показывать все. */
    private fun parseFilter(text: String): Set<Int>? {
        val ids = text.split(' ', ',', ';').mapNotNull { it.trim().toIntOrNull(16) }.toSet()
        return if (ids.isEmpty()) null else ids
    }

    private fun renderTable(filter: Set<Int>?): CharSequence {
        val stats = table.snapshot().filter { filter == null || it.id in filter }
        if (stats.isEmpty()) return getString(R.string.table_empty)
        val now = SystemClock.elapsedRealtime()
        val text = SpannableStringBuilder(getString(R.string.table_header)).append('\n')
        for (stat in stats) appendRow(text, stat, now)
        return text
    }

    private fun appendRow(text: SpannableStringBuilder, stat: FrameStat, now: Long) {
        text.append(String.format(Locale.ROOT, "%03X %d   ", stat.id, stat.dlc))
        for (i in 0 until 8) {
            if (i < stat.dlc) {
                val start = text.length
                text.append(String.format(Locale.ROOT, "%02X", stat.data[i]))
                // Недавно изменившийся байт выделяется цветом
                if (stat.changedAt[i] != 0L && now - stat.changedAt[i] < HIGHLIGHT_MS) {
                    text.setSpan(ForegroundColorSpan(HIGHLIGHT_COLOR), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.append(' ')
            } else {
                text.append("   ")
            }
        }
        // Кадр, который перестал приходить, показывается приглушённо
        val stale = now - stat.lastAt > STALE_MS
        val tailStart = text.length
        text.append(String.format(Locale.ROOT, " %5d мс %8d  %s", stat.period, stat.count, KnownFrames.name(stat.id)))
        if (stale) {
            text.setSpan(ForegroundColorSpan(STALE_COLOR), tailStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append('\n')
    }

    // ---- Запись в файл ----

    private fun startRecording() {
        try {
            val dir = File(getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val name = "can-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
            val file = File(dir, name)
            recordStarted = SystemClock.elapsedRealtime()
            recorder = BufferedWriter(FileWriter(file))
            recordFile = file
            log(getString(R.string.record_started, file.absolutePath))
        } catch (e: Exception) {
            log(getString(R.string.record_failed, e.message ?: e.javaClass.simpleName))
            recordBox.isChecked = false
        }
    }

    private fun stopRecording() {
        val writer = recorder ?: return
        recorder = null
        try {
            writer.close()
        } catch (e: Exception) {
            // Файл мог быть уже закрыт
        }
        log(getString(R.string.record_stopped, recordFile?.absolutePath ?: ""))
    }

    // ---- Стенд ----

    private fun buildBenchControls(root: LinearLayout) {
        addTitle(root, R.string.title_car)
        addSwitch(root, "IGN", R.string.ignition)
        addSwitch(root, "REV", R.string.reverse)
        addSwitch(root, "DARK", R.string.dark)
        addSlider(root, "TEMP", R.string.outside_temp, -40, 60)
        addSlider(root, "COOL", R.string.coolant_temp, -40, 130)
        addSlider(root, "BRIGHT", R.string.brightness, 0, 15)

        addTitle(root, R.string.title_message)
        val codes = InfoMessages.codes
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            codes.map { String.format(Locale.ROOT, "%03X  %s", it, InfoMessages.text(it)) },
        )
        root.addView(spinner)
        val messageRow = addRow(root)
        addButton(messageRow, getString(R.string.message_show)) { command("MSG ${codes[spinner.selectedItemPosition]}") }
        addButton(messageRow, getString(R.string.message_hide)) { command("MSG OFF") }
        addSwitch(root, "CYCLE", R.string.cycle)

        addTitle(root, R.string.title_radio)
        addSwitch(root, "RADIO", R.string.radio)
        addSlider(root, "VOL", R.string.volume, 0, 30)

        addTitle(root, R.string.title_buttons)
        for (names in PANEL_BUTTONS) {
            val row = addRow(root)
            for (name in names) addButton(row, name) { command("BTN $name") }
        }

        addTitle(root, R.string.title_raw)
        val raw = EditText(this)
        raw.setHint(R.string.raw_hint)
        raw.setSingleLine()
        raw.textSize = 14f
        root.addView(raw)
        val rawRow = addRow(root)
        addButton(rawRow, getString(R.string.raw_send)) {
            val text = raw.text.toString().trim()
            if (text.isNotEmpty()) command("TX $text")
        }

        addTitle(root, R.string.title_display_frames)
        benchTable = TextView(this, null, 0, R.style.FrameTable)
        root.addView(benchTable)
    }

    private fun addTitle(root: LinearLayout, textRes: Int) {
        val title = TextView(this, null, 0, R.style.SectionTitle)
        title.setText(textRes)
        root.addView(title)
    }

    private fun addRow(root: LinearLayout): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        root.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return row
    }

    private fun addButton(row: LinearLayout, text: String, onClick: () -> Unit) {
        val button = Button(this)
        button.text = text
        button.setOnClickListener { onClick() }
        row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun addSwitch(root: LinearLayout, name: String, textRes: Int) {
        val switch = Switch(this)
        switch.setText(textRes)
        switch.setTextColor(TEXT_COLOR)
        switch.setPadding(0, 12, 0, 12)
        switch.setOnCheckedChangeListener { _, checked -> if (!syncing) command("$name ${if (checked) 1 else 0}") }
        root.addView(switch, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        switches[name] = switch
    }

    private fun addSlider(root: LinearLayout, name: String, labelRes: Int, min: Int, max: Int) {
        val label = TextView(this)
        label.setTextColor(TEXT_COLOR)
        label.text = getString(labelRes, min)
        root.addView(label)
        val bar = SeekBar(this)
        bar.max = max - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = getString(labelRes, progress + min)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            // Команда уходит один раз, когда ползунок отпущен
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!syncing) command("$name ${seekBar.progress + min}")
            }
        })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sliders[name] = Slider(name, min, labelRes, label, bar)
    }

    /** Приводит элементы стенда к состоянию из строки `STATE IGN=1 REV=0 …`. */
    private fun syncBench(pairs: List<String>) {
        syncing = true
        for (pair in pairs) {
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "").toIntOrNull() ?: continue
            switches[name]?.isChecked = value != 0
            sliders[name]?.let { it.bar.progress = value - it.offset }
        }
        syncing = false
    }

    private companion object {
        const val REFRESH_MS = 200L
        const val HELLO_DELAY_MS = 2500L
        const val HIGHLIGHT_MS = 1500L
        const val STALE_MS = 3000L
        const val LOG_LINES = 6
        val HIGHLIGHT_COLOR = Color.rgb(0xFF, 0x8C, 0x00)
        val STALE_COLOR = Color.rgb(0x70, 0x70, 0x70)
        val TEXT_COLOR = Color.rgb(0xE0, 0xE0, 0xE0)

        /** Имена кнопок должны совпадать с таблицей panelButtons в прошивке. */
        val PANEL_BUTTONS = listOf(
            listOf("MENU", "MODE", "ESC", "DARK"),
            listOf("LEFT", "UP", "OK", "DOWN", "RIGHT"),
            listOf("TRIP", "AUDIO", "TEL", "NAV", "CLIM"),
        )
    }
}
