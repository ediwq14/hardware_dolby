/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler()
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager
    @Volatile private var previousActiveDevice: AudioDeviceInfo? = null
    private var appProfileManager: AppProfileManager? = null

    // A single DSP re-apply is ~10 Binder round-trips. Without debouncing,
    // one song change fires a burst of onPlaybackConfigChanged and each one
    // re-programs DAP mid-playback -> audible pop. Same for BT connect bursts
    // (added+removed in quick succession). Coalesce them into one delayed run
    // on a background thread so the audio thread and UI thread never block.
    private val applyRunnable = Runnable {
        try {
            repository.applySavedState()
        } catch (e: Exception) {
            Log.w(TAG, "Debounced apply failed", e)
        }
    }
    private val deviceChangeRunnable = Runnable {
        try {
            handleDeviceChange()
        } catch (e: Exception) {
            Log.w(TAG, "Debounced device change failed", e)
        }
    }

    private fun scheduleApply(delayMs: Long = APPLY_DEBOUNCE_MS) {
        val h = bgHandler ?: return
        h.removeCallbacks(applyRunnable)
        h.postDelayed(applyRunnable, delayMs)
    }

    private fun scheduleDeviceChange(delayMs: Long = DEVICE_DEBOUNCE_MS) {
        val h = bgHandler ?: return
        h.removeCallbacks(deviceChangeRunnable)
        h.postDelayed(deviceChangeRunnable, delayMs)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            scheduleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            if (isDeviceStateMemoryEnabled) {
                // Capture cheap keys now (AudioDeviceInfo may go stale),
                // run the expensive DSP snapshot on the bg thread.
                val keys = removedDevices.map { deviceStateManager.deviceKey(it) }
                bgHandler?.post {
                    keys.forEach { key ->
                        try {
                            Log.d(TAG, "Snapshotting state for removed device: $key")
                            deviceStateManager.saveSnapshot(key, repository)
                        } catch (e: Exception) {
                            Log.w(TAG, "Snapshot failed for $key", e)
                        }
                    }
                }
            }
            scheduleDeviceChange()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            if (isActive) {
                // Debounced: the framework fires this in bursts on every
                // track change; re-applying DSP synchronously here = glitch.
                scheduleApply()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("DolbyBg").also { it.start() }
        bgThread = thread
        bgHandler = Handler(thread.looper)
        repository = DolbyRepository(this)
        deviceStateManager = DeviceStateManager(this)
        appProfileManager = AppProfileManager(this)
        val currentDevice = getCurrentOutputDevice()
        if (currentDevice != null) {
            previousActiveDevice = currentDevice
        }
        // Heavy initial restore (~15 Binder IPCs) off the main thread:
        // onCreate runs on the main thread and blocking it delays service
        // start and can drop the first seconds of audio tuning.
        bgHandler?.post {
            try {
                if (currentDevice != null) {
                    if (isDeviceStateMemoryEnabled) {
                        val key = deviceStateManager.deviceKey(currentDevice)
                        val restored = deviceStateManager.restoreSnapshot(key, repository)
                        if (!restored) repository.applySavedState()
                    } else {
                        repository.applySavedState()
                    }
                } else {
                    repository.applySavedState()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial apply failed", e)
            }
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        val newDevice = getCurrentOutputDevice()
        val oldDevice = previousActiveDevice

        if (oldDevice != null) {
            if (isDeviceStateMemoryEnabled) {
                val oldKey = deviceStateManager.deviceKey(oldDevice)
                Log.d(TAG, "Saving snapshot for previous device: $oldKey")
                deviceStateManager.saveSnapshot(oldKey, repository)
            }
        }

        if (newDevice != null) {
            val newKey = deviceStateManager.deviceKey(newDevice)
            if (isDeviceStateMemoryEnabled) {
                Log.d(TAG, "Restoring snapshot for new device: $newKey")
                val restored = deviceStateManager.restoreSnapshot(newKey, repository)
                if (!restored) {
                    Log.d(TAG, "First time device, applying saved state as base")
                    repository.applySavedState()
                    // Apply per-device EQ preset on first connect
                    bgHandler?.post {
                        try {
                            deviceStateManager.applyDeviceEqPreset(newDevice, repository)
                        } catch (e: Exception) {
                            Log.w(TAG, "Device EQ preset failed", e)
                        }
                    }
                }
            } else {
                Log.d(TAG, "Device state memory disabled, applying saved state")
                repository.applySavedState()
                // Apply per-device EQ preset on every connect
                bgHandler?.post {
                    try {
                        deviceStateManager.applyDeviceEqPreset(newDevice, repository)
                    } catch (e: Exception) {
                        Log.w(TAG, "Device EQ preset failed", e)
                    }
                }
            }
            previousActiveDevice = newDevice
        } else {
            repository.updateSpeakerState()
            repository.applySavedState()
            previousActiveDevice = null
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val routedDevice = try {
            audioManager
                .getDevicesForAttributes(ATTRIBUTES_MEDIA)
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active media route", e)
            null
        } ?: return null

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val routedAddress = routedDevice.address.orEmpty()

        return outputs.firstOrNull { device ->
            device.isSink &&
                device.type == routedDevice.type &&
                (routedAddress.isEmpty() || device.address == routedAddress)
        } ?: outputs.firstOrNull { device ->
            device.isSink && device.type == routedDevice.type
        }.also { device ->
            if (device == null) {
                Log.w(
                    TAG,
                    "Unable to map active media route: " +
                        "type=${routedDevice.type}, address=${routedDevice.address}"
                )
            }
        }
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("game_mode", false) == true) {
            appProfileManager?.let { mgr ->
                if (mgr.isGameApp(intent?.getStringExtra("package") ?: "")) {
                    bgHandler?.post {
                        try {
                            deviceStateManager.applyGameMode(repository)
                        } catch (e: Exception) {
                            Log.w(TAG, "Game mode apply failed", e)
                        }
                    }
                }
            }
        } else {
            scheduleApply(0L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bgHandler?.removeCallbacks(applyRunnable)
            bgHandler?.removeCallbacks(deviceChangeRunnable)
            if (isDeviceStateMemoryEnabled) {
                previousActiveDevice?.let { device ->
                    val key = deviceStateManager.deviceKey(device)
                    // Best-effort synchronous snapshot; bg thread may be gone.
                    try {
                        deviceStateManager.saveSnapshot(key, repository)
                    } catch (e: Exception) {
                        Log.w(TAG, "Final snapshot failed", e)
                    }
                }
            }
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error during destroy", e)
        } finally {
            handler.removeCallbacksAndMessages(null)
            bgThread?.quitSafely()
            bgThread = null
            bgHandler = null
        }
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"
        // Long enough to coalesce framework callback bursts, short enough
        // that retuning after a real route change is imperceptible.
        private const val APPLY_DEBOUNCE_MS = 400L
        private const val DEVICE_DEBOUNCE_MS = 600L

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
