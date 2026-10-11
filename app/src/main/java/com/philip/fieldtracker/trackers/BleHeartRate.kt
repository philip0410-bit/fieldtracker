package com.philip.fieldtracker.trackers

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.philip.fieldtracker.tracking.HeartRateHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Capteur cardio Bluetooth standard (profil Heart Rate 0x180D) :
 * ceintures Polar/Garmin/Wahoo, brassards, et traqueurs GPS qui exposent ce profil.
 */
@SuppressLint("MissingPermission")
object BleHeartRate {
    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    data class Found(val address: String, val name: String, val rssi: Int)

    enum class Status { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = d.name ?: result.scanRecord?.deviceName ?: tr("Capteur cardio")
            val list = _found.value.filter { it.address != d.address } + Found(d.address, name, result.rssi)
            _found.value = list.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = Status.ERROR
        }
    }

    fun startScan(ctx: Context) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: run {
            _status.value = Status.ERROR; return
        }
        if (!adapter.isEnabled) { _status.value = Status.ERROR; return }
        val scanner = adapter.bluetoothLeScanner ?: run { _status.value = Status.ERROR; return }
        _found.value = emptyList()
        _status.value = Status.SCANNING
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            handler.postDelayed({ stopScan(ctx) }, 12_000)
        } catch (e: SecurityException) {
            _status.value = Status.ERROR
        }
    }

    fun stopScan(ctx: Context) {
        try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (_status.value == Status.SCANNING) _status.value = Status.IDLE
    }

    fun connect(ctx: Context, f: Found) {
        stopScan(ctx)
        disconnect()
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val device: BluetoothDevice = adapter.getRemoteDevice(f.address)
        _deviceName.value = f.name
        _status.value = Status.CONNECTING
        gatt = try {
            device.connectGatt(ctx.applicationContext, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _status.value = Status.ERROR; null
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect(); gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        _status.value = Status.IDLE
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _status.value = if (gatt == null) Status.IDLE else Status.CONNECTING
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: run {
                _status.value = Status.ERROR; return
            }
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCC) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
            _status.value = Status.CONNECTED
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == HR_MEASUREMENT) parse(value)
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && ch.uuid == HR_MEASUREMENT) {
                @Suppress("DEPRECATION")
                ch.value?.let { parse(it) }
            }
        }
    }

    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt()
        val bpm = if (flags and 0x01 != 0 && v.size >= 3) {
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        } else {
            v[1].toInt() and 0xFF
        }
        handler.post { HeartRateHub.push(bpm, _deviceName.value.ifEmpty { tr("Capteur BLE") }) }
    }
}
