package dev.blocks.android.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.blocks.android.project.ProjectCoverContent
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

class PreparedProjectCoverCrop internal constructor(
    internal val source: File?,
    val preview: Bitmap,
)

interface ProjectCoverCropper {
    fun prepare(uri: Uri): PreparedProjectCoverCrop
    fun confirm(
        projectId: String,
        prepared: PreparedProjectCoverCrop,
        selection: CoverCropSelection,
    ): ProjectCoverContent

    fun discard(prepared: PreparedProjectCoverCrop)
    fun cleanupAbandonedSources(): Int
}

/** Owns plaintext input only for the lifetime of one explicit crop decision. */
class AndroidProjectCoverCropper(
    context: Context,
    private val processor: ProjectCoverImageProcessor = ProjectCoverImageProcessor(),
    private val now: () -> String = { Instant.now().toString() },
) : ProjectCoverCropper {
    private val resolver = context.applicationContext.contentResolver
    private val files = CoverImportFiles(context.applicationContext)

    override fun prepare(uri: Uri): PreparedProjectCoverCrop {
        files.cleanupOrphans()
        val source = files.createRetained()
        try {
            requireNotNull(resolver.openInputStream(uri)) { "The selected image is unavailable" }.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    try {
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= ProjectCoverImageProcessor.MAX_COMPRESSED_BYTES) {
                                "The selected image is too large"
                            }
                            output.write(buffer, 0, read)
                        }
                        require(total > 0) { "The selected image is empty" }
                        output.fd.sync()
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
            return PreparedProjectCoverCrop(source, processor.preview(source))
        } catch (error: Throwable) {
            files.delete(source)
            throw error
        }
    }

    override fun confirm(
        projectId: String,
        prepared: PreparedProjectCoverCrop,
        selection: CoverCropSelection,
    ): ProjectCoverContent {
        val source = requireNotNull(prepared.source) { "The cover crop source is unavailable" }
        return try {
            processor.process(projectId, source, now(), selection)
        } finally {
            discard(prepared)
        }
    }

    override fun discard(prepared: PreparedProjectCoverCrop) {
        if (!prepared.preview.isRecycled) prepared.preview.recycle()
        prepared.source?.let(files::delete)
    }

    override fun cleanupAbandonedSources(): Int = files.cleanupOrphans()

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
    }
}

/** Fixed 16:9 crop viewport. Drag positions the image; pinch changes crop scale. */
class ProjectCoverCropView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                clampCenter()
                invalidate()
                return true
            }
        },
    )

    init {
        contentDescription = "Project cover crop. Drag to position and pinch to scale."
        isFocusable = true
    }

    fun show(preview: Bitmap) {
        bitmap = preview
        centerX = 0.5f
        centerY = 0.5f
        zoom = 1f
        invalidate()
    }

    fun selection(): CoverCropSelection = CoverCropSelection(centerX, centerY, zoom)

    internal fun setSelectionForTest(selection: CoverCropSelection) {
        centerX = selection.centerX
        centerY = selection.centerY
        zoom = selection.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        clampCenter()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val desiredHeight = (width / TARGET_ASPECT).roundToInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val source = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val region = sourceRegion(source)
        canvas.drawBitmap(source, region, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                val source = bitmap ?: return true
                val region = sourceRegion(source)
                centerX -= (event.x - lastX) / width.coerceAtLeast(1) * region.width() / source.width
                centerY -= (event.y - lastY) / height.coerceAtLeast(1) * region.height() / source.height
                lastX = event.x
                lastY = event.y
                clampCenter()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun sourceRegion(source: Bitmap): Rect {
        val sourceAspect = source.width.toFloat() / source.height
        val baseWidth: Float
        val baseHeight: Float
        if (sourceAspect > TARGET_ASPECT) {
            baseHeight = source.height.toFloat()
            baseWidth = baseHeight * TARGET_ASPECT
        } else {
            baseWidth = source.width.toFloat()
            baseHeight = baseWidth / TARGET_ASPECT
        }
        val cropWidth = (baseWidth / zoom).coerceAtLeast(1f)
        val cropHeight = (baseHeight / zoom).coerceAtLeast(1f)
        val left = (centerX * source.width - cropWidth / 2f).coerceIn(0f, source.width - cropWidth)
        val top = (centerY * source.height - cropHeight / 2f).coerceIn(0f, source.height - cropHeight)
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + cropWidth).roundToInt().coerceAtMost(source.width),
            (top + cropHeight).roundToInt().coerceAtMost(source.height),
        )
    }

    private fun clampCenter() {
        val source = bitmap ?: return
        val region = sourceRegion(source)
        centerX = centerX.coerceIn(region.width() / 2f / source.width, 1f - region.width() / 2f / source.width)
        centerY = centerY.coerceIn(region.height() / 2f / source.height, 1f - region.height() / 2f / source.height)
    }

    private companion object {
        const val TARGET_ASPECT = 16f / 9f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}
