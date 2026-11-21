package com.kgrecap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject

/** x,y,w,h = ဗီဒီယိုပုံရဲ့ အချိုး (0..1)၊ density = 0..100 */
class BlurSpec(
    var on: Boolean,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var density: Int
) {
    fun copy() = BlurSpec(on, x, y, w, h, density)
}

class CaptionStyle(
    var font: String = "",
    var sizePct: Float = 5.2f,
    var textColor: Int = Color.WHITE,
    var outlineColor: Int = Color.BLACK,
    var outlineW: Float = 0.12f,
    var bgColor: Int = 0,
    var bgAlpha: Int = 55,
    var bold: Boolean = false,
    var show: Boolean = true,
    var cx: Float = 0.5f,
    var cy: Float = 0.86f
) {
    fun copy() = CaptionStyle(
        font, sizePct, textColor, outlineColor, outlineW, bgColor, bgAlpha, bold, show, cx, cy
    )

    fun styleKey() = "$font|$sizePct|$textColor|$outlineColor|$outlineW|$bgColor|$bgAlpha|$bold"
}

class Overlay(val blurs: List<BlurSpec>, val caption: CaptionStyle) {
    fun copy() = Overlay(blurs.map { it.copy() }, caption.copy())

    fun save(ctx: Context) {
        try {
            val j = JSONObject()
            val arr = JSONArray()
            for (b in blurs) {
                val o = JSONObject()
                o.put("on", b.on)
                o.put("x", b.x.toDouble())
                o.put("y", b.y.toDouble())
                o.put("w", b.w.toDouble())
                o.put("h", b.h.toDouble())
                o.put("d", b.density)
                arr.put(o)
            }
            j.put("blurs", arr)
            val c = caption
            val o = JSONObject()
            o.put("font", c.font)
            o.put("size", c.sizePct.toDouble())
            o.put("tc", c.textColor)
            o.put("oc", c.outlineColor)
            o.put("ow", c.outlineW.toDouble())
            o.put("bc", c.bgColor)
            o.put("ba", c.bgAlpha)
            o.put("bold", c.bold)
            o.put("show", c.show)
            o.put("cx", c.cx.toDouble())
            o.put("cy", c.cy.toDouble())
            j.put("caption", o)
            ctx.getSharedPreferences("kgrecap_overlay", Context.MODE_PRIVATE)
                .edit().putString("json", j.toString()).apply()
        } catch (e: Exception) { }
    }

    companion object {
        fun defaults() = Overlay(
            listOf(
                BlurSpec(false, 0.04f, 0.78f, 0.92f, 0.12f, 70),
                BlurSpec(false, 0.04f, 0.04f, 0.45f, 0.07f, 70)
            ),
            CaptionStyle()
        )

        fun load(ctx: Context): Overlay {
            val d = defaults()
            try {
                val s = ctx.getSharedPreferences("kgrecap_overlay", Context.MODE_PRIVATE)
                    .getString("json", null) ?: return d
                val j = JSONObject(s)
                val arr = j.optJSONArray("blurs")
                if (arr != null) {
                    for (i in 0 until minOf(2, arr.length())) {
                        val o = arr.getJSONObject(i)
                        val b = d.blurs[i]
                        b.on = o.optBoolean("on", b.on)
                        b.x = o.optDouble("x", b.x.toDouble()).toFloat().coerceIn(0f, 0.95f)
                        b.y = o.optDouble("y", b.y.toDouble()).toFloat().coerceIn(0f, 0.95f)
                        b.w = o.optDouble("w", b.w.toDouble()).toFloat().coerceIn(0.1f, 1f - b.x)
                        b.h = o.optDouble("h", b.h.toDouble()).toFloat().coerceIn(0.04f, 1f - b.y)
                        b.density = o.optInt("d", b.density).coerceIn(0, 100)
                    }
                }
                val o = j.optJSONObject("caption")
                if (o != null) {
                    val c = d.caption
                    c.font = o.optString("font", c.font)
                    c.sizePct = o.optDouble("size", c.sizePct.toDouble()).toFloat().coerceIn(2f, 10f)
                    c.textColor = o.optInt("tc", c.textColor)
                    c.outlineColor = o.optInt("oc", c.outlineColor)
                    c.outlineW = o.optDouble("ow", c.outlineW.toDouble()).toFloat().coerceIn(0f, 0.25f)
                    c.bgColor = o.optInt("bc", c.bgColor)
                    c.bgAlpha = o.optInt("ba", c.bgAlpha).coerceIn(0, 100)
                    c.bold = o.optBoolean("bold", c.bold)
                    c.show = o.optBoolean("show", c.show)
                    c.cx = o.optDouble("cx", c.cx.toDouble()).toFloat().coerceIn(0f, 1f)
                    c.cy = o.optDouble("cy", c.cy.toDouble()).toFloat().coerceIn(0f, 1f)
                }
            } catch (e: Exception) { }
            return d
        }
    }
}

