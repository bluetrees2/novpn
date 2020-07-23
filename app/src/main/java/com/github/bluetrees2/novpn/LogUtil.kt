package com.github.bluetrees2.novpn

import android.util.Log as AndroidLog

data class LogRecord(val priority: Int, val tag: String?, val msg: String)

@Suppress("unused")
class Log private constructor() {
    companion object {
        const val ASSERT = AndroidLog.ASSERT
        const val DEBUG = AndroidLog.DEBUG
        const val ERROR = AndroidLog.ERROR
        const val INFO = AndroidLog.INFO
        const val VERBOSE = AndroidLog.VERBOSE
        const val WARN = AndroidLog.WARN

        val records = RingBuffer<LogRecord>(512)

        fun v(tag: String?, msg: String): Int {
            return println(VERBOSE, tag, msg)
        }

        fun d(tag: String?, msg: String): Int {
            return println(DEBUG, tag, msg)
        }

        fun i(tag: String?, msg: String): Int {
            return println(INFO, tag, msg)
        }

        fun w(tag: String?, msg: String): Int {
            return println(WARN, tag, msg)
        }

        fun e(tag: String?, msg: String): Int {
            return println(ERROR, tag, msg)
        }

        fun println(priority: Int, tag: String?, msg: String): Int {
            records.add(LogRecord(priority, tag, msg))
            return AndroidLog.println(priority, tag, msg)
        }
    }
}
