package com.kgrecap

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Preview ပေါ်မှာ အုပ်ထားတဲ့ အလွှာ: Blur ၂ ကွက် + စာတန်း (လက်နဲ့ဆွဲရွှေ့)၊
 * ဘယ်အပေါ်ထောင့်မှာ ခလုတ်တန်း၊ Blur density slider။
 */
class OverlayLayer(ctx: Context, private val overlay: Overlay) : FrameLayout(ctx) {
    private val sample = "မင်္ဂလာပါ၊ ဒီစာတန်းပုံစံကို ကြည့်ပါ။"
    private var vw = 0
    private var vh = 0
    private var captionText = ""
    private var selKind = -1
    private var selIndex = 0

    private val canvasView = CanvasView(ctx)
    private val bBlur = arrayOf(smallButton("Blur 1"), smallButton("Blur 2"))
    private val bStyle = smallButton("Aa")
    private val bShow = smallButton("စာတန်း")
    private val bReset = smallButton("↺")
    private val seek = SeekBar(ctx)
    private val seekLabel = TextView(ctx)
    private val seekBox = LinearLayout(ctx)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun smallButton(t: String): Button {
        val b = Button(context)
        b.text = t
        b.textSize = 11f
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        val px = dp(8f).toInt()
        b.setPadding(px, dp(3f).toInt(), px, dp(3f).toInt())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32f).toInt())
        lp.setMargins(0, 0, dp(4f).toInt(), 0)
        b.layoutParams = lp
        return b
    }

    init {
        addView(
            canvasView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        for (b in bBlur) bar.addView(b)
        bar.addView(bStyle)
        bar.addView(bShow)
        bar.addView(bReset)

        seekLabel.setTextColor(Color.WHITE)
        seekLabel.textSize = 11f
        seekLabel.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        seek.max = 100
        seekBox.orientation = LinearLayout.VERTICAL
        seekBox.visibility = View.GONE
        seekBox.addView(seekLabel)
        seekBox.addView(seek, LinearLayout.LayoutParams(dp(210f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.VERTICAL
        top.addView(bar)
        top.addView(seekBox)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        lp.setMargins(dp(6f).toInt(), dp(6f).toInt(), 0, 0)
        addView(top, lp)

        bBlur[0].setOnClickListener { toggleBlur(0) }
        bBlur[1].setOnClickListener { toggleBlur(1) }
        bStyle.setOnClickListener { openStyle() }
        bShow.setOnClickListener {
            overlay.caption.show = !overlay.caption.show
            save()
            refreshButtons()
            canvasView.invalidate()
        }
        bReset.setOnClickListener { resetPositions() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || selKind != 0) return
                overlay.blurs[selIndex].density = p
                seekLabel.text = "Blur ${selIndex + 1} density: $p%"
                canvasView.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                save()
            }
        })
        refreshButtons()
    }

    fun setVideoSize(w: Int, h: Int) {
        vw = w
        vh = h
        canvasView.invalidateCaption()
    }

    fun setCaptionText(t: String) {
        captionText = t
        canvasView.invalidateCaption()
    }

    private fun save() = overlay.save(context)

    private fun refreshButtons() {
        val on = 0xCC2E7D32.toInt()
        val off = 0xCC37474F.toInt()
        bBlur[0].setBackgroundColor(if (overlay.blurs[0].on) on else off)
        bBlur[1].setBackgroundColor(if (overlay.blurs[1].on) on else off)
        bShow.setBackgroundColor(if (overlay.caption.show) on else off)
        bStyle.setBackgroundColor(0xCC1565C0.toInt())
        bReset.setBackgroundColor(off)
    }

    private fun toggleBlur(i: Int) {
        val b = overlay.blurs[i]
        b.on = !b.on
        if (b.on) {
            select(0, i)
        } else if (selKind == 0 && selIndex == i) {
            selKind = -1
        }
        updateSeekUi()
        refreshButtons()
        canvasView.invalidate()
        save()
    }

    private fun select(kind: Int, idx: Int) {
        selKind = kind
        selIndex = idx
        updateSeekUi()
        canvasView.invalidate()
    }

    private fun updateSeekUi() {
        if (selKind == 0 && overlay.blurs[selIndex].on) {
            seekBox.visibility = View.VISIBLE
            seek.progress = overlay.blurs[selIndex].density
            seekLabel.text = "Blur ${selIndex + 1} density: ${overlay.blurs[selIndex].density}%"
        } else {
            seekBox.visibility = View.GONE
        }
    }

    private fun resetPositions() {
        val d = Overlay.defaults()
        for (i in 0..1) {
            val b = overlay.blurs[i]
            val s = d.blurs[i]
            b.x = s.x
            b.y = s.y
            b.w = s.w
            b.h = s.h
        }
        overlay.caption.cx = d.caption.cx
        overlay.caption.cy = d.caption.cy
        save()
        canvasView.invalidate()
    }

    private fun openStyle() {
        val a = context as? Activity ?: return
        StyleDialog.show(a, overlay, if (captionText.isBlank()) sample else captionText) {
            save()
            canvasView.invalidateCaption()
        }
    }

    private inner class CanvasView(c: Context) : View(c) {
        private val content = RectF()
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pDash = Paint(Paint.ANTI_ALIAS_FLAG)
        private var capBmp: Bitmap? = null
        private var capKey = ""
        private var mode = 0 // 0 မဆွဲ၊ 1 ရွှေ့၊ 2 အရွယ်ပြောင်း
        private var dragKind = -1
        private var dragIdx = 0
        private var lastX = 0f
        private var lastY = 0f

        init {
            pStroke.style = Paint.Style.STROKE
            pText.color = 0xFF222222.toInt()
            pText.textSize = dp(11f)
            pDash.style = Paint.Style.STROKE
            pDash.color = Color.CYAN
            pDash.strokeWidth = dp(2f)
            pDash.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        }

        fun invalidateCaption() {
            capKey = ""
            invalidate()
        }

        private fun computeContent() {
            val w = width.toFloat()
            val h = height.toFloat()
            if (vw > 0 && vh > 0 && w > 0f && h > 0f) {
                val s = minOf(w / vw, h / vh)
                val cw = vw * s
                val ch = vh * s
                content.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
            } else {
                content.set(0f, 0f, w, h)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            invalidateCaption()
        }

        private fun blurRect(b: BlurSpec): RectF {
            val cw = content.width()
            val ch = content.height()
            return RectF(
                content.left + b.x * cw, content.top + b.y * ch,
                content.left + (b.x + b.w) * cw, content.top + (b.y + b.h) * ch
            )
        }

        private fun handleSize(r: RectF): Float = minOf(dp(28f), minOf(r.width(), r.height()) / 2f)

        private fun captionBitmap(): Bitmap? {
            val st = overlay.caption
            if (!st.show) return null
            val text = if (captionText.isBlank()) sample else captionText
            val cw = content.width().toInt()
            val ch = content.height().toInt()
            if (cw < 20 || ch < 20) return null
            val key = st.styleKey() + "|" + text + "|" + cw + "x" + ch
            if (key != capKey) {
                capBmp = CaptionRenderer.render(context, text, st, cw, ch)
                capKey = key
            }
            return capBmp
        }

        private fun captionRect(): RectF? {
            val b = captionBitmap() ?: return null
            val st = overlay.caption
            var l = content.left + st.cx * content.width() - b.width / 2f
            var t = content.top + st.cy * content.height() - b.height / 2f
            l = l.coerceIn(content.left, maxOf(content.left, content.right - b.width))
            t = t.coerceIn(content.top, maxOf(content.top, content.bottom - b.height))
            return RectF(l, t, l + b.width, t + b.height)
        }

        override fun onDraw(c: Canvas) {
            computeContent()
            for (i in 0..1) {
                val b = overlay.blurs[i]
                if (!b.on) continue
                val r = blurRect(b)
                val sel = selKind == 0 && selIndex == i
                pFill.color = Color.argb(60 + b.density * 130 / 100, 235, 235, 235)
                c.drawRoundRect(r, dp(4f), dp(4f), pFill)
                pStroke.strokeWidth = if (sel) dp(3f) else dp(1.5f)
                pStroke.color = if (sel) Color.CYAN else Color.WHITE
                c.drawRoundRect(r, dp(4f), dp(4f), pStroke)
                c.drawText("Blur ${i + 1} · ${b.density}%", r.left + dp(6f), r.top + dp(16f), pText)
                val hs = handleSize(r)
                pFill.color = if (sel) Color.CYAN else Color.WHITE
                c.drawRect(r.right - hs, r.bottom - hs, r.right, r.bottom, pFill)
            }
            val bmp = captionBitmap()
            val cr = captionRect()
            if (bmp != null && cr != null) {
                c.drawBitmap(bmp, cr.left, cr.top, null)
                if (selKind == 1) c.drawRect(cr, pDash)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    computeContent()
                    val x = e.x
                    val y = e.y
                    mode = 0
                    val cr = captionRect()
                    if (cr != null && cr.contains(x, y)) {
                        dragKind = 1
                        dragIdx = 0
                        mode = 1
                        select(1, 0)
                    } else {
                        for (i in 1 downTo 0) {
                            val b = overlay.blurs[i]
                            if (!b.on) continue
                            val r = blurRect(b)
                            val hs = handleSize(r)
                            if (RectF(r.right - hs, r.bottom - hs, r.right, r.bottom).contains(x, y)) {
                                dragKind = 0
                                dragIdx = i
                                mode = 2
                                select(0, i)
                                break
                            }
                            if (r.contains(x, y)) {
                                dragKind = 0
                                dragIdx = i
                                mode = 1
                                select(0, i)
                                break
                            }
                        }
                    }
                    if (mode == 0) return false
                    lastX = x
                    lastY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 0) return false
                    drag(e.x, e.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode != 0) {
                        mode = 0
                        save()
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun drag(x: Float, y: Float) {
            val cw = content.width().coerceAtLeast(1f)
            val ch = content.height().coerceAtLeast(1f)
            val dx = (x - lastX) / cw
            val dy = (y - lastY) / ch
            lastX = x
            lastY = y
            if (dragKind == 1) {
                val st = overlay.caption
                st.cx = (st.cx + dx).coerceIn(0.02f, 0.98f)
                st.cy = (st.cy + dy).coerceIn(0.02f, 0.98f)
            } else {
                val b = overlay.blurs[dragIdx]
                if (mode == 1) {
                    b.x = (b.x + dx).coerceIn(0f, 1f - b.w)
                    b.y = (b.y + dy).coerceIn(0f, 1f - b.h)
                } else {
                    b.w = (b.w + dx).coerceIn(0.10f, 1f - b.x)
                    b.h = (b.h + dy).coerceIn(0.04f, 1f - b.y)
                }
            }
            invalidate()
        }
    }
}
