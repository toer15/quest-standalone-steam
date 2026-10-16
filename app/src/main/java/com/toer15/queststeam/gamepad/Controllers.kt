package com.toer15.queststeam.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A Bluetooth/USB game controller connected to the headset. */
data class Controller(val deviceId: Int, val model: String, val type: Int, val systemName: String)

/** Keeps the list of connected controllers up to date (Quest Settings → Devices → Bluetooth pairs them). */
object Controllers {
    const val TYPE_UNKNOWN = 0
    const val TYPE_XBOX = 1
    const val TYPE_PLAYSTATION = 2
    const val TYPE_NINTENDO = 3

    private val _connected = MutableStateFlow<List<Controller>>(emptyList())
    val connected: StateFlow<List<Controller>> = _connected
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val im = ctx.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = refresh()
            override fun onInputDeviceRemoved(id: Int) = refresh()
            override fun onInputDeviceChanged(id: Int) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        _connected.value = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter(::isController).map(::describe)
        GamepadBridge.onControllersChanged(_connected.value)
    }

    fun byDevice(id: Int) = _connected.value.firstOrNull { it.deviceId == id }

    /** Gamepads only: not the Quest's own Touch controllers, keyboards or the headset buttons. */
    fun isController(d: InputDevice): Boolean {
        if (d.isVirtual) return false
        val s = d.sources
        val pad = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!pad) return false
        val n = d.name.lowercase()
        if (d.vendorId == VENDOR_META || "oculus" in n || "quest" in n || "touch" in n || "headset" in n) return false
        return true
    }

    fun describe(d: InputDevice) = Controller(d.id, modelName(d.vendorId, d.productId, d.name), typeOf(d.vendorId, d.name), d.name)

    fun typeOf(vendor: Int, name: String): Int {
        val n = name.lowercase()
        return when {
            vendor == VENDOR_MICROSOFT || "xbox" in n -> TYPE_XBOX
            vendor == VENDOR_SONY || "dualsense" in n || "dualshock" in n || "playstation" in n -> TYPE_PLAYSTATION
            vendor == VENDOR_NINTENDO || "pro controller" in n || "joy-con" in n -> TYPE_NINTENDO
            else -> TYPE_UNKNOWN
        }
    }

    /** The controller's real model, e.g. "DualSense Wireless Controller (PS5)". */
    fun modelName(vendor: Int, product: Int, name: String): String {
        val n = name.lowercase()
        return when (vendor) {
            VENDOR_SONY -> when (product) {
                0x0ce6 -> "DualSense Wireless Controller (PS5)"
                0x0df2 -> "DualSense Edge (PS5)"
                0x05c4, 0x09cc, 0x0ba0 -> "DualShock 4 (PS4)"
                0x0268 -> "DualShock 3 (PS3)"
                else -> if ("dualsense" in n) "DualSense Wireless Controller (PS5)" else "PlayStation controller"
            }
            VENDOR_MICROSOFT -> when (product) {
                0x0b00, 0x0b05, 0x0b22 -> "Xbox Elite Wireless Controller Series 2"
                0x0b12, 0x0b13, 0x0b20, 0x0b21 -> "Xbox Wireless Controller (Series X|S)"
                0x02dd, 0x02e0, 0x02ea, 0x02fd, 0x0b06 -> "Xbox Wireless Controller (Xbox One)"
                0x02e3 -> "Xbox Elite Wireless Controller"
                else -> "Xbox controller"
            }
            VENDOR_NINTENDO -> when (product) {
                0x2009 -> "Nintendo Switch Pro Controller"
                0x2006 -> "Joy-Con (L)"
                0x2007 -> "Joy-Con (R)"
                else -> "Nintendo controller"
            }
            else -> when {
                "dualsense" in n -> "DualSense Wireless Controller (PS5)"
                "xbox" in n -> "Xbox controller"
                vendor == VENDOR_8BITDO -> name.ifBlank { "8BitDo controller" }
                else -> name.ifBlank { "Game controller" }
            }
        }
    }

    const val VENDOR_SONY = 0x054c
    const val VENDOR_MICROSOFT = 0x045e
    const val VENDOR_NINTENDO = 0x057e
    const val VENDOR_8BITDO = 0x2dc8
    const val VENDOR_META = 0x2833
}
