package com.usbtester

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbDeviceInfo(
    val name: String,
    val manufacturer: String,
    val product: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceSummary: String
)

object UsbInfo {
    fun connected(context: Context): List<UsbDeviceInfo> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.map { device: UsbDevice ->
            UsbDeviceInfo(
                name = device.deviceName,
                manufacturer = device.manufacturerName ?: "Not reported",
                product = device.productName ?: "Not reported",
                vendorId = device.vendorId,
                productId = device.productId,
                interfaceSummary = (0 until device.interfaceCount).joinToString { index ->
                    val intf = device.getInterface(index)
                    "class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}"
                }.ifBlank { "No interfaces reported" }
            )
        }
    }
}
