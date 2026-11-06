package com.navigator.app.controller

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import com.navigator.app.ui.AppForegroundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Turns the handlebar remote into a **system-wide gamepad**. This is the only
 * sanctioned Android way for one app to drive navigation inside *other* apps:
 * an AccessibilityService can move accessibility focus, click the focused
 * element, and perform global Back/Home/Recents.
 *
 * Button mapping (only while Navigator is NOT the foreground app - when it is,
 * MainActivity's own handler drives Navigator's UI, so the two never fight):
 *  - UP    -> move focus to the previous focusable element (or scroll up)
 *  - DOWN  -> move focus to the next focusable element (or scroll down)
 *  - SET   -> click the focused element
 *  - BACK  -> global Back
 *
 * It never reads screen content for any purpose other than finding the next
 * focusable node to move the selection to.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    companion object {
        /** True while the service is connected/enabled by the user. */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * Whether the rider still has our accessibility service ticked in
         * Android's own settings. [isRunning] only tells us about THIS process:
         * it's false whenever the process was restarted and the service hasn't
         * rebound yet, and - the case that matters on the bike - it can't
         * distinguish that from the OEM battery manager having quietly switched
         * the service off, which is how the handlebar gamepad goes dead
         * mid-ride with no sign on the dash.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, RemoteControlAccessibilityService::class.java)
            return enabled.split(':').any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c.packageName == me.packageName && c.className == me.className
            }
        }
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        AppLogger.log("Gamepad", "Accessibility service connected - handlebar gamepad available")
        collectJob = s.launch {
            BccuConnectionService.gestureEvents.collect { gesture ->
                try {
                    handleGesture(gesture)
                } catch (e: Exception) {
                    AppLogger.log("Gamepad", "!! handleGesture threw: $e")
                }
            }
        }
        // Debug-only: let `adb shell am broadcast -a com.navigator.app.DEBUG_GAMEPAD --es button UP`
        // inject a button so the gamepad can be exercised on an emulator with no bike.
        // Compiled out of release builds.
        if (com.navigator.app.BuildConfig.DEBUG) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val name = i?.getStringExtra("button")?.uppercase() ?: return
                    val b = BccuProtocol.HandlebarButton.entries.firstOrNull { it.name == name } ?: return
                    val kindName = i.getStringExtra("kind")?.uppercase()
                    val kind = GestureKind.entries.firstOrNull { it.name == kindName } ?: GestureKind.SINGLE
                    AppLogger.log("Gamepad", "DEBUG inject $name $kind")
                    handleGesture(RemoteGesture(b, kind))
                }
            }
            debugReceiver = r
            val filter = android.content.IntentFilter("com.navigator.app.DEBUG_GAMEPAD")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, filter)
            }
        }
    }

    // We track the selection index ourselves over the on-screen actionable
    // nodes, rather than relying on the system's accessibility focus round-trip
    // (findFocus() often returns null between presses on real screens, which
    // would make focus never advance). Reset whenever the window changes.
    private var selectionIndex = -1
    private var lastWindowId = -1
    private var lastPressAtMs = 0L

    private enum class Dir { UP, DOWN, LEFT, RIGHT }

    private fun handleGesture(g: RemoteGesture) {
        lastPressAtMs = System.currentTimeMillis()
        // Only act as a system gamepad in Controller mode. NOTE: unlike before we
        // do NOT bail when Navigator is foregrounded - Controller mode is now a
        // universal D-pad (its own Compose UI is accessibility-navigable too), and
        // it must also work over the lock screen for PIN entry.
        if (AppSettings(this).remoteMode != AppSettings.MODE_CONTROLLER) {
            hideHighlight()
            return
        }

        val btn = g.button
        val kind = g.kind
        when {
            btn == BccuProtocol.HandlebarButton.UP && kind == GestureKind.SINGLE -> moveDirection(Dir.UP)
            btn == BccuProtocol.HandlebarButton.DOWN && kind == GestureKind.SINGLE -> moveDirection(Dir.DOWN)
            btn == BccuProtocol.HandlebarButton.SET && kind == GestureKind.SINGLE -> moveDirection(Dir.RIGHT)
            btn == BccuProtocol.HandlebarButton.BACK && kind == GestureKind.SINGLE -> moveDirection(Dir.LEFT)
            btn == BccuProtocol.HandlebarButton.SET && kind == GestureKind.DOUBLE -> clickSelection()
            btn == BccuProtocol.HandlebarButton.SET && kind == GestureKind.LONG -> longClickSelection()
            btn == BccuProtocol.HandlebarButton.BACK && kind == GestureKind.DOUBLE -> {
                AppLogger.log("Gamepad", "BACK double -> global back")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            else -> AppLogger.log("Gamepad", "unmapped gesture $g")
        }
    }

    private fun centerX(n: AccessibilityNodeInfo): Int { val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.centerX() }
    private fun centerY(n: AccessibilityNodeInfo): Int { val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.centerY() }

    /**
     * Move the highlight to the nearest actionable node in [dir] (a true D-pad
     * over the on-screen elements). We keep our own selection index (drawn via a
     * custom highlight box, since ACTION_ACCESSIBILITY_FOCUS renders nothing
     * without TalkBack). At a vertical edge with nothing further, scroll to
     * reveal more.
     */
    private fun moveDirection(dir: Dir) {
        val root = rootInActiveWindow ?: run { AppLogger.log("Gamepad", "move: no active window"); return }
        resetIfWindowChanged(root)
        val nodes = collectActionable(root)
        if (nodes.isEmpty()) { AppLogger.log("Gamepad", "move: no actionable nodes"); return }

        // First press after a window change just seeds a visible selection.
        if (selectionIndex !in nodes.indices) {
            selectionIndex = 0
            select(nodes, root)
            return
        }

        val cur = nodes[selectionIndex]
        val cx = centerX(cur); val cy = centerY(cur)
        var best = -1; var bestDist = Long.MAX_VALUE
        nodes.forEachIndexed { i, n ->
            if (i == selectionIndex) return@forEachIndexed
            val dx = (centerX(n) - cx); val dy = (centerY(n) - cy)
            val matches = when (dir) {
                Dir.UP -> dy < 0 && kotlin.math.abs(dy) >= kotlin.math.abs(dx)
                Dir.DOWN -> dy > 0 && kotlin.math.abs(dy) >= kotlin.math.abs(dx)
                Dir.LEFT -> dx < 0 && kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                Dir.RIGHT -> dx > 0 && kotlin.math.abs(dx) >= kotlin.math.abs(dy)
            }
            if (!matches) return@forEachIndexed
            val dist = dx.toLong() * dx + dy.toLong() * dy
            if (dist < bestDist) { bestDist = dist; best = i }
        }

        if (best >= 0) {
            selectionIndex = best
            select(nodes, root)
        } else if (dir == Dir.UP || dir == Dir.DOWN) {
            // Nothing further in that direction: scroll to reveal more.
            val scrollable = firstScrollable(root) ?: return
            val act = if (dir == Dir.UP) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val ok = scrollable.performAction(act)
            AppLogger.log("Gamepad", "move $dir: at edge -> scroll ${if (ok) "ok" else "none"}")
            selectionIndex = -1
        }
    }

    /** Focus + scroll-into-view + draw the highlight for the current selection. */
    private fun select(nodes: List<AccessibilityNodeInfo>, @Suppress("UNUSED_PARAMETER") root: AccessibilityNodeInfo) {
        val node = nodes[selectionIndex]
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        showHighlight(node)
        AppLogger.log("Gamepad", "select ${selectionIndex + 1}/${nodes.size} ${describe(node)}")
    }

    // --- Visible selection highlight (accessibility overlay) ---

    private var highlightView: android.view.View? = null

    /** Draw (or move) an orange rounded-rect outline over [node]'s screen bounds. */
    private fun showHighlight(node: AccessibilityNodeInfo) {
        try {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val view = highlightView ?: android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((4 * resources.displayMetrics.density).toInt(), 0xFFFF6600.toInt())
                    cornerRadius = 12 * resources.displayMetrics.density
                    setColor(0x22FF6600)
                }
            }.also { highlightView = it }
            val lp = android.view.WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    // getBoundsInScreen() is in absolute screen coordinates; without
                    // this the window is positioned relative to the area below the
                    // status bar, drawing the box ~a status-bar too low everywhere.
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = bounds.left
                y = bounds.top
                if (android.os.Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            }
            if (view.parent == null) wm.addView(view, lp) else wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            AppLogger.log("Gamepad", "!! highlight failed: $e")
        }
    }

    private fun hideHighlight() {
        val view = highlightView ?: return
        highlightView = null
        runCatching {
            if (view.parent != null) (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view)
        }
    }

    /** Click the currently-selected node (walking up to a clickable ancestor). */
    private fun clickSelection() {
        val root = rootInActiveWindow ?: return
        resetIfWindowChanged(root)
        val nodes = collectActionable(root)
        if (nodes.isEmpty()) { AppLogger.log("Gamepad", "SET: nothing to click"); return }
        if (selectionIndex !in nodes.indices) selectionIndex = 0
        var target: AccessibilityNodeInfo? = nodes[selectionIndex]
        while (target != null && !target.isClickable) target = target.parent
        val hit = target ?: nodes[selectionIndex]
        val ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLogger.log("Gamepad", "SELECT -> click ${describe(hit)} ${if (ok) "" else "(failed, tapping)"}")
        if (!ok) {
            // ACTION_CLICK fails on launcher icons / custom views that aren't
            // reported clickable - dispatch a real tap at the node's centre so it
            // opens anyway. This is the fix for "highlights the app but won't open it".
            tapNodeCenter(nodes[selectionIndex])
        }
    }

    /** Dispatch a real touch tap at the centre of [node] (fallback when ACTION_CLICK is refused). */
    private fun tapNodeCenter(node: AccessibilityNodeInfo) {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) { AppLogger.log("Gamepad", "tap: node has no bounds"); return }
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        AppLogger.log("Gamepad", "tap ${if (dispatched) "dispatched" else "FAILED"} at ${r.centerX()},${r.centerY()}")
    }

    /** SET long-press: long-click the highlighted item (context menu / app-icon
     * shortcuts). Tries ACTION_LONG_CLICK, then falls back to a held tap gesture. */
    private fun longClickSelection() {
        val root = rootInActiveWindow ?: return
        resetIfWindowChanged(root)
        val nodes = collectActionable(root)
        if (nodes.isEmpty()) { AppLogger.log("Gamepad", "long-press: nothing selected"); return }
        if (selectionIndex !in nodes.indices) selectionIndex = 0
        val node = nodes[selectionIndex]
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isLongClickable) target = target.parent
        val hit = target
        val ok = hit?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
        AppLogger.log("Gamepad", "SET long -> long-click ${describe(node)} ${if (ok) "" else "(fallback tap)"}")
        if (!ok) {
            // Held tap gesture as fallback (600ms = system long-press threshold).
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return
            val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 600))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    private fun resetIfWindowChanged(root: AccessibilityNodeInfo) {
        val wid = root.windowId
        if (wid != lastWindowId) {
            lastWindowId = wid
            selectionIndex = -1
            hideHighlight()
        }
    }

    /**
     * Flatten the node tree to a reading-ordered list of things worth selecting:
     * on-screen, enabled, and either clickable/checkable or a focusable node that
     * carries a label. Nested actionables keep the INNERMOST nodes: selecting the
     * actual small buttons, not the whole-screen container around them (the R20
     * field test hit full-page "Home screen 1"-sized selections that made SET
     * unpredictable). A container with no actionable descendants is itself the
     * leaf, so plain list rows still select as a row.
     */
    private fun collectActionable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val minSidePx = (12 * resources.displayMetrics.density).toInt()
        val bounds = android.graphics.Rect()
        // Returns true when this subtree contributed at least one selectable.
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            var childTook = false
            for (i in 0 until node.childCount) {
                if (visit(node.getChild(i))) childTook = true
            }
            if (childTook) return true // descendants are the finer-grained targets
            val onScreen = node.isVisibleToUser && node.isEnabled
            if (!onScreen) return false
            val labelled = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val actionable = node.isClickable || node.isCheckable || (node.isFocusable && labelled)
            if (!actionable) return false
            // Skip sub-finger-size targets (decorative 1px views showed up as
            // unlabelled [View] selections that appeared to do nothing).
            node.getBoundsInScreen(bounds)
            if (bounds.width() < minSidePx || bounds.height() < minSidePx) return false
            out.add(node)
            return true
        }
        visit(root)
        return out
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            firstScrollable(c)?.let { return it }
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.take(24)
        val desc = node.contentDescription?.toString()?.take(24)
        val cls = node.className?.toString()?.substringAfterLast('.')
        return "[$cls${if (!text.isNullOrBlank()) " '$text'" else if (!desc.isNullOrBlank()) " '$desc'" else ""}]"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Remove the selection box only when the screen GENUINELY changed
        // underneath it (another app came up, SET opened a new screen). The
        // R20 field test showed it vanishing instantly after every press:
        // WINDOW_STATE_CHANGED also fires for our own overlay being added and
        // for cosmetic same-window events (Maps re-posts constantly), and the
        // old unconditional hide + index reset made the selector unusable.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || highlightView == null) return
        if (event.packageName == packageName) return // our own highlight/mode-picker windows
        if (System.currentTimeMillis() - lastPressAtMs < 1200) return // change WE caused (click/scroll)
        val activeWid = rootInActiveWindow?.windowId ?: return
        if (activeWid == lastWindowId) return // same window, just state noise
        hideHighlight()
        selectionIndex = -1
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        isRunning = false
        hideHighlight()
        collectJob?.cancel()
        scope?.cancel()
        scope = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        AppLogger.log("Gamepad", "Accessibility service destroyed")
        super.onDestroy()
    }
}
