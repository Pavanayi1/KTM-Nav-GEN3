package com.navigator.app.controller

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.navigator.app.settings.AppSettings

/**
 * A small heads-up overlay, drawn on top of whatever app is in front, that lets
 * the rider pick what the handlebar remote does: Gamepad (control phone apps),
 * Media (play/pause/skip), or Dash menu (Navigator Gen3's own UI). It is driven
 * entirely by the handlebar — Up/Down move the selection, Set confirms, Back
 * closes — so it's usable with gloves, eyes-on-road. Opened by triple-pressing
 * Up (handled in the connection service).
 *
 * Built as plain Views (not Compose) because it lives in a WindowManager overlay
 * owned by a Service. All methods must be called on the main thread.
 */
class RemoteModeOverlay(
    private val context: Context,
    private val onModeChosen: (String) -> Unit,
) {
    private data class Mode(val id: String, val title: String, val subtitle: String)

    private val modes = listOf(
        Mode(AppSettings.MODE_CONTROLLER, "Controller", "D-pad for the phone"),
        Mode(AppSettings.MODE_MUSIC, "Music", "Volume & tracks"),
        Mode(AppSettings.MODE_INTERCOM, "Intercom", "Hold SET to talk"),
        Mode(AppSettings.MODE_OFF, "Bike (off)", "Buttons control the bike"),
    )

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private val rows = ArrayList<LinearLayout>()
    private var selected = 0

    val isShowing: Boolean get() = root != null

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(currentMode: String) {
        if (root != null || !canShow()) return
        selected = modes.indexOfFirst { it.id == currentMode }.coerceAtLeast(0)

        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(20).toFloat()
                setColor(Color.parseColor("#F2111316"))
                setStroke(px(1), Color.parseColor("#33FFFFFF"))
            }
        }
        panel.addView(TextView(context).apply {
            text = "REMOTE MODE"
            setTextColor(Color.parseColor("#FFFF6600"))
            textSize = 13f
            letterSpacing = 0.15f
            setPadding(px(6), 0, 0, px(10))
        })
        panel.addView(TextView(context).apply {
            text = "Up/Down choose · Set confirm · Back close"
            setTextColor(Color.parseColor("#8AFFFFFF"))
            textSize = 11f
            setPadding(px(6), 0, 0, px(12))
        })

        rows.clear()
        modes.forEach { mode ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), px(10), px(14), px(10))
                val lp = LinearLayout.LayoutParams(px(240), LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = px(8)
                layoutParams = lp
            }
            row.addView(TextView(context).apply {
                text = mode.title; setTextColor(Color.WHITE); textSize = 17f
            })
            row.addView(TextView(context).apply {
                text = mode.subtitle; setTextColor(Color.parseColor("#8AFFFFFF")); textSize = 12f
            })
            rows.add(row)
            panel.addView(row)
        }

        val container = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(panel)
        }
        root = container
        highlight()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Not focusable so it doesn't grab the keyboard/IME; we drive it with
            // the handlebar. Dim the app behind so it reads as a modal.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { dimAmount = 0.55f; gravity = Gravity.CENTER }

        runCatching { wm.addView(container, params) }
    }

    fun moveSelection(delta: Int) {
        if (root == null) return
        selected = ((selected + delta) % modes.size + modes.size) % modes.size
        highlight()
    }

    fun confirm() {
        val mode = modes.getOrNull(selected)?.id ?: return
        hide()
        onModeChosen(mode)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        rows.clear()
    }

    private fun highlight() {
        val dp = context.resources.displayMetrics.density
        rows.forEachIndexed { i, row ->
            row.background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                if (i == selected) {
                    setColor(Color.parseColor("#FFFF6600"))
                } else {
                    setColor(Color.parseColor("#141618"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#22FFFFFF"))
                }
            }
            (row.getChildAt(0) as TextView).setTextColor(if (i == selected) Color.parseColor("#0B0C0E") else Color.WHITE)
            (row.getChildAt(1) as TextView).setTextColor(if (i == selected) Color.parseColor("#B30B0C0E") else Color.parseColor("#8AFFFFFF"))
        }
    }
}
