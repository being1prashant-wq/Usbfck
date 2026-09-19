package com.example.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

data class PtpInterfaceInfo(
    val usbInterface: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
    val interruptIn: UsbEndpoint?
)

sealed class UsbConnectionState {
    object Idle : UsbConnectionState()
    data class DeviceAttached(val device: UsbDevice) : UsbConnectionState()
    data class PermissionRequired(val device: UsbDevice) : UsbConnectionState()
    object PermissionDenied : UsbConnectionState()
    data class Connected(val device: UsbDevice, val client: PtpClient, val deviceName: String) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()
    object Disconnected : UsbConnectionState()
}

class UsbHostManager(
    private val context: Context,
    private val onStateChanged: (UsbConnectionState) -> Unit
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.example.directusb.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null
    private var currentInterface: UsbInterface? = null
    private var activePtpClient: PtpClient? = null
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        Log.i(PtpConstants.TAG, "USB device attached: ${device.deviceName} (Product: ${device.productName})")
                        handleDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && (currentDevice == null || device.deviceId == currentDevice?.deviceId)) {
                        Log.i(PtpConstants.TAG, "USB device detached: ${device.deviceName}")
                        disconnect()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Log.i(PtpConstants.TAG, "USB permission granted for ${device.deviceName}")
                            connectPtp(device)
                        } else {
                            Log.w(PtpConstants.TAG, "USB permission denied for ${device.deviceName}")
                            onStateChanged(UsbConnectionState.PermissionDenied)
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
        // Inspect currently connected devices at startup (no continuous polling)
        inspectConnectedDevices()
    }

    fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        disconnect()
    }

    private fun inspectConnectedDevices() {
        val deviceList = usbManager.deviceList
        Log.i(PtpConstants.TAG, "Inspect connected devices: found ${deviceList.size} device(s)")
        for (device in deviceList.values) {
            val ptpInfo = findPtpInterface(device)
            if (ptpInfo != null) {
                Log.i(PtpConstants.TAG, "Found PTP device: ${device.deviceName} (Product: ${device.productName})")
                handleDeviceAttached(device)
                return
            }
        }
        onStateChanged(UsbConnectionState.Idle)
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        val ptpInfo = findPtpInterface(device)
        if (ptpInfo == null) {
            Log.w(PtpConstants.TAG, "Device ${device.deviceName} has no PTP interface exposed.")
            onStateChanged(UsbConnectionState.Error("No PTP interface was exposed by this device.\nOn your phone select PTP / Transfer photos."))
            return
        }

        currentDevice = device
        onStateChanged(UsbConnectionState.DeviceAttached(device))

        if (usbManager.hasPermission(device)) {
            Log.i(PtpConstants.TAG, "USB permission already held for ${device.deviceName}")
            connectPtp(device)
        } else {
            Log.i(PtpConstants.TAG, "Requesting USB permission for ${device.deviceName}")
            onStateChanged(UsbConnectionState.PermissionRequired(device))
            requestPermission(device)
        }
    }

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectPtp(device: UsbDevice) {
        val ptpInfo = findPtpInterface(device)
        if (ptpInfo == null) {
            onStateChanged(UsbConnectionState.Error("No PTP interface was exposed by this device."))
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(PtpConstants.TAG, "Failed to open USB device connection")
            onStateChanged(UsbConnectionState.Error("Could not open USB connection."))
            return
        }

        if (!connection.claimInterface(ptpInfo.usbInterface, true)) {
            Log.e(PtpConstants.TAG, "Failed to claim PTP interface ${ptpInfo.usbInterface.id}")
            connection.close()
            onStateChanged(UsbConnectionState.Error("Could not claim PTP interface."))
            return
        }

        currentConnection = connection
        currentInterface = ptpInfo.usbInterface

        val client = PtpClient(connection, ptpInfo.bulkIn, ptpInfo.bulkOut)
        activePtpClient = client

        val fallbackName = device.productName ?: device.manufacturerName ?: "Phone"
        onStateChanged(UsbConnectionState.Connected(device, client, fallbackName))
    }

    fun disconnect() {
        Log.i(PtpConstants.TAG, "Disconnecting USB and releasing resources")
        activePtpClient = null
        currentInterface?.let { intf ->
            try {
                currentConnection?.releaseInterface(intf)
            } catch (_: Exception) {}
        }
        currentInterface = null
        currentConnection?.let { conn ->
            try {
                conn.close()
            } catch (_: Exception) {}
        }
        currentConnection = null
        currentDevice = null
        onStateChanged(UsbConnectionState.Disconnected)
    }

    /**
     * Inspect all interfaces on the device to locate a PTP interface.
     * Searches for:
     * 1. USB Class 6 (Still Image), Subclass 1, Protocol 1
     * 2. Or any interface with Bulk IN and Bulk OUT endpoints where Class == 6
     */
    fun findPtpInterface(device: UsbDevice): PtpInterfaceInfo? {
        val count = device.interfaceCount
        for (i in 0 until count) {
            val intf = device.getInterface(i)
            // Check class: 6 = USB_CLASS_STILL_IMAGE
            val isStillImageClass = (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE)
            val isPtpStandard = isStillImageClass && intf.interfaceSubclass == 1 && intf.interfaceProtocol == 1

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        bulkIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOut = ep
                    }
                } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                    interruptIn = ep
                }
            }

            // Valid PTP interface must have both bulk IN and bulk OUT endpoints
            if (bulkIn != null && bulkOut != null && (isPtpStandard || isStillImageClass)) {
                Log.i(PtpConstants.TAG, "PTP interface detected at index $i (id=${intf.id})")
                return PtpInterfaceInfo(intf, bulkIn, bulkOut, interruptIn)
            }
        }
        return null
    }
}
