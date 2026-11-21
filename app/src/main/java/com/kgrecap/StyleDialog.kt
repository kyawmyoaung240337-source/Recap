package com.kgrecap

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

object StyleDialog {
    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF76FF03.toInt(),
        0xFFFF9100.toInt(), 0xFFFF4081.toInt(), 0xFFFF1744.toInt(), 0xFF000000.toInt()
    )
    private val bgPalette = intArrayOf(
        0, 0xFF000000.toInt(), 0xFF333333.toInt(), 0xFFFFFFFF.toInt(),
        0xFF0D47A1.toInt(), 0xFFB71C1C.toInt(), 0xFFFFEB3B.toInt()
    )

    fun show(act: Activity, overlay: Overlay, sample: String, onSaved: () -> Unit) {
        val st = overlay.caption.copy()
        val dens = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        val fonts = FontStore.list(act)
        st.font = FontStore.resolve(act, st.font)

        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(8), dp(14), dp(8))

        val img = ImageView(act)
        img.setBackgroundColor(0xFF607D8B.toInt())
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        fun refresh() {
            img.setImageBitmap(CaptionRenderer.render(act, sample, st, 720, 1280))
        }

        fun title(t: String): TextView {
            val v = TextView(act)
            v.text = t
            v.textSize = 13f
            v.setPadding(0, dp(10), 0, 0)
            return v
        }

        fun colorRow(colors: IntArray, noneFirst: Boolean, onPick: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            for ((i, c) in colors.withIndex()) {
                val b = Button(act)
                if (noneFirst && i == 0) {
                    b.text = "မပါ"
                    b.textSize = 10f
                    b.isAllCaps = false
                } else {
                    b.setBackgroundColor(c)
                }
                b.setOnClickListener { onPick(c) }
                val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
                lp.setMargins(dp(2), dp(4), dp(2), dp(4))
                row.addView(b, lp)
            }
            return row
        }

        fun seekBar(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
            val s = SeekBar(act)
            s.max = max
            s.progress = progress
            s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            return s
        }

        // Font
        root.addView(title("Font"))
        val spinner = Spinner(act)
        spinner.adapter = ArrayAdapter(
            act, android.R.layout.simple_spinner_dropdown_item, fonts.map { FontStore.label(it) }
        )
        spinner.setSelection(maxOf(0, fonts.indexOf(st.font)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                st.font = fonts[pos]
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        // Size
        val sizeLabel = title(String.format(Locale.US, "Font size: %.1f", st.sizePct))
        root.addView(sizeLabel)
        root.addView(seekBar(80, ((st.sizePct - 2f) * 10f).toInt().coerceIn(0, 80)) { p ->
            st.sizePct = 2f + p * 0.1f
            sizeLabel.text = String.format(Locale.US, "Font size: %.1f", st.sizePct)
            refresh()
        })

        // Text color
        root.addView(title("စာလုံးအရောင်"))
        root.addView(colorRow(palette, false) { c ->
            st.textColor = c
            refresh()
        })
        val hex = EditText(act)
        hex.hint = "#RRGGBB (ကိုယ်ပိုင်အရောင်)"
        hex.setSingleLine()
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.trim() ?: return
                if (t.length == 7 && t[0] == '#') {
                    try {
                        st.textColor = Color.parseColor(t)
                        refresh()
                    } catch (e: Exception) { }
                }
            }
        })
        root.addView(hex)

        val bold = CheckBox(act)
        bold.text = "စာလုံး ထူ (Bold)"
        bold.isChecked = st.bold
        bold.setOnCheckedChangeListener { _, v ->
            st.bold = v
            refresh()
        }
        root.addView(bold)

        // Outline
        val owLabel = title("စာလုံးအနား (Outline) အထူ: ${(st.outlineW * 100).toInt()}")
        root.addView(owLabel)
        root.addView(seekBar(25, (st.outlineW * 100).toInt().coerceIn(0, 25)) { p ->
            st.outlineW = p / 100f
            owLabel.text = "စာလုံးအနား (Outline) အထူ: $p"
            refresh()
        })
        root.addView(title("Outline အရောင်"))
        root.addView(colorRow(palette, false) { c ->
            st.outlineColor = c
            refresh()
        })

        // Background
        root.addView(title("စာလုံးနောက်ခံ (မပါ = ဘာမှမပါ)"))
        root.addView(colorRow(bgPalette, true) { c ->
            st.bgColor = c
            refresh()
        })
        val baLabel = title("နောက်ခံ အထူ (opacity): ${st.bgAlpha}%")
        root.addView(baLabel)
        root.addView(seekBar(100, st.bgAlpha.coerceIn(0, 100)) { p ->
            st.bgAlpha = p
            baLabel.text = "နောက်ခံ အထူ (opacity): $p%"
            refresh()
        })

        val sv = ScrollView(act)
        sv.addView(root)
        refresh()
        AlertDialog.Builder(act)
            .setTitle("စာတန်း Style")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                val c = overlay.caption
                c.font = st.font
                c.sizePct = st.sizePct
                c.textColor = st.textColor
                c.outlineColor = st.outlineColor
                c.outlineW = st.outlineW
                c.bgColor = st.bgColor
                c.bgAlpha = st.bgAlpha
                c.bold = st.bold
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
