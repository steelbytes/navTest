package com.example.navtest

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** In-process log shared by the activity and services; also mirrored to logcat. */
object AppLog {
    private const val TAG = "NavTest"
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(msg: String) {
        Log.d(TAG, msg)
        val line = "${LocalTime.now().format(timeFormat)}  $msg"
        _lines.update { it + line }
    }
}
