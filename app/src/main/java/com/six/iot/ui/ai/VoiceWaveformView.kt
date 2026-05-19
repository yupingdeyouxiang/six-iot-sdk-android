package com.six.iot.ui.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.*

class VoiceWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10A37F")
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private var amplitudes = mutableListOf<Float>()
    private val lineCount = 35
    private var isAnimating = false
    private val random = Random()
    private var currentAmplitude = 0.1f

    fun startAnimation() {
        isAnimating = true
        invalidate()
    }

    fun stopAnimation() {
        isAnimating = false
        amplitudes.clear()
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        if (!isAnimating) return
        // 映射音量到合理的显示范围 (0.1 - 1.0)
        currentAmplitude = (amplitude * 12f).coerceIn(0.1f, 1.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        val spacing = w / lineCount
        
        // 线条宽度
        paint.strokeWidth = spacing * 0.6f

        if (amplitudes.size < lineCount) {
            for (i in 0 until lineCount) amplitudes.add(0.1f)
        }

        for (i in 0 until lineCount) {
            // 在当前音量基础上增加随机扰动，模拟真实的“跳动”感
            val target = currentAmplitude * (0.4f + random.nextFloat() * 0.6f)
            // 平滑线条过渡
            amplitudes[i] = amplitudes[i] + (target - amplitudes[i]) * 0.4f
            
            val x = i * spacing + spacing / 2
            val lineHeight = h * 0.85f * amplitudes[i]
            
            // 绘制居中的对称线条
            canvas.drawLine(x, centerY - lineHeight / 2f, x, centerY + lineHeight / 2f, paint)
        }

        // 持续刷新
        postInvalidateDelayed(40)
    }
}
