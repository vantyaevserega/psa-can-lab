package com.vantserg.psacanlab

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

/**
 * Связь с адаптером по USB-serial: поиск, подключение, чтение строк, переподключение.
 * Все методы [Listener] вызываются из потока связи, не из главного.
 */
class LabLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onSearching(waitingPermission: Boolean)
        fun onConnected(deviceName: String)
        fun onDisconnected(reason: String)
        fun onLine(line: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var port: UsbSerialPort? = null
    private var thread: Thread? = null
    private var permissionAskedFor: String? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "lab-link").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        closePort()
    }

    /** Отправляет строку команды. Без связи команда молча отбрасывается. */
    fun send(command: String) {
        val bytes = (command + "\n").toByteArray(Charsets.US_ASCII)
        writer.execute {
            try {
                port?.write(bytes, WRITE_TIMEOUT_MS)
            } catch (e: Exception) {
                // Обрыв связи обнаружит и обработает поток чтения
            }
        }
    }

    private fun loop() {
        while (running) {
            try {
                val opened = open()
                if (opened == null) {
                    Thread.sleep(RETRY_MS)
                    continue
                }
                port = opened
                listener.onConnected(opened.device.productName ?: opened.device.deviceName)
                readLines(opened)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                closePort()
            }
            if (running) {
                try {
                    Thread.sleep(RETRY_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    /** null — адаптера нет или к нему ещё нет доступа. */
    private fun open(): UsbSerialPort? {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            permissionAskedFor = null
            listener.onSearching(false)
            return null
        }
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            // Запрос показывается один раз на подключение устройства
            if (permissionAskedFor != device.deviceName) {
                permissionAskedFor = device.deviceName
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
            listener.onSearching(true)
            return null
        }
        val connection = usbManager.openDevice(device) ?: return null
        val serial = driver.ports[0]
        serial.open(connection)
        serial.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        serial.dtr = true
        serial.rts = true
        return serial
    }

    private fun readLines(serial: UsbSerialPort) {
        val buffer = ByteArray(4096)
        val line = StringBuilder()
        while (running) {
            val count = serial.read(buffer, READ_TIMEOUT_MS)
            for (i in 0 until count) {
                val c = buffer[i].toInt().toChar()
                if (c == '\r' || c == '\n') {
                    if (line.isNotEmpty()) {
                        listener.onLine(line.toString())
                        line.setLength(0)
                    }
                } else if (line.length < MAX_LINE) {
                    line.append(c)
                }
            }
        }
    }

    private fun closePort() {
        val current = port
        port = null
        try {
            current?.close()
        } catch (e: Exception) {
            // Порт уже закрыт или устройство отключено
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.vantserg.psacanlab.USB_PERMISSION"

        /** Должно совпадать с SERIAL_BAUD в прошивке. */
        const val BAUD_RATE = 500_000
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 500
        const val RETRY_MS = 1500L
        const val MAX_LINE = 128
    }
}
