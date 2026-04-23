package com.deatrg.dnsfilter

import android.util.Log

/**
 * 应用日志封装工具，确保 release 构建下完全不执行日志参数求值。
 * 所有日志调用都应通过此对象，避免直接调用 android.util.Log。
 */
object AppLog {

    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg)
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg, tr)
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, msg)
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, msg, tr)
        }
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, msg)
        }
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, msg)
        }
    }
}
