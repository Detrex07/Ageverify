package com.ageverify.util

import android.util.Log

object AppLog {

    fun d(tag: String, message: String) = runSafely {
        Log.d(tag, message)
    }

    fun w(tag: String, message: String) = runSafely {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) = runSafely {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    private inline fun runSafely(block: () -> Unit) {
        runCatching(block)
    }
}
