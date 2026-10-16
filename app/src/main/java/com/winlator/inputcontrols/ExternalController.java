package com.winlator.inputcontrols;

import android.view.InputDevice;

/** Quest Steam: game controllers go to games through Quest Steam's XInput bridge, not the X keyboard. */
public final class ExternalController {
    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        int s = device.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
