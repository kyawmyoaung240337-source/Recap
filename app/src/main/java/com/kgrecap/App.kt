package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object Breadcrumb {
    @Volatile var dir: File? = null

    fun mark(s: String) {
        try {
            val d = dir ?: return
            File(d, "stage.txt").writeText(s)
        } catch (e: Throwable) { }
    }

    fun clear() {
        try {
            val d = dir ?: return
            File(d, "stage.txt").delete()
        } catch (e: Throwable) { }
    }
}

class App : Application() {
    private var checked = false

    override fun onCreate() {
        super.onCreate()
        Breadcrumb.dir = filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Thread: ${t.name}\n$sw")
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (checked) return
                checked = true
                val cf = File(filesDir, "crash.txt")
                val sf = File(filesDir, "stage.txt")
                if (!cf.exists() && !sf.exists()) return
                val stage = if (sf.exists()) sf.readText() else ""
                val full = if (cf.exists()) cf.readText() else ""
                cf.delete()
                sf.delete()

                val shown = StringBuilder()
                if (stage.isNotEmpty()) shown.append("နောက်ဆုံး အဆင့်: ").append(stage).append("\n\n")
                if (full.isNotEmpty()) {
                    val keep = full.lines().filter {
                        it.startsWith("Thread") || it.contains("Exception") ||
                            it.contains("Error") || it.contains("Caused by") ||
                            it.contains("com.kgrecap")
                    }
                    shown.append(keep.take(40).joinToString("\n"))
                } else {
                    shown.append("Java error မရှိပါ။ Native crash ဒါမှမဟုတ် memory မလောက်လို့ system က ပိတ်ချတာ ဖြစ်နိုင်ပါတယ်။")
                }
                val copyText = ("နောက်ဆုံး အဆင့်: $stage\n\n$full").take(12000)

                val tv = TextView(a)
                tv.text = shown.toString()
                tv.textSize = 11f
                tv.setTextIsSelectable(true)
                tv.setPadding(32, 16, 32, 16)
                val sv = ScrollView(a)
                sv.addView(tv)
                AlertDialog.Builder(a)
                    .setTitle("Crash log")
                    .setView(sv)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", copyText))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
