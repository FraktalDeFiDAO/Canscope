package com.canscope.app.link

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.canscope.app.protocol.BleUuid
import com.canscope.app.protocol.BusStatus
import com.canscope.app.protocol.CanFrame
import com.canscope.app.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Native BLE link — scans for the "CANScope" ESP32, connects GATT,
 * subscribes to TX notifications, writes commands/frames to RX.
 * Speaks the same 13-byte binary protocol as the web app.
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before connect()
class BleLink(private val context: Context, private val scope: CoroutineScope) : Link {

    override val state = MutableStateFlow(LinkState.DISCONNECTED)
    override val frames = MutableSharedFlow<CanFrame>(extraBufferCapacity = 512)
    override val obdValues = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 128)
    override val dtcs = MutableSharedFlow<List<Pair<Int, Int>>>(extraBufferCapacity = 8)
    override val busStatus = MutableStateFlow<BusStatus?>(null)
    override val lastError = MutableStateFlow<String?>(null)

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    private val serviceUuid: UUID = UUID.fromString(BleUuid.SERVICE)
    private val txUuid: UUID = UUID.fromString(BleUuid.CHAR_TX)
    private val rxUuid: UUID = UUID.fromString(BleUuid.CHAR_RX)
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            lastError.value = "BLE scan failed ($errorCode)"
            state.value = LinkState.ERROR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(185)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    state.value = LinkState.DISCONNECTED
                    rxChar = null
                    g.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(serviceUuid)
            val tx = svc?.getCharacteristic(txUuid)
            rxChar = svc?.getCharacteristic(rxUuid)
            if (tx == null || rxChar == null) {
                lastError.value = "GATT service not found — is this a CANScope bridge?"
                state.value = LinkState.ERROR
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(cccdUuid)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            state.value = LinkState.CONNECTED
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(ch.value ?: return)
        }
    }

    private fun handleNotify(b: ByteArray) {
        if (b.isEmpty()) return
        if (b[0] == Protocol.DTC_PKT) {
            scope.launch { dtcs.emit(Protocol.unpackDtcs(b)) }
            return
        }
        // Bus status packet: [0xFD, state_code, tx_errors, rx_errors]
        if (b[0] == 0xFD.toByte()) {
            val states = arrayOf("running", "bus-off", "recovering", "stopped")
            busStatus.value = BusStatus(
                states.getOrElse(b.getOrElse(1) { 0 }.toInt()) { "running" },
                b.getOrElse(2) { 0 }.toInt() and 0xFF,
                b.getOrElse(3) { 0 }.toInt() and 0xFF,
            )
            return
        }
        val f = Protocol.unpackFrame(b) ?: return
        scope.launch { frames.emit(f) }
        // OBD-II ECM response: mode 01 answer
        if (!f.ext && f.id in 0x7E8..0x7EF && f.data.getOrNull(1)?.toInt() == 0x41) {
            val pid = f.data[2].toInt() and 0xFF
            val len = (f.data[0].toInt() and 0xFF) - 2
            val raw = f.data.copyOfRange(3, 3 + len.coerceAtLeast(0))
            scope.launch { obdValues.emit(pid to raw) }
        }
    }

    override suspend fun connect() {
        lastError.value = null
        state.value = LinkState.CONNECTING
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            lastError.value = "Bluetooth is off or unavailable"
            state.value = LinkState.ERROR
            return
        }
        val scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        // 15s scan timeout
        scope.launch(Dispatchers.Default) {
            delay(15_000)
            if (scanning && state.value == LinkState.CONNECTING) {
                stopScan()
                lastError.value = "CANScope bridge not found — is the ESP32 powered?"
                state.value = LinkState.ERROR
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    override fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt = null
        state.value = LinkState.DISCONNECTED
    }

    private fun write(b: ByteArray) {
        val g = gatt ?: return
        val ch = rxChar ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        ch.value = b
        @Suppress("DEPRECATION")
        g.writeCharacteristic(ch)
    }

    override fun txFrame(id: Long, data: ByteArray, ext: Boolean) {
        write(Protocol.packFrame(id, ext, data))
        scope.launch { frames.emit(CanFrame(id = id, ext = ext, dlc = data.size, data = data, dir = CanFrame.Dir.TX)) }
    }

    override fun readDtcs() = write(Protocol.cmd(0x01))
    override fun clearDtcs() = write(Protocol.cmd(0x02))
    fun requestPid(pid: Int) = write(Protocol.cmd(0x03, pid))
    override fun applyConfig(bitrateIdx: Int, listenOnly: Boolean) {
        write(Protocol.cmd(0x10, bitrateIdx))
        write(Protocol.cmd(0x11, if (listenOnly) 1 else 0))
    }
}
