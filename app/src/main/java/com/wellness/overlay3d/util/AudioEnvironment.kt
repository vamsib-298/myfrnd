package com.wellness.overlay3d.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object AudioEnvironment {
    fun isInActiveCall(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return when (am.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    fun areHeadphonesConnected(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= 31 && (
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    )
            }
        }
    }
}
