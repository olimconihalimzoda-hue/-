package com.evdiag.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeoutException

/** Канал связи с адаптером ELM327. */
interface Transport {
    suspend fun open()
    suspend fun write(text: String)

    /** Читает ответ до приглашения '>' (без него) или бросает TimeoutException. */
    suspend fun readUntilPrompt(timeoutMs: Long): String
    fun close()
}

abstract class StreamTransport : Transport {
    protected var input: InputStream? = null
    protected var output: OutputStream? = null

    override suspend fun write(text: String) {
        withContext(Dispatchers.IO) {
            val out = output ?: error("Нет соединения")
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: error("Нет соединения")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val c = inp.read()
                if (c < 0) break
                if (c.toChar() == '>') return@withContext sb.toString()
                sb.append(c.toChar())
            } else {
                delay(5)
            }
        }
        throw TimeoutException("Таймаут ELM327: $sb")
    }
}

/** Bluetooth Classic (SPP). Адаптер должен быть предварительно спарен в настройках Android. */
class BluetoothTransport(private val device: BluetoothDevice) : StreamTransport() {
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(SPP)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
        }
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Wi-Fi ELM327 (обычно 192.168.0.10:35000). */
class WifiTransport(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000,
) : StreamTransport() {
    private var socket: Socket? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
    }
}
