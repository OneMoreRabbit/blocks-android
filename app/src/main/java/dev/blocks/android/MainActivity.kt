package dev.blocks.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val spacing = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundColor(Color.rgb(246, 243, 234))
        }

        content.addView(label(getString(R.string.heading), 28f, Color.rgb(32, 48, 42)))
        content.addView(label(getString(R.string.status_ready), 18f, Color.rgb(53, 104, 89)))
        content.addView(label(getString(R.string.contract_pin, BuildConfig.BLOCKS_SERVICE_API_VERSION), 14f))
        content.addView(label(getString(R.string.service_endpoint, BuildConfig.BLOCKS_API_BASE_URL), 14f))
        setContentView(content)
    }

    private fun label(text: String, size: Float, color: Int = Color.DKGRAY) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    }
}
