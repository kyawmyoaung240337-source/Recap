package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Keys {
    private const val PREFS = "kgrecap_keys"
    private const val DEEPGRAM = "deepgram"

    fun deepgram(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEEPGRAM, "") ?: ""

    private fun setDeepgram(ctx: Context, v: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DEEPGRAM, v).apply()
    }

    fun mask(k: String): String = if (k.length <= 8) "••••" else "••••" + k.takeLast(4)

    /** Save နှိပ်ရင် key ကို တကယ်စစ်ပြီး မှန်မှသာ သိမ်းမယ် */
    fun showDialog(a: Activity, onChanged: () -> Unit) {
        val saved = deepgram(a)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(48, 24, 48, 8)

        val label = TextView(a)
        label.text = "Deepgram API key (စာထုတ်ဖို့)"
        label.textSize = 14f

        val field = EditText(a)
        field.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.setSingleLine()
        field.hint = if (saved.isNotEmpty()) "သိမ်းထားပြီး ${mask(saved)} (အသစ်ထည့်ရင် အစားထိုး)"
        else "Key ကို paste လုပ်ပါ"

        val status = TextView(a)
        status.textSize = 13f
        status.setPadding(0, 16, 0, 8)
        status.text = if (saved.isNotEmpty()) "✓ သိမ်းထားပြီး (${mask(saved)})" else "Key မရှိသေးပါ"

        val help = TextView(a)
        help.textSize = 11f
        help.text = "Key ရယူရန်: console.deepgram.com → API Keys → Create (Member role)"

        box.addView(label)
        box.addView(field)
        box.addView(status)
        box.addView(help)

        val builder = AlertDialog.Builder(a)
            .setTitle("🔑 API Keys")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("ပိတ်မယ်", null)
        if (saved.isNotEmpty()) builder.setNeutralButton("ဖျက်မယ်", null)
        val dlg = builder.create()

        dlg.setOnShowListener {
            val save = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val v = field.text.toString().trim()
                if (v.isEmpty()) {
                    status.setTextColor(Color.parseColor("#C62828"))
                    status.text = "Key အသစ် paste လုပ်ပါ"
                    return@setOnClickListener
                }
                save.isEnabled = false
                status.setTextColor(Color.DKGRAY)
                status.text = "စစ်နေပါတယ်..."
                Thread {
                    val err = Deepgram.validate(v)
                    a.runOnUiThread {
                        save.isEnabled = true
                        if (err == null) {
                            setDeepgram(a, v)
                            Toast.makeText(a, "Key အလုပ်လုပ်ပါတယ်၊ သိမ်းပြီးပါပြီ ✓", Toast.LENGTH_SHORT).show()
                            onChanged()
                            if (dlg.isShowing) dlg.dismiss()
                        } else {
                            status.setTextColor(Color.parseColor("#C62828"))
                            status.text = "✗ $err"
                        }
                    }
                }.start()
            }
            if (saved.isNotEmpty()) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    setDeepgram(a, "")
                    Toast.makeText(a, "Key ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                    onChanged()
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
    }
}
