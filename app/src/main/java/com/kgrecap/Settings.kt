package com.kgrecap

import android.content.Context

/** Render လုပ်ချိန် အသံ volume (MainActivity က ပြောင်းပေးတယ်) */
object RenderSettings {
    @Volatile var voiceGain: Double = 2.0
}

/** API key တွေကို app ရဲ့ private storage ထဲ သိမ်း */
object KeyStore {
    const val DEEPGRAM = "deepgram"
    private const val FILE = "kgrecap_keys"

    fun get(ctx: Context, name: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(name, "") ?: ""

    fun set(ctx: Context, name: String, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(name, value).apply()
