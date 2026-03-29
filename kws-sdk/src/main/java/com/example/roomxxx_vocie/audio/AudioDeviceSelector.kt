package com.example.roomxxx_vocie.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

object AudioDeviceSelector {
    fun selectPreferredInputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    fun chooseSampleRate(sampleRates: IntArray?): Int {
        if (sampleRates == null || sampleRates.isEmpty()) {
            return 16000
        }
        if (sampleRates.contains(16000)) {
            return 16000
        }
        if (sampleRates.contains(48000)) {
            return 48000
        }
        return sampleRates[0]
    }
}
