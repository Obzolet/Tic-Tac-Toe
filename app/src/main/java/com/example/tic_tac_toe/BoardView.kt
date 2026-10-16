package com.example.tic_tac_toe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * View personalizada que dibuja el tablero:
 *  - cada casilla es un rectángulo redondeado (relleno blanco + borde de 1dp) dibujado con [Canvas.drawRoundRect];
 *  - las X y las O son bitmaps (res/drawable-nodpi/mark_x.png y mark_o.png) dibujados con [Canvas.drawBitmap];
 *  - debajo del tablero dibuja el contador "n of 9 squares filled".
 *
 * Solo muestra el estado: la lógica vive en [TicTacToeGame] y los toques se notifican con [onCellClickListener].
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Se invoca con el índice (0..8) de la casilla tocada. */
    var onCellClickListener: ((Int) -> Unit)? = null

    /** Texto bajo el tablero, p. ej. "3 of 9 squares filled". */
    var countText: String = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            invalidate()
        }

    private val marks = IntArray(TicTacToeGame.SIZE)

    // Medidas del diseño (dp)
    private val cellGap = dp(12f)
    private val cellRadius = dp(12f)
    private val borderWidth = dp(1f)

    // Configurables desde XML (distintos en vertical y horizontal)
    private var boardAspect = 1f
    private var markScale = 0.4f
    private var countSpacing = dp(20f)

    private val cellColor = ContextCompat.getColor(context, R.color.board_cell)
    private val cellPressedColor = ContextCompat.getColor(context, R.color.board_cell_pressed)

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = ContextCompat.getColor(context, R.color.outline)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Los PNG de X y O son de un solo color: se tiñen con el color del modo claro/oscuro actual.
    private val filterX = PorterDuffColorFilter(ContextCompat.getColor(context, R.color.mark_x), PorterDuff.Mode.SRC_IN)
    private val filterO = PorterDuffColorFilter(ContextCompat.getColor(context, R.color.mark_o), PorterDuff.Mode.SRC_IN)
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
        typeface = Typeface.DEFAULT
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    // Bitmaps originales (240 x 252 px) y copias escaladas al tamaño exacto de la casilla actual
    private val sourceX = decodeBitmap(R.drawable.mark_x)
    private val sourceO = decodeBitmap(R.drawable.mark_o)
    private var scaledX: Bitmap? = null
    private var scaledO: Bitmap? = null

    // Geometría calculada en onSizeChanged
    private val cellRects = Array(TicTacToeGame.SIZE) { RectF() }
    private val borderRect = RectF()
    private var countX = 0f
    private var countBaseline = 0f

    private var pressedCell = -1

    // Animación de aparición de la última ficha
    private var animIndex = -1
    private var animValue = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220
        interpolator = OvershootInterpolator(1.6f)
        addUpdateListener {
            animValue = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animIndex = -1
                invalidate()
            }
        })
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.BoardView)
        try {
            boardAspect = a.getFloat(R.styleable.BoardView_boardAspect, boardAspect)
            markScale = a.getFloat(R.styleable.BoardView_markScale, markScale)
            countSpacing = a.getDimension(R.styleable.BoardView_countSpacing, countSpacing)
        } finally {
            a.recycle()
        }
    }

    /**
     * Actualiza las casillas (0 = vacía, 1 = X, 2 = O).
     * Si [animateIndex] es válido, esa ficha aparece con una pequeña animación.
     */
    fun setMarks(newMarks: IntArray, animateIndex: Int = -1) {
        newMarks.copyInto(marks, endIndex = min(newMarks.size, marks.size))
        animator.cancel()
        animIndex = -1
        if (animateIndex in marks.indices && marks[animateIndex] != TicTacToeGame.EMPTY) {
            animIndex = animateIndex
            animValue = 0f
            animator.start()
        }
        invalidate()
    }

    // ---------------------------------------------------------------- medición y geometría

    private fun reservedHeight(): Float = countSpacing + countPaint.textSize * COUNT_LINE_HEIGHT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        val reserved = reservedHeight()
        val maxW = if (wMode == MeasureSpec.UNSPECIFIED) dp(364f) else wSize.toFloat()
        val maxBoardH =
            if (hMode == MeasureSpec.UNSPECIFIED) dp(360f) else max(0f, hSize - reserved)

        // El tablero ocupa lo máximo posible respetando su proporción.
        val boardH = min(maxBoardH, maxW / boardAspect)
        val boardW = boardH * boardAspect

        val width = if (wMode == MeasureSpec.EXACTLY) wSize else ceil(boardW).toInt()
        val height = if (hMode == MeasureSpec.EXACTLY) hSize else ceil(boardH + reserved).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val reserved = reservedHeight()
        val boardH = max(0f, min(h - reserved, w / boardAspect))
        val boardW = boardH * boardAspect

        // El bloque (tablero + contador) queda centrado en la View.
        val left = (w - boardW) / 2f
        val top = (h - (boardH + reserved)) / 2f

        val cellW = max(0f, (boardW - 2 * cellGap) / 3f)
        val cellH = max(0f, (boardH - 2 * cellGap) / 3f)
        for (i in cellRects.indices) {
            val l = left + (i % 3) * (cellW + cellGap)
            val t = top + (i / 3) * (cellH + cellGap)
            cellRects[i].set(l, t, l + cellW, t + cellH)
        }

        // Línea del contador centrada verticalmente en su caja de 1.4 x tamaño de letra
        val lineHeight = countPaint.textSize * COUNT_LINE_HEIGHT
        val lineTop = top + boardH + countSpacing
        val fm = countPaint.fontMetrics
        countX = left + boardW / 2f
        countBaseline = lineTop + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent

        // Bitmaps al tamaño exacto: nítidos sin depender del filtrado en cada frame
        val markH = max(1, (cellH * markScale).roundToInt())
        val markW = max(1, (markH * MARK_ASPECT).roundToInt())
        scaledX = Bitmap.createScaledBitmap(sourceX, markW, markH, true)
        scaledO = Bitmap.createScaledBitmap(sourceO, markW, markH, true)
    }

    // ---------------------------------------------------------------- dibujo

    override fun onDraw(canvas: Canvas) {
        val half = borderWidth / 2f
        for (i in cellRects.indices) {
            val rect = cellRects[i]

            // Casilla: rectángulo redondeado blanco con borde de 1dp (dentro del rectángulo, como en Figma)
            val pressed = i == pressedCell && marks[i] == TicTacToeGame.EMPTY && isEnabled
            cellPaint.color = if (pressed) cellPressedColor else cellColor
            canvas.drawRoundRect(rect, cellRadius, cellRadius, cellPaint)
            borderRect.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
            canvas.drawRoundRect(borderRect, cellRadius - half, cellRadius - half, borderPaint)

            // Ficha: bitmap centrado en la casilla
            val bitmap = when (marks[i]) {
                TicTacToeGame.X -> scaledX
                TicTacToeGame.O -> scaledO
                else -> null
            }
            if (bitmap != null) {
                bitmapPaint.colorFilter = if (marks[i] == TicTacToeGame.X) filterX else filterO
                drawMark(canvas, bitmap, rect, animating = i == animIndex)
            }
        }

        canvas.drawText(countText, countX, countBaseline, countPaint)
    }

    private fun drawMark(canvas: Canvas, bitmap: Bitmap, rect: RectF, animating: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val left = (cx - bitmap.width / 2f).roundToInt().toFloat()
        val top = (cy - bitmap.height / 2f).roundToInt().toFloat()

        if (!animating) {
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bitmap, left, top, bitmapPaint)
            return
        }
        val scale = 0.6f + 0.4f * animValue
        bitmapPaint.alpha = (255 * min(1f, animValue * 2f)).roundToInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawBitmap(bitmap, left, top, bitmapPaint)
        canvas.restore()
        bitmapPaint.alpha = 255
    }

    // ---------------------------------------------------------------- toques

    private fun cellAt(x: Float, y: Float): Int {
        for (i in cellRects.indices) if (cellRects[i].contains(x, y)) return i
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedCell = cellAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedCell != -1 && cellAt(event.x, event.y) != pressedCell) {
                    pressedCell = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cell = cellAt(event.x, event.y)
                val tapped = cell != -1 && cell == pressedCell
                pressedCell = -1
                invalidate()
                if (tapped) {
                    performClick()
                    onCellClickListener?.invoke(cell)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedCell = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- utilidades

    private fun decodeBitmap(resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(resources, resId, options)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /** Alto de línea del contador en el diseño: 14sp x 1.4. */
        const val COUNT_LINE_HEIGHT = 1.4f

        /** Proporción ancho/alto de los bitmaps de X y O (40 x 42). */
        const val MARK_ASPECT = 40f / 42f
    }
}
