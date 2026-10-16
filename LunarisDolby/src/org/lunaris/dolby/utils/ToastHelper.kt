/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object ToastHelper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToast: WeakReference<Toast>? = null

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            currentToast?.get()?.cancel()
            val toast = Toast.makeText(context.applicationContext, message, duration)
            currentToast = WeakReference(toast)
            toast.show()
        }
    }
    
    fun showLongToast(context: Context, message: String) {
        showToast(context, message, Toast.LENGTH_LONG)
    }
}