object FontStore {
    private val cache = HashMap<String, Typeface>()

    /** "system" + assets/fonts ထဲက .ttf/.otf အားလုံး */
    fun list(ctx: Context): List<String> {
        val out = ArrayList<String>()
        out.add("system")
        try {
            val names = ctx.assets.list("fonts") ?: emptyArray()
            for (n in names.sorted()) {
                val l = n.lowercase()
                if (l.endsWith(".ttf") || l.endsWith(".otf")) out.add(n)
            }
        } catch (e: Exception) { }
        return out
    }

    fun label(id: String): String {
        if (id == "system") return "Myanmar font (system)"
        val base = id.substringBeforeLast('.')
        val l = base.lowercase()
        return when {
            l.contains("notosansmyanmar") -> "Noto Sans Myanmar"
            l.contains("pyidaungsu") -> "Pyidaungsu"
            else -> base
        }
    }

    fun resolve(ctx: Context, id: String): String {
        val all = list(ctx)
        if (id.isNotEmpty() && all.contains(id)) return id
        return all.firstOrNull { it.lowercase().contains("notosansmyanmar") } ?: "system"
    }

    fun typeface(ctx: Context, id: String): Typeface {
        val key = resolve(ctx, id)
        return cache.getOrPut(key) {
            if (key == "system") Typeface.DEFAULT
            else try {
                Typeface.createFromAsset(ctx.assets, "fonts/$key")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
    }
}

/** စာတန်းကို Android စာလုံးဆွဲစနစ်နဲ့ ဆွဲ (preview နဲ့ render နှစ်ခုလုံး ဒီတစ်ခုတည်းသုံး) */
object CaptionRenderer {
    private fun withAlpha(c: Int, pct: Int): Int =
        Color.argb(255 * pct.coerceIn(0, 100) / 100, Color.red(c), Color.green(c), Color.blue(c))

    fun render(ctx: Context, text: String, st: CaptionStyle, frameW: Int, frameH: Int): Bitmap? {
        val t = text.trim()
        if (t.isEmpty() || frameW < 20 || frameH < 20) return null
        val minDim = minOf(frameW, frameH).toFloat()
        val size = (minDim * st.sizePct / 100f).coerceAtLeast(10f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = FontStore.typeface(ctx, st.font)
        paint.textSize = size
        paint.isFakeBoldText = st.bold
        paint.strokeJoin = Paint.Join.ROUND
        val stroke = if (st.outlineW > 0.001f) size * st.outlineW else 0f
        val padX = size * 0.45f
        val padY = size * 0.2f
        val maxW = frameW * 0.92f
        val textW = (maxW - 2f * padX - 2f * stroke).toInt().coerceAtLeast(40)
        val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, textW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        var lw = 1f
        for (i in 0 until layout.lineCount) lw = maxOf(lw, layout.getLineWidth(i))
        val w = (lw + 2f * padX + 2f * stroke).toInt().coerceAtLeast(2)
        val h = (layout.height + 2f * padY + 2f * stroke).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (st.bgColor != 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG)
            bp.color = withAlpha(st.bgColor, st.bgAlpha)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), size * 0.3f, size * 0.3f, bp)
        }
        c.save()
        c.translate((w - textW) / 2f, padY + stroke)
        if (stroke > 0.5f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke * 2f
            paint.color = st.outlineColor
            layout.draw(c)
        }
        paint.style = Paint.Style.FILL
        paint.color = st.textColor
        layout.draw(c)
        c.restore()
        return bmp
    }
}
