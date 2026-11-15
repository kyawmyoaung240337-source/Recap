package com.kgrecap

object WhisperBridge {
    init {
        System.loadLibrary("kgwhisper")
    }

    external fun nativeInit(path: String): Long
    external fun nativeFree(ptr: Long)
    external fun nativeTranscribe(ptr: Long, audio: FloatArray, lang: String, threads: Int): String
    external fun nativeSystemInfo(): String

    fun systemInfo(): String = nativeSystemInfo()
}
