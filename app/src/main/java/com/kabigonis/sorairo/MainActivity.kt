package com.kabigonis.sorairo

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.sin
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.StatFs
import android.os.SystemClock
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


import kotlin.math.abs
import kotlin.math.min

/**
 * Positions the existing workspace window views without recreating them.
 * Only explicit configuration changes animate; parent size changes snap.
 */
private class TileLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {
    private val animator = ValueAnimator()
    private var mode = "dwindle"
    private var ratio = 0.5f
    private var order = listOf("terminal", "pinned", "bottom")
    private var visible = setOf("terminal", "pinned", "bottom")
    private var lastWidth = 0
    private var lastHeight = 0

    init { clipChildren = false }

    var moveModeActive: () -> Boolean = { false }
    var onMoveTap: (String) -> Unit = {}
    private var moveTracking = false
    private var moveDownX = 0f
    private var moveDownY = 0f
    private val moveSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2

    private fun tileAt(x: Int, y: Int): String? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c.tag as? String
        }
        return null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = moveModeActive()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!moveModeActive() && !moveTracking) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveTracking = true; moveDownX = ev.x; moveDownY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tracked = moveTracking
                moveTracking = false
                if (tracked && abs(ev.x - moveDownX) <= moveSlop && abs(ev.y - moveDownY) <= moveSlop) {
                    tileAt(ev.x.toInt(), ev.y.toInt())?.let { onMoveTap(it) }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { moveTracking = false; return true }
        }
        return moveTracking
    }

    fun configure(newMode: String, newRatio: Float, newOrder: List<String>,
                  newVisible: Set<String>, animate: Boolean = true) {
        val nextMode = newMode.takeIf { it in setOf("dwindle", "master", "columns", "rows") } ?: "dwindle"
        val nextRatio = newRatio.coerceIn(0.3f, 0.7f)
        val nextOrder = (newOrder.filter { it in setOf("terminal", "pinned", "bottom") } +
            listOf("terminal", "pinned", "bottom")).distinct()
        val changed = mode != nextMode || ratio != nextRatio || order != nextOrder || visible != newVisible
        if (!changed) return
        val previouslyVisible = visible
        mode = nextMode
        ratio = nextRatio
        order = nextOrder
        visible = newVisible
        val targets = calculateRects(width, height).toMutableMap()
        val hiddenAfter = previouslyVisible - newVisible
        val children = childrenForIds()
        children.forEach { (id, child) ->
            child.visibility = if (id in previouslyVisible || id in newVisible) View.VISIBLE else View.GONE
        }
        if (animate && width > 0 && height > 0) {
            hiddenAfter.forEach { id ->
                val child = children[id]
                if (child != null) {
                    val cx = (child.left + child.right) / 2
                    val cy = (child.top + child.bottom) / 2
                    targets[id] = Rect(cx, cy, cx, cy)
                }
            }
            animateTo(targets, hiddenAfter)
        } else {
            animator.cancel()
            hiddenAfter.forEach { children[it]?.visibility = View.GONE }
            applyRects(targets)
        }
    }

    private fun childrenForIds(): Map<String, View> =
        (0 until childCount).mapNotNull { i ->
            val child = getChildAt(i)
            (child.tag as? String)?.let { it to child }
        }.toMap()

    private fun calculateRects(w: Int, h: Int): Map<String, Rect> {
        val ids = order.filter { it in visible && childrenForIds().containsKey(it) }
        if (ids.isEmpty()) return emptyMap()
        val l = paddingLeft
        val t = paddingTop
        val rw = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val rh = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val gap = gapPx.coerceAtMost(minOf(rw, rh).coerceAtLeast(0))
        val result = linkedMapOf<String, Rect>()
        fun put(id: String, x: Int, y: Int, width: Int, height: Int) {
            result[id] = Rect(x, y, x + width.coerceAtLeast(0), y + height.coerceAtLeast(0))
        }
        when {
            ids.size == 1 -> put(ids[0], l, t, rw, rh)
            mode == "rows" -> {
                val each = (rh - gap * (ids.size - 1)).coerceAtLeast(0) / ids.size
                ids.forEachIndexed { i, id ->
                    val top = t + i * (each + gap)
                    put(id, l, top, rw, if (i == ids.lastIndex) t + rh - top else each)
                }
            }
            mode == "columns" -> {
                val each = (rw - gap * (ids.size - 1)).coerceAtLeast(0) / ids.size
                ids.forEachIndexed { i, id ->
                    val left = l + i * (each + gap)
                    put(id, left, t, if (i == ids.lastIndex) l + rw - left else each, rh)
                }
            }
            mode == "master" -> {
                // Master keeps the first window prominent; the remaining windows
                // share a vertical stack on the right.
                val leftWidth = ((rw - gap) * ratio).toInt().coerceIn(0, (rw - gap).coerceAtLeast(0))
                put(ids[0], l, t, leftWidth, rh)
                val rightX = l + leftWidth + gap
                val rightW = (l + rw - rightX).coerceAtLeast(0)
                val rest = ids.drop(1)
                val each = (rh - gap * (rest.size - 1)).coerceAtLeast(0) / rest.size
                rest.forEachIndexed { i, id ->
                    val top = t + i * (each + gap)
                    put(id, rightX, top, rightW, if (i == rest.lastIndex) t + rh - top else each)
                }
            }
            else -> {
                // Dwindle uses an even primary split, with remaining windows
                // stacked in the secondary area.
                val leftWidth = ((rw - gap) * ratio).toInt().coerceIn(0, (rw - gap).coerceAtLeast(0))
                put(ids[0], l, t, leftWidth, rh)
                val rightX = l + leftWidth + gap
                val rightW = (l + rw - rightX).coerceAtLeast(0)
                val rest = ids.drop(1)
                val each = (rh - gap * (rest.size - 1)).coerceAtLeast(0) / rest.size
                rest.forEachIndexed { i, id ->
                    val top = t + i * (each + gap)
                    put(id, rightX, top, rightW, if (i == rest.lastIndex) t + rh - top else each)
                }
            }
        }
        return result
    }

    private fun applyRects(rects: Map<String, Rect>) {
        val children = childrenForIds()
        rects.forEach { (id, rect) ->
            children[id]?.let { child ->
                val childWidth = (rect.right - rect.left).coerceAtLeast(0)
                val childHeight = (rect.bottom - rect.top).coerceAtLeast(0)
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
                )
                child.layout(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        invalidate()
    }

    private fun animateTo(targets: Map<String, Rect>, hiddenAfter: Set<String>) {
        animator.cancel()
        val children = childrenForIds()
        val starts = targets.mapValues { (id, _) ->
            val child = children[id]
            if (child != null && child.width > 0 && child.height > 0) {
                Rect(child.left, child.top, child.right, child.bottom)
            } else {
                val target = targets.getValue(id)
                val cx = (target.left + target.right) / 2
                val cy = (target.top + target.bottom) / 2
                Rect(cx, cy, cx, cy)
            }
        }
        animator.removeAllUpdateListeners()
        animator.removeAllListeners()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                hiddenAfter.filter { it !in visible }.forEach { childrenForIds()[it]?.visibility = View.GONE }
            }
        })
        animator.setFloatValues(0f, 1f)
        animator.duration = 220
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { a ->
            val f = a.animatedValue as Float
            val current = targets.mapValues { (id, end) ->
                val start = starts[id] ?: end
                Rect(
                    (start.left + (end.left - start.left) * f).toInt(),
                    (start.top + (end.top - start.top) * f).toInt(),
                    (start.right + (end.right - start.right) * f).toInt(),
                    (start.bottom + (end.bottom - start.bottom) * f).toInt()
                )
            }
            applyRects(current)
        }
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        // Children are measured and laid out at their tile size in applyRects().
        // Measuring them full-size here used to clobber their real size whenever
        // a descendant called requestLayout(), leaving windows blank until toggled.
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w
            lastHeight = h
            animator.cancel()
            applyRects(calculateRects(w, h))
        } else if (!animator.isRunning) {
            // A descendant (pins list, player, monitor...) asked for layout. Always
            // re-place every tile, otherwise those windows stay unlaid-out/blank.
            applyRects(calculateRects(w, h))
        }
    }
}


class MainActivity : AppCompatActivity() {
    private companion object {
        const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"
        const val POWERAMP_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND"
        const val POWERAMP_API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
        const val POWERAMP_MODE_CHANGED = "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED"
        const val POWERAMP_EXTRA_SHUFFLE = "shuffle"
        const val POWERAMP_EXTRA_REPEAT = "repeat"
        const val NEW_APP_WINDOW_MS = 60L * 60L * 1000L
        const val NEW_APP_MARKER = " ◆"
    }

    data class App(val label: String, val pkg: String)

    class Pal(
        val name: String, bg: String, fg: String, dim: String,
        blue: String, green: String, accent: String, sel: String
    ) {
        val cBg = Color.parseColor(bg)
        val cFg = Color.parseColor(fg)
        val cDim = Color.parseColor(dim)
        val cBlue = Color.parseColor(blue)
        val cGreen = Color.parseColor(green)
        val cAccent = Color.parseColor(accent)
        val cSel = Color.parseColor(sel)
    }

    private val pals = listOf(
        Pal("Tokyo Night", "#1a1b26", "#c0caf5", "#565f89", "#7aa2f7", "#9ece6a", "#e0af68", "#33467c"),
        Pal("Catppuccin", "#1e1e2e", "#cdd6f4", "#6c7086", "#89b4fa", "#a6e3a1", "#f9e2af", "#45475a"),
        Pal("Gruvbox", "#282828", "#ebdbb2", "#928374", "#83a598", "#b8bb26", "#fabd2f", "#504945"),
        Pal("Nord", "#2e3440", "#d8dee9", "#4c566a", "#81a1c1", "#a3be8c", "#ebcb8b", "#434c5e"),
        Pal("Dracula", "#282a36", "#f8f8f2", "#6272a4", "#bd93f9", "#50fa7b", "#ff79c6", "#44475a")
    )
    private val fontNames = listOf(
        "monospace", "serif-monospace", "sans-serif", "sans-serif-condensed", "casual", "serif"
    )
    private val termColors = listOf(
        "theme" to 0,
        "green" to Color.parseColor("#9ece6a"),
        "blue" to Color.parseColor("#7aa2f7"),
        "amber" to Color.parseColor("#e0af68"),
        "pink" to Color.parseColor("#f7768e"),
        "white" to Color.parseColor("#ffffff")
    )
    private val opacities = listOf(40, 60, 85, 100)
    private val launchModes = listOf("both", "drawer", "terminal")
    private val logoNames = listOf("android", "arch", "omarchy", "image", "none")

    private val androidArt = listOf(
        "  ;,           ,;",
        "   ';,.-----.,;'",
        "  ,'           ',",
        " /    O     O    \\",
        "|                 |",
        "'-----------------'"
    ).joinToString("\n")
    private val archArt = listOf(
        "       /\\",
        "      /  \\",
        "     /    \\",
        "    /      \\",
        "   /   ,,   \\",
        "  /   |  |   \\",
        " /_-''    ''-_\\"
    ).joinToString("\n")
    private val omarchyArt = listOf(
        "████████████████",
        "██            ██",
        "██  ████████  ██",
        "██  ██    ██  ██",
        "██  ████████  ██",
        "██            ██",
        "████████████████"
    ).joinToString("\n")

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showWifiPanel() else
                Toast.makeText(this, "Location permission is needed to list nearby Wi-Fi networks", Toast.LENGTH_LONG).show()
        }


    private val pal: Pal get() = pals[prefs.getInt("theme", 0).coerceIn(0, pals.size - 1)]
    private val font: Typeface
        get() = Typeface.create(
            fontNames[prefs.getInt("font", 0).coerceIn(0, fontNames.size - 1)], Typeface.NORMAL
        )
    private val mode: String
        get() = launchModes[prefs.getInt("mode", 0).coerceIn(0, launchModes.size - 1)]

    private fun termFg(): Int {
        val t = termColors[prefs.getInt("tcolor", 0).coerceIn(0, termColors.size - 1)].second
        return if (t == 0) pal.cFg else t
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun panelColor(): Int {
        val a = opacities[prefs.getInt("opacity", 2).coerceIn(0, opacities.size - 1)] * 255 / 100
        return (pal.cBg and 0x00FFFFFF) or (a shl 24)
    }

    private fun solidColor(): Int = (pal.cBg and 0x00FFFFFF) or (0xF2 shl 24)

    private fun bottomWindowScopeAll(): Boolean =
        prefs.getBoolean("bottom_window_scope_all", false)

    private fun bottomWindowFor(ws: Int): String {
        val key = if (bottomWindowScopeAll()) "bottom_window_shared" else "bottom_window_$ws"
        return prefs.getString(key, "monitor")?.takeIf { it == "player" } ?: "monitor"
    }

    private fun setBottomWindowForCurrent(value: String) {
        val choice = if (value == "player") "player" else "monitor"
        val edit = prefs.edit()
        if (bottomWindowScopeAll()) {
            edit.putString("bottom_window_shared", choice)
        } else {
            edit.putString("bottom_window_$curWs", choice)
        }
        edit.apply()
    }

    private fun setBottomWindowForWorkspace(ws: Int, value: String) {
        val choice = if (value == "player") "player" else "monitor"
        val edit = prefs.edit()
        if (bottomWindowScopeAll()) {
            edit.putString("bottom_window_shared", choice)
            for (i in 0..4) edit.putString("bottom_window_$i", choice)
        } else {
            edit.putString("bottom_window_$ws", choice)
        }
        edit.apply()
    }


    private fun layoutScopeAll(): Boolean = prefs.getBoolean("layout_scope_all", false)

    // Visibility can be shared by every workspace or stored for each individual
    // window instance (terminal/pinned/bottom in a particular workspace).
    private fun windowVisibilityScopeAll(): Boolean =
        prefs.getBoolean("home_visibility_scope_all", true)

    private fun windowShown(ws: Int, id: String): Boolean {
        val legacy = prefs.getBoolean("home_show_$id", true)
        if (windowVisibilityScopeAll()) return legacy
        return prefs.getBoolean("home_show_${id}_$ws", legacy)
    }

    private fun setWindowShown(ws: Int, id: String, shown: Boolean) {
        val edit = prefs.edit()
        if (windowVisibilityScopeAll()) {
            edit.putBoolean("home_show_$id", shown)
        } else {
            edit.putBoolean("home_show_${id}_$ws", shown)
        }
        edit.apply()
    }

    private fun layoutModeFor(ws: Int): String {
        val key = if (layoutScopeAll()) "window_layout_mode_global" else "window_layout_mode_$ws"
        return prefs.getString(key, "dwindle")?.takeIf { it in setOf("dwindle", "master", "columns", "rows") } ?: "dwindle"
    }
    private fun layoutRatioFor(ws: Int): Float {
        val key = if (layoutScopeAll()) "window_layout_ratio_global" else "window_layout_ratio_$ws"
        return (prefs.getInt(key, 50).coerceIn(30, 70) / 100f)
    }
    private fun applyTileLayout(ws: Int, animate: Boolean = true) {
        val shown = buildSet {
            if (windowShown(ws, "terminal")) add("terminal")
            if (windowShown(ws, "pinned")) add("pinned")
            if (windowShown(ws, "bottom")) add("bottom")
        }
        tileLayouts[ws]?.configure(layoutModeFor(ws), layoutRatioFor(ws),
            tileOrder(ws), shown, animate)
    }
    private fun setLayoutMode(ws: Int, value: String) {
        val edit = prefs.edit()
        if (layoutScopeAll()) edit.putString("window_layout_mode_global", value)
        else edit.putString("window_layout_mode_$ws", value)
        edit.apply()
        if (layoutScopeAll()) for (i in 0..4) applyTileLayout(i) else applyTileLayout(ws)
    }
    private fun setLayoutRatio(ws: Int, value: Int) {
        val edit = prefs.edit()
        if (layoutScopeAll()) edit.putInt("window_layout_ratio_global", value)
        else edit.putInt("window_layout_ratio_$ws", value)
        edit.apply()
        if (layoutScopeAll()) for (i in 0..4) applyTileLayout(i) else applyTileLayout(ws)
    }

    // ---- state ----
    private var allApps: List<App> = emptyList()
    private var apps: List<App> = emptyList()
    private var curWs = 0
    private val focusedWindow = IntArray(5) { 0 } // 0 = terminal, 1 = pinned
    private val pinWindowForFocus = arrayOfNulls<View>(5)
    private val bottomWindowForFocus = arrayOfNulls<View>(5)
    private val terminalWindowForFocus = arrayOfNulls<View>(5)
    private val tileLayouts = arrayOfNulls<TileLayout>(5)
    private val moveSource = IntArray(5) { -1 }
    private lateinit var moveHint: TextView
    private val tileIds = listOf("terminal", "pinned", "bottom")
    private fun tileOrder(ws: Int): List<String> {
        val key = if (layoutScopeAll()) "tile_order_global" else "tile_order_$ws"
        val saved = prefs.getString(key, "").orEmpty().split(",").filter { it in tileIds }
        return (saved + tileIds).distinct()
    }
    private fun saveTileOrder(ws: Int, order: List<String>) {
        val key = if (layoutScopeAll()) "tile_order_global" else "tile_order_$ws"
        prefs.edit().putString(key, order.joinToString(",")).apply()
    }
    private fun tileView(ws: Int, id: String): View? = when (id) {
        "terminal" -> terminalWindowForFocus[ws]
        "pinned" -> pinWindowForFocus[ws]
        "bottom" -> bottomWindowForFocus[ws]
        else -> null
    }
    private fun endMove() {
        val index = moveSource[curWs]
        if (index < 0) return
        moveSource[curWs] = -1
        moveHint.visibility = View.GONE
        tileView(curWs, tileIds[index])?.let { v ->
            v.animate().cancel(); v.alpha = 1f
            (v.background as? GradientDrawable)?.setStroke(dp(2), pal.cDim)
        }
    }
    private fun startMove(ws: Int, id: String) {
        if (ws != curWs) return
        endMove(); moveSource[ws] = tileIds.indexOf(id)
        moveHint.visibility = View.VISIBLE
        tileView(ws, id)?.let { v ->
            (v.background as? GradientDrawable)?.setStroke(dp(3), pal.cAccent)
            v.animate().alpha(0.55f).setDuration(250).withEndAction {
                if (moveSource[ws] >= 0) v.animate().alpha(1f).setDuration(250).start()
            }.start()
        }
    }
    private fun focusWindow(ws: Int, id: String) {
        val terminal = terminalWindowForFocus[ws] ?: return
        val pinned = pinWindowForFocus[ws] ?: return
        setWindowFocus(ws, tileIds.indexOf(id).coerceAtLeast(0), terminal, pinned)
    }
    private fun moveTap(ws: Int, target: String): Boolean {
        val from = moveSource[ws]
        if (from < 0) return false
        val source = tileIds[from]
        if (source != target) {
            val order = tileOrder(ws).toMutableList()
            val a = order.indexOf(source); val b = order.indexOf(target)
            if (a >= 0 && b >= 0) {
                val tmp = order[a]; order[a] = order[b]; order[b] = tmp
                saveTileOrder(ws, order); applyTileLayout(ws, true); focusWindow(ws, source)
            }
        }
        endMove(); return true
    }
    private fun swapTileNames(ws: Int, first: String, second: String): Boolean {
        fun resolve(q: String) = tileIds.firstOrNull { it.startsWith(q, true) || q.startsWith(it, true) }
        val aName = resolve(first) ?: return false; val bName = resolve(second) ?: return false
        val order = tileOrder(ws).toMutableList(); val a = order.indexOf(aName); val b = order.indexOf(bName)
        if (a < 0 || b < 0) return false
        val tmp = order[a]; order[a] = order[b]; order[b] = tmp
        saveTileOrder(ws, order); applyTileLayout(ws, true); focusWindow(ws, aName); return true
    }
    private fun stepFocus(ws: Int, delta: Int) {
        val visible = tileOrder(ws).filter { windowShown(ws, it) }
        if (visible.isEmpty()) return
        val current = tileIds.getOrElse(focusedWindow[ws]) { visible[0] }
        val i = visible.indexOf(current).let { if (it < 0) 0 else it }
        focusWindow(ws, visible[(i + delta + visible.size) % visible.size])
    }
    private val focusBorderAnimators = java.util.WeakHashMap<View, ValueAnimator>()
    private val focusBorderColors = java.util.WeakHashMap<View, Int>()
    private lateinit var wall: ImageView
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var pager: ViewPager2
    private val wsLabels = ArrayList<TextView>()
    private lateinit var drawer: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var search: EditText
    private lateinit var settings: FrameLayout
    private lateinit var setBox: LinearLayout
    private lateinit var setScroll: ScrollView
    private val adapter = RowAdapter()
    private var selectedDrawerApp: App? = null
    private var activeDrawerActionHolder: RowAdapter.VH? = null

    private val history = Array(5) { SpannableStringBuilder() }
    private val outViews = arrayOfNulls<TextView>(5)
    private val outScrolls = arrayOfNulls<ScrollView>(5)
    private val pinBoxes = arrayOfNulls<LinearLayout>(5)
    private val bottomWindowViews = arrayOfNulls<LinearLayout>(5)
    private val bottomMonitorColumns = arrayOfNulls<LinearLayout>(5)
    private val bottomWindowContents = arrayOfNulls<ScrollView>(5)
    private val playerTitles = arrayOfNulls<TextView>(5)
    private val playerArtists = arrayOfNulls<TextView>(5)
    private val playerStatuses = arrayOfNulls<TextView>(5)
    private val playerHeaders = arrayOfNulls<TextView>(5)
    private val playerTimes = arrayOfNulls<TextView>(5)
    private val playerQueueViews = arrayOfNulls<LinearLayout>(5)
    private val playerQueueScrolls = arrayOfNulls<ScrollView>(5)
    private val playerQueueSignatures = arrayOfNulls<String>(5)
    private val playerQueueRows = Array(5) { mutableListOf<TextView>() }
    private val terminalPrompts = arrayOfNulls<EditText>(5)
    private val playerQueueEmptyLabels = arrayOfNulls<TextView>(5)
    private val playerPlayButtons = arrayOfNulls<TextView>(5)
    private val powerampShuffleButtons = arrayOfNulls<TextView>(5)
    private val powerampRepeatButtons = arrayOfNulls<TextView>(5)
    private var powerampShuffleMode = 0
    private var powerampRepeatMode = 0
    private var powerampModeReceiver: android.content.BroadcastReceiver? = null
    private val playerProgressBars = arrayOfNulls<android.widget.ProgressBar>(5)
    private val monitorSummaries = arrayOfNulls<TextView>(5)
    private val monitorGraphs = arrayOfNulls<NetGraphView>(5)
    private val sharedNetHistory = NetGraphHistory()
    private val bottomWindowTitles = arrayOfNulls<TextView>(5)
    private val defaultLauncherPrompts = arrayOfNulls<TextView>(5)
    private val fetchInfos = arrayOfNulls<TextView>(5)
    private lateinit var vis: VisualizerView
    private lateinit var song: TextView
    private lateinit var battText: TextView
    private lateinit var qs: FrameLayout
    private lateinit var qsBox: LinearLayout
    private var ctrl: MediaController? = null
    private var listeningController: MediaController? = null
    private var playerCallback: MediaController.Callback? = null

    private val handler = Handler(Looper.getMainLooper())

    private var lastSongDetails = "No active media"
    private var missingSessionReads = 0

    private fun activeController(): MediaController? {
        if (!mediaAccess()) return null
        return try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(
                ComponentName(this, NowPlayingService::class.java)
            )
            sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private val playerRefresh = object : Runnable {
        override fun run() {
            if (!activityResumed || bottomWindowFor(curWs) != "player") return
            startPlayerCallbacks()
        }
    }


    private fun stopPlayerCallbacks() {
        unregisterPowerampModeReceiver()
        val controller = listeningController
        val callback = playerCallback
        if (controller != null && callback != null) {
            try {
                controller.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        listeningController = null
        playerCallback = null
        handler.removeCallbacks(playerRefresh)
    }

    private fun unregisterPowerampModeReceiver() {
        val receiver = powerampModeReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        } finally {
            powerampModeReceiver = null
        }
    }

    private fun registerPowerampModeReceiverIfNeeded(controller: MediaController?) {
        if (!activityResumed || bottomWindowFor(curWs) != "player" ||
            controller?.packageName != POWERAMP_PACKAGE
        ) {
            unregisterPowerampModeReceiver()
            return
        }
        if (powerampModeReceiver != null) return
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        if (intent?.action != POWERAMP_MODE_CHANGED) return
                        if (intent.hasExtra(POWERAMP_EXTRA_SHUFFLE)) {
                            val mode = intent.getIntExtra(POWERAMP_EXTRA_SHUFFLE, powerampShuffleMode)
                            if (mode in 0..4) powerampShuffleMode = mode
                        }
                        if (intent.hasExtra(POWERAMP_EXTRA_REPEAT)) {
                            powerampRepeatMode = intent.getIntExtra(POWERAMP_EXTRA_REPEAT, powerampRepeatMode)
                                .coerceIn(0, 5)
                        }
                        updatePowerampButtons(curWs)
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Poweramp state unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val sticky = ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(POWERAMP_MODE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            powerampModeReceiver = receiver
            if (sticky != null) {
                if (sticky.hasExtra(POWERAMP_EXTRA_SHUFFLE)) {
                    val mode = sticky.getIntExtra(POWERAMP_EXTRA_SHUFFLE, powerampShuffleMode)
                    if (mode in 0..4) powerampShuffleMode = mode
                }
                if (sticky.hasExtra(POWERAMP_EXTRA_REPEAT)) {
                    powerampRepeatMode = sticky.getIntExtra(POWERAMP_EXTRA_REPEAT, powerampRepeatMode).coerceIn(0, 5)
                }
            }
            updatePowerampButtons(curWs)
        } catch (_: Exception) {
            powerampModeReceiver = null
            Toast.makeText(this, "Poweramp state unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun powerampShuffleLabel(mode: Int): String = when (mode) {
        0 -> "none"
        1 -> "all"
        2 -> "songs"
        3 -> "cats"
        4 -> "songs+cats"
        else -> "none"
    }

    private fun powerampRepeatLabel(mode: Int): String = when (mode) {
        0 -> "none"
        1 -> "on"
        2 -> "advance"
        3 -> "song"
        4 -> "single"
        5 -> "advance+stop"
        else -> "none"
    }

    private fun updatePowerampButtons(ws: Int) {
        val visible = ctrl?.packageName == POWERAMP_PACKAGE &&
            activityResumed && bottomWindowFor(curWs) == "player"
        powerampShuffleButtons[ws]?.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            text = "shuffle: ${powerampShuffleLabel(powerampShuffleMode)}"
            setTextColor(if (powerampShuffleMode != 0) pal.cAccent else pal.cDim)
        }
        powerampRepeatButtons[ws]?.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            text = "repeat: ${powerampRepeatLabel(powerampRepeatMode)}"
            setTextColor(if (powerampRepeatMode != 0) pal.cAccent else pal.cDim)
        }
    }

    private fun sendPowerampModeCommand(command: Int, extra: String, mode: Int, label: String) {
        try {
            val intent = Intent(POWERAMP_API_COMMAND).setComponent(
                ComponentName(POWERAMP_PACKAGE, POWERAMP_API_RECEIVER)
            ).putExtra("cmd", command).putExtra(extra, mode)
            sendBroadcast(intent)
            if (extra == POWERAMP_EXTRA_SHUFFLE) powerampShuffleMode = mode
            else powerampRepeatMode = mode
            updatePowerampButtons(curWs)
        } catch (_: Exception) {
            Toast.makeText(this, "Poweramp $label failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePlayerWindow(ws: Int) {
        val controller = ctrl
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val state = controller?.playbackState?.state
        val playback = when (state) {
            PlaybackState.STATE_PLAYING -> "Playing"
            PlaybackState.STATE_PAUSED -> "Paused"
            PlaybackState.STATE_BUFFERING -> "Buffering"
            else -> "Not playing"
        }

        if (!title.isNullOrBlank()) {
            lastSongDetails = if (artist.isNullOrBlank()) title else "$title\n$artist"
            missingSessionReads = 0
        } else if (controller == null) {
            missingSessionReads++
            if (missingSessionReads >= 2) lastSongDetails = "No active media"
        }

        val details = lastSongDetails.split("\n", limit = 2)
        playerStatuses[ws]?.text = playback
        updatePowerampButtons(ws)
        playerPlayButtons[ws]?.text =
            if (state == PlaybackState.STATE_PLAYING) "pause" else "play"

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playbackState = controller?.playbackState
        val position = playbackState?.position ?: -1L
        fun timeLabel(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
            return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }
        val elapsed = if (position >= 0L) timeLabel(position) else "00:00"
        val total = if (duration > 0L) timeLabel(duration) else "00:00"
        val trackLabel = details.filter { it.isNotBlank() }.joinToString(" - ")
        val headerTrack = if (trackLabel.isBlank()) "" else " — $trackLabel"
        playerHeaders[ws]?.text = "$playback$headerTrack"
        playerTimes[ws]?.text = "$elapsed / $total"
        val queueView = playerQueueViews[ws]
        if (queueView != null) {
            val queue = controller?.queue.orEmpty().take(100)
            val activeQueueId = playbackState?.activeQueueItemId
            val signature = queue.joinToString("|") {
                "${it.queueId}:${it.description.title}:${it.description.subtitle}"
            }
            val rows = playerQueueRows[ws]

            if (queue.isEmpty()) {
                if (playerQueueSignatures[ws] != "empty") {
                    queueView.removeAllViews()
                    rows.clear()
                    val empty = txt(10f, pal.cDim, "this app doesn't share its queue")
                    queueView.addView(empty, LinearLayout.LayoutParams(-1, -2))
                    playerQueueEmptyLabels[ws] = empty
                    playerQueueSignatures[ws] = "empty"
                }
            } else {
                if (playerQueueSignatures[ws] != signature) {
                    queueView.removeAllViews()
                    rows.clear()
                    playerQueueEmptyLabels[ws] = null

                    queue.forEachIndexed { index, item ->
                        val itemTitle = item.description.title?.toString()
                            ?.takeIf { it.isNotBlank() } ?: "Unknown track"
                        val itemArtist = item.description.subtitle?.toString().orEmpty()
                        val label = if (itemArtist.isBlank()) itemTitle else "$itemTitle - $itemArtist"
                        val row = txt(10.5f, pal.cFg, "${index + 1}. $label").apply {
                            typeface = Typeface.MONOSPACE
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(6), dp(5), dp(6), dp(5))
                            setOnLongClickListener { showSettings(); true }
                            setOnClickListener {
                                val c = ctrl ?: return@setOnClickListener
                                val stateNow = c.playbackState ?: return@setOnClickListener
                                if ((stateNow.actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L) {
                                    c.transportControls.skipToQueueItem(item.queueId)
                                }
                            }
                        }
                        rows.add(row)
                        queueView.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                    playerQueueSignatures[ws] = signature
                }

                queue.forEachIndexed { index, item ->
                    rows.getOrNull(index)?.setTextColor(
                        if (activeQueueId != null && item.queueId == activeQueueId) {
                            pal.cAccent
                        } else {
                            pal.cFg
                        }
                    )
                }
            }
        }

        val progressBar = playerProgressBars[ws]
        if (progressBar != null) {
            if (duration > 0L && position >= 0L && position <= duration) {
                progressBar.max = 1000
                progressBar.progress =
                    ((position.toDouble() / duration.toDouble()) * 1000.0).toInt()
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.progress = 0
                progressBar.visibility = View.GONE
            }
        }

        playerTitles[ws]?.text = details.firstOrNull().orEmpty()
        playerArtists[ws]?.apply {
            text = details.getOrNull(1).orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun startPlayerCallbacks() {
        if (!activityResumed || bottomWindowFor(curWs) != "player") return

        val controller = activeController()
        if (controller == null) {
            stopPlayerCallbacks()
            ctrl = null
            updatePlayerWindow(curWs)
            handler.removeCallbacks(playerRefresh)
            handler.postDelayed(playerRefresh, 1000)
            return
        }

        if (listeningController !== controller) {
            stopPlayerCallbacks()
            val callback = object : MediaController.Callback() {
                override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
                    updatePlayerWindow(curWs)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    updateNowPlaying()
                    updatePlayerWindow(curWs)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    updateNowPlaying()
                    updatePlayerWindow(curWs)
                }

                override fun onSessionDestroyed() {
                    stopPlayerCallbacks()
                    ctrl = null
                    updateNowPlaying()
                    updatePlayerWindow(curWs)
                    if (activityResumed && bottomWindowFor(curWs) == "player") {
                        handler.postDelayed(playerRefresh, 1000)
                    }
                }
            }
            listeningController = controller
            playerCallback = callback
            try {
                controller.registerCallback(callback)
            } catch (_: Exception) {
                listeningController = null
                playerCallback = null
            }
        }

        ctrl = controller
        registerPowerampModeReceiverIfNeeded(controller)
        updatePlayerWindow(curWs)
        handler.removeCallbacks(playerRefresh)
        handler.postDelayed(playerRefresh, 1000)
    }

    private var askedWrite = false

    private var autoLaunchedQuery: String? = null

    // network speed state
    private var lastT = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var downBps = 0L
    private var upBps = 0L
    private var monitorLastT = 0L
    private var monitorLastRx = 0L
    private var monitorLastTx = 0L
    private var monitorLastSummaryT = 0L

    private val exportHiddenFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val list = hiddenSet().sorted()
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(list.joinToString("\n").toByteArray())
                }
                Toast.makeText(this, "saved ${list.size} hidden apps", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "couldn't save the file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importHiddenFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val imported = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
                    .orEmpty()
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotEmpty() && line.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' }
                    }
                    .toSet()
                if (imported.isEmpty()) {
                    Toast.makeText(this, "no apps found in that file", Toast.LENGTH_SHORT).show()
                } else {
                    val merged = hiddenSet() + imported
                    prefs.edit().putString("hidden", merged.joinToString(",")).apply()
                    apps = visibleApps()
                    adapter.items = apps
                    adapter.notifyDataSetChanged()
                    for (i in 0..4) {
                        fillPins(i)
                        refreshFetch(i)
                    }
                    if (settings.visibility == View.VISIBLE) fillHidden()
                    Toast.makeText(this, "restored ${imported.size} hidden apps", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "couldn't read the file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickWall = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            prefs.edit().putString("wall", uri.toString()).apply()
            loadWallpaper()
        }
    }

    private var returnToStyleAfterLogoPick = false

    private val pickLogo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Persist the navigation target because Android may recreate this
        // Activity while the system document picker is open.
        val returnToStyle = returnToStyleAfterLogoPick ||
            prefs.getBoolean("pending_logo_return_style", false)
        returnToStyleAfterLogoPick = false
        prefs.edit().remove("pending_logo_return_style").apply()

        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            prefs.edit().putString("logo_uri", uri.toString()).putInt("logo", 3).apply()
            // Rebuild the home UI without reopening the root config page. The
            // style submenu is restored below after the new UI is in place.
            rebuild(!returnToStyle)
        }

        if (returnToStyle) {
            fillStyleSettings()
            setScroll.scrollTo(0, 0)
            showOverlay()
        }
    }

    private fun pickLogoFromStyle() {
        returnToStyleAfterLogoPick = true
        prefs.edit().putBoolean("pending_logo_return_style", true).apply()
        // Do not leave a fade-out callback pending: it can hide the newly
        // reopened Style overlay after the document picker returns.
        settings.animate().cancel()
        settings.alpha = 1f
        settings.visibility = View.GONE
        pickLogo.launch(arrayOf("image/*"))
    }

    private var touchDownScreenX = 0f
    private var touchDownScreenY = 0f

    private fun touchDownStartedInCurrentQueue(): Boolean {
        if (bottomWindowFor(curWs) != "player") return false
        val queueScroll = playerQueueScrolls.getOrNull(curWs) ?: return false
        if (!queueScroll.isShown || queueScroll.width <= 0 || queueScroll.height <= 0) return false
        return try {
            val location = IntArray(2)
            queueScroll.getLocationOnScreen(location)
            touchDownScreenX >= location[0] && touchDownScreenX < location[0] + queueScroll.width &&
                touchDownScreenY >= location[1] && touchDownScreenY < location[1] + queueScroll.height
        } catch (_: Exception) {
            false
        }
    }

    private val detector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (moveSource[curWs] >= 0) return false
                if (mode != "terminal" && e1 != null &&
                    e1.y - e2.y > dp(80) && vy < -800 && abs(vx) < abs(vy)
                ) {
                    if (touchDownStartedInCurrentQueue()) return false
                    openDrawer()
                    return true
                }
                return false
            }
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            if (!activityResumed) return

            val now = SystemClock.elapsedRealtime()
            if (bottomWindowFor(curWs) == "monitor" &&
                (monitorLastSummaryT == 0L || now - monitorLastSummaryT >= 1000L)
            ) {
                updateMonitorSummary(curWs)
                monitorLastSummaryT = now
            }

            sampleMonitorNetwork()?.let { (down, up) ->
                sharedNetHistory.push(down, up, now)
            }
            monitorGraphs[curWs]?.invalidate()
            handler.postDelayed(this, 1000)
        }
    }

    private var activityResumed = false

    // True while the app drawer (not the terminal prompt) owns the soft keyboard.
    // Keeps the workspace windows from moving when the drawer's keyboard opens/closes.
    private var drawerOwnsIme = false

    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private fun registerActiveSessionsListener() {
        if (!mediaAccess() || activeSessionsListener != null) return
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
                val active = sessions?.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                } ?: sessions?.firstOrNull()
                ctrl = active
                updateNowPlaying()
                updatePlayerWindow(curWs)
                if (activityResumed && bottomWindowFor(curWs) == "player") {
                    if (active != null) startPlayerCallbacks()
                    else handler.postDelayed(playerRefresh, 700L)
                }
            }
            manager.addOnActiveSessionsChangedListener(
                listener, ComponentName(this, NowPlayingService::class.java), handler
            )
            activeSessionsListener = listener
        } catch (_: Exception) {
            activeSessionsListener = null
        }
    }

    private fun unregisterActiveSessionsListener() {
        val listener = activeSessionsListener ?: return
        try {
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .removeOnActiveSessionsChangedListener(listener)
        } catch (_: Exception) {
        }
        activeSessionsListener = null
    }

    private fun updateBottomWindowRuntime() {
        handler.removeCallbacks(monitorTick)
        if (!activityResumed) {
            stopPlayerCallbacks()
            return
        }
        if (bottomWindowFor(curWs) == "monitor") {
            stopPlayerCallbacks()
            updateMonitorSummary(curWs)
            monitorLastSummaryT = SystemClock.elapsedRealtime()
            monitorGraphs.forEach { it?.setColors(pal.cDim, pal.cBlue, pal.cGreen) }
        } else {
            updatePlayerWindow(curWs)
            startPlayerCallbacks()
        }
        // Keep collecting network history while the player panel is visible too.
        handler.post(monitorTick)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    private fun txt(size: Float, color: Int, s: CharSequence = ""): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = font
    }

    private fun SpannableStringBuilder.add(t: String, c: Int): SpannableStringBuilder {
        val s = length
        append(t)
        setSpan(ForegroundColorSpan(c), s, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    private inner class HomeLayer(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                touchDownScreenX = ev.rawX
                touchDownScreenY = ev.rawY
            }
            detector.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    // ================= lifecycle =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideStatusBar()

        allApps = loadApps()
        apps = visibleApps()
        initHistory()
        buildUi()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        refreshInstalledApps()
        handler.removeCallbacks(tick)
        tick.run()
        updateNowPlaying()
        pager.post {
            updateBottomWindowDisplay(curWs, false)
            registerActiveSessionsListener()
            updateBottomWindowRuntime()
            updateMonitorSummary(curWs)
            updatePlayerWindow(curWs)
            monitorGraphs[curWs]?.invalidate()

            // Media sessions can become available a little after the launcher
            // resumes (especially after a cold start). Retry discovery instead
            // of requiring the user to switch the monitor/player tab.
            fun retryMediaDiscovery(attempt: Int) {
                if (!activityResumed || attempt >= 8) return
                updateNowPlaying()
                if (bottomWindowFor(curWs) == "player") {
                    val controller = activeController()
                    if (controller != null) {
                        ctrl = controller
                        startPlayerCallbacks()
                        updatePlayerWindow(curWs)
                    } else {
                        handler.postDelayed({ retryMediaDiscovery(attempt + 1) }, 900L)
                    }
                } else if (activeController() == null) {
                    handler.postDelayed({ retryMediaDiscovery(attempt + 1) }, 900L)
                }
            }
            handler.postDelayed({ retryMediaDiscovery(0) }, 400L)
        }
        refreshDefaultLauncherPrompts()
        if (permissionConfigOpen && settings.visibility == View.VISIBLE) {
            fillPermissionSettings()
        }
    }

    private fun refreshDefaultLauncherPrompts() {
        val showPrompt = !isDefaultLauncher()
        defaultLauncherPrompts.forEach { prompt ->
            prompt?.visibility = if (showPrompt) View.VISIBLE else View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        activityResumed = false
        unregisterActiveSessionsListener()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(monitorTick)
        stopPlayerCallbacks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
        closeSettings()
        closeQs()
    }

    @Deprecated("Launcher stays put on back")
    override fun onBackPressed() {
        if (moveSource[curWs] >= 0) endMove()
        else if (qs.visibility == View.VISIBLE) closeQs()
        else if (settings.visibility == View.VISIBLE) closeSettings() else closeDrawer()
    }

    // ================= UI =================
    private fun buildUi() {
        val p = pal
        val dm = resources.displayMetrics

        val root = FrameLayout(this).apply { setBackgroundColor(p.cBg) }
        wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wall, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            View(this).apply { setBackgroundColor(Color.parseColor("#55000000")) },
            FrameLayout.LayoutParams(-1, -1)
        )
        loadWallpaper()

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- top bar ----
        val bar = FrameLayout(this)
        moveHint = txt(11f, p.cAccent, "move: tap another window").apply { visibility = View.GONE; gravity = Gravity.CENTER; setOnClickListener { endMove() } }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wsLabels.clear()
        for (i in 0..4) {
            val t = txt(13f, p.cDim, "${i + 1}").apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { pager.setCurrentItem(i, true) }
            }
            wsLabels.add(t)
            left.addView(t)
        }
        vis = VisualizerView(this).apply {
            color = p.cGreen
            visibility = View.GONE
        }
        left.addView(vis, LinearLayout.LayoutParams(dp(16), dp(14)).apply {
            marginStart = dp(14); marginEnd = dp(8)
        })
        song = txt(13f, p.cFg).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = maxOf(dp(100), dm.widthPixels / 2 - dp(230))
            visibility = View.GONE
            setOnClickListener { togglePlay() }
        }
        left.addView(song)
        clock = txt(13f, p.cFg)
        status = txt(13f, p.cFg)
        battText = txt(13f, p.cFg).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnLongClickListener { openQs(); true }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        right.addView(status)
        right.addView(battText)
        bar.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        content.addView(bar, LinearLayout.LayoutParams(-1, dp(34)))
        content.addView(moveHint, LinearLayout.LayoutParams(-1, dp(22)))

        // ---- workspaces ----
        pager = ViewPager2(this).apply {
            adapter = PageAdapter()
            offscreenPageLimit = 1
            setCurrentItem(curWs, false)
            // Trial: subtle wallpaper parallax, inspired by Omalauncher's workspace background.
            wall.scaleX = 1.08f
            updateWallpaperParallax(curWs.toFloat())
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    updateWallpaperParallax(position + positionOffset)
                }

                override fun onPageSelected(position: Int) {
                    curWs = position
                    applyTileLayout(position, false)
                    updateWsBar()
                    updateBottomWindowRuntime()
                }
            })
        }
        content.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        val home = HomeLayer(this)
        home.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // ---- drawer ----
        drawer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { closeDrawer() }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(solidColor(), p.cAccent)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
        }
        search = EditText(this).apply {
            hint = "Search..."
            setHintTextColor(p.cDim)
            setTextColor(p.cFg)
            typeface = font
            textSize = 18f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnEditorActionListener { _, _, _ ->
                if (adapter.items.isEmpty()) {
                    val query = text.toString().trim()
                    if (query.isNotEmpty()) {
                        try {
                            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query))
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "No app found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    launchApp(adapter.items.first())
                }
                true
            }
            doAfterTextChanged { editable ->
                val query = editable?.toString().orEmpty()
                adapter.items = matches(query)
                adapter.notifyDataSetChanged()
                autoLaunchSearchResult(query)
            }
        }
        adapter.items = apps
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@MainActivity.adapter
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, -2))
        panel.addView(View(this).apply { setBackgroundColor(p.cDim) }, LinearLayout.LayoutParams(-1, dp(1)))
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        val panelTop = (dm.heightPixels * 0.08f).toInt()
        drawer.addView(
            panel,
            FrameLayout.LayoutParams(
                min(dp(640), dm.widthPixels - dp(32)),
                (dm.heightPixels * 0.6f).toInt(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = panelTop }
        )
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        // ---- quick settings overlay ----
        qs = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#55000000"))
            setOnClickListener { closeQs() }
        }
        qsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(solidColor(), p.cAccent)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            isClickable = true
        }
        qs.addView(
            qsBox,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(44); marginEnd = dp(12)
            }
        )
        root.addView(qs, FrameLayout.LayoutParams(-1, -1))

        // ---- settings overlay ----
        settings = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { closeSettings() }
        }
        setBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(solidColor(), p.cAccent)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
        }
        setScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(setBox, FrameLayout.LayoutParams(-1, -2))
        }
        settings.addView(
            setScroll,
            FrameLayout.LayoutParams(min(dp(520), dm.widthPixels - dp(32)), -2, Gravity.CENTER).apply {
                topMargin = dp(24); bottomMargin = dp(24)
            }
        )
        root.addView(settings, FrameLayout.LayoutParams(-1, -1))

        // Keyboard handling: the workspace only follows the keyboard when the
        // terminal prompt owns it (not the app drawer / settings / quick panel).
        fun terminalOwnsIme(): Boolean =
            terminalPrompts.getOrNull(curWs)?.hasFocus() == true &&
                !drawerOwnsIme &&
                drawer.visibility != View.VISIBLE &&
                settings.visibility != View.VISIBLE &&
                qs.visibility != View.VISIBLE

        fun applyContentPadding(insets: WindowInsetsCompat) {
            val stable = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(
                dp(12), dp(6), dp(12),
                maxOf(stable.bottom, if (terminalOwnsIme()) ime.bottom else 0) + dp(12)
            )
        }

        var imeAnimating = false
        var lastInsets: WindowInsetsCompat? = null

        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    // Runs before the final insets are dispatched. Hold the layout
                    // still so the windows glide with the keyboard instead of jumping.
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyContentPadding(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimating = false
                        lastInsets?.let { applyContentPadding(it) }
                        // Keyboard fully gone: the drawer no longer owns it.
                        if (lastInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0) {
                            drawerOwnsIme = false
                        }
                    }
                }
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            lastInsets = insets
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (ime.bottom == 0 && !imeAnimating) drawerOwnsIme = false
            // Workspace padding is applied here only; overlay keyboards never resize the workspace.
            // While the keyboard is animating, onProgress drives the padding.
            if (!imeAnimating) applyContentPadding(insets)
            val avail = (dm.heightPixels - ime.bottom - panelTop - dp(16)).coerceAtLeast(dp(200))
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                height = min((dm.heightPixels * 0.6f).toInt(), avail)
            }
            insets
        }

        setContentView(root)
        updateWsBar()
        updateStatus()
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val box: FrameLayout) : RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) })

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.box.removeAllViews()
            holder.box.addView(buildPage(position), FrameLayout.LayoutParams(-1, -1))

        }

        override fun getItemCount() = 5
    }

    private fun setWindowFocus(
        ws: Int,
        focused: Int,
        terminal: View,
        pinned: View
    ) {
        focusedWindow[ws] = focused

        fun animateBorder(view: View, targetColor: Int) {
            val drawable = view.background as? GradientDrawable ?: return
            focusBorderAnimators.remove(view)?.cancel()

            val currentColor = focusBorderColors[view]
                ?: if (view === terminalWindowForFocus[ws]) pal.cAccent else pal.cDim
            val animator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                currentColor,
                targetColor
            ).apply {
                duration = 180L
                addUpdateListener { animation ->
                    val color = animation.animatedValue as Int
                    drawable.setStroke(dp(2), color)
                    focusBorderColors[view] = color
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (focusBorderAnimators[view] === this@apply) {
                            focusBorderAnimators.remove(view)
                        }
                    }
                })
            }
            focusBorderAnimators[view] = animator
            animator.start()
        }

        val p = pal
        animateBorder(terminal, if (focused == 0) p.cAccent else p.cDim)
        animateBorder(pinned, if (focused == 1) p.cAccent else p.cDim)
        bottomWindowForFocus[ws]?.let { bottom ->
            animateBorder(bottom, if (focused == 2) p.cAccent else p.cDim)
        }
    }

    private fun openSettingsOnLongPress(v: View) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showSettings()
            }
        })
        v.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            false
        }
    }

    private fun buildBottomWindow(ws: Int): LinearLayout {
        val p = pal
        val win = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = box(panelColor(), p.cDim)
            setOnLongClickListener { showSettings(); true }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = txt(12.5f, p.cBlue, "~/monitor")
        title.setOnLongClickListener { if (moveSource[ws] >= 0) moveTap(ws, "bottom") else startMove(ws, "bottom"); true }
        bottomWindowTitles[ws] = title
        titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        fun addChoice(label: String, choice: String) {
            val tab = txt(11f, if (bottomWindowFor(ws) == choice) p.cAccent else p.cDim, label).apply {
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setOnClickListener {
                    val terminal = terminalWindowForFocus[ws]
                    val pinned = pinWindowForFocus[ws]
                    if (terminal != null && pinned != null) {
                        setWindowFocus(ws, 2, terminal, pinned)
                    }
                    if (bottomWindowFor(ws) != choice) {
                        setBottomWindowForWorkspace(ws, choice)
                        updateBottomWindowDisplay(ws, true)
                        if (ws == curWs) updateBottomWindowRuntime()
                    }
                }
            }
            titleRow.addView(tab, LinearLayout.LayoutParams(-2, -2))
        }
        addChoice("monitor", "monitor")
        addChoice("player", "player")
        win.addView(titleRow, LinearLayout.LayoutParams(-1, -2))

        val playerColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, 0)
        }
        val playerHeader = txt(10.5f, p.cBlue).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            text = "Not playing"
        }
        playerHeaders[ws] = playerHeader
        playerColumn.addView(playerHeader, LinearLayout.LayoutParams(-1, -2))
        val playerTime = txt(10.5f, p.cDim).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            text = "00:00 / 00:00"
        }
        playerTimes[ws] = playerTime
        playerColumn.addView(playerTime, LinearLayout.LayoutParams(-1, -2))

        val playerTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val queueTab = txt(10.5f, p.cDim, "[Queue]").apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        queueTab.setTextColor(p.cAccent)
        playerTabs.addView(queueTab, LinearLayout.LayoutParams(-2, -2))


        val playerTitle = txt(12f, p.cAccent).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "No active media"
        }
        val playerArtist = txt(10.5f, p.cDim).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val playerStatus = txt(10.5f, p.cBlue).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        playerTitles[ws] = playerTitle
        playerArtists[ws] = playerArtist
        playerStatuses[ws] = playerStatus
        // Track details are shown in the two-line header above.
        playerTitle.visibility = View.GONE
        playerArtist.visibility = View.GONE
        playerStatus.visibility = View.GONE

        val playerProgress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
        }
        playerProgressBars[ws] = playerProgress
        playerColumn.addView(
            playerProgress,
            LinearLayout.LayoutParams(-1, dp(3)).apply {
                topMargin = dp(4)
                bottomMargin = dp(2)
            }
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun controlButton(label: String, action: () -> Unit): TextView =
            txt(11f, p.cFg, label).apply {
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener { action() }
            }

        controls.addView(
            controlButton("<<") {
                val c = ctrl ?: return@controlButton
                val actions = c.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                    c.transportControls.skipToPrevious()
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val playButton = controlButton("play") {
            val c = ctrl
            val actions = c?.playbackState?.actions ?: 0L
            val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
            val supported = if (playing) {
                actions and PlaybackState.ACTION_PAUSE != 0L
            } else {
                actions and PlaybackState.ACTION_PLAY != 0L
            }
            if (supported) togglePlay()
        }
        playerPlayButtons[ws] = playButton
        controls.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        controls.addView(
            controlButton(">>") {
                val c = ctrl ?: return@controlButton
                val actions = c.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                    c.transportControls.skipToNext()
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val shuffleButton = txt(9.5f, p.cDim, "shuffle: none").apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            maxLines = 2
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener {
                try {
                    if (ctrl?.packageName != POWERAMP_PACKAGE) return@setOnClickListener
                    val next = if (powerampShuffleMode == 0) 2 else 0
                    sendPowerampModeCommand(9, POWERAMP_EXTRA_SHUFFLE, next, "shuffle")
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Poweramp shuffle failed", Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                try {
                    if (ctrl?.packageName != POWERAMP_PACKAGE) return@setOnLongClickListener true
                    val next = (powerampShuffleMode + 1) % 5
                    sendPowerampModeCommand(9, POWERAMP_EXTRA_SHUFFLE, next, "shuffle")
                    Toast.makeText(this@MainActivity, "Shuffle: ${powerampShuffleLabel(next)}", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Poweramp shuffle failed", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        powerampShuffleButtons[ws] = shuffleButton
        controls.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        val repeatButton = txt(9.5f, p.cDim, "repeat: none").apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            maxLines = 2
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener {
                try {
                    if (ctrl?.packageName != POWERAMP_PACKAGE) return@setOnClickListener
                    val next = when (powerampRepeatMode) {
                        0 -> 1
                        1 -> 3
                        3 -> 0
                        else -> 0
                    }
                    sendPowerampModeCommand(8, POWERAMP_EXTRA_REPEAT, next, "repeat")
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Poweramp repeat failed", Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                try {
                    if (ctrl?.packageName != POWERAMP_PACKAGE) return@setOnLongClickListener true
                    val next = (powerampRepeatMode + 1) % 6
                    sendPowerampModeCommand(8, POWERAMP_EXTRA_REPEAT, next, "repeat")
                    Toast.makeText(this@MainActivity, "Repeat: ${powerampRepeatLabel(next)}", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Poweramp repeat failed", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        powerampRepeatButtons[ws] = repeatButton
        controls.addView(repeatButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        playerColumn.addView(
            controls,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2)
            }
        )

        val queueLabel = txt(10.5f, p.cAccent, "── QUEUE ──").apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }
        playerColumn.addView(queueLabel, LinearLayout.LayoutParams(-1, -2))

        val queueScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val queueList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        queueScroll.addView(queueList)
        playerQueueScrolls[ws] = queueScroll
        playerQueueViews[ws] = queueList
        openSettingsOnLongPress(queueScroll)
        playerColumn.addView(
            queueScroll,
            LinearLayout.LayoutParams(-1, dp(112)).apply {
                topMargin = dp(2)
            }
        )
        val playerScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(playerColumn, ViewGroup.LayoutParams(-1, -2))
        }
        openSettingsOnLongPress(playerScroll)
        bottomWindowContents[ws] = playerScroll

        val monitorColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val summary = txt(10.5f, p.cFg).apply {
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
        }
        monitorSummaries[ws] = summary
        monitorColumn.addView(summary, LinearLayout.LayoutParams(-1, -2))
        bottomMonitorColumns[ws] = monitorColumn

        val graph = NetGraphView(this, sharedNetHistory).also {
            it.setColors(p.cDim, p.cBlue, p.cGreen)
        }
        monitorGraphs[ws] = graph
        monitorColumn.addView(graph, LinearLayout.LayoutParams(-1, 0, 1f))

        if (bottomWindowFor(ws) == "monitor") {
            win.addView(monitorColumn, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            win.addView(playerScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        bottomWindowViews[ws] = win
        updateBottomWindowHeader(ws)
        win.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateBottomWindowHeader(ws)
            val availableHeight = view.height - titleRow.height -
                view.paddingTop - view.paddingBottom
            val showQueue = availableHeight >= dp(200)
            queueLabel.visibility = if (showQueue) View.VISIBLE else View.GONE
            queueScroll.visibility = if (showQueue) View.VISIBLE else View.GONE
        }
        return win
    }

    private fun updateBottomWindowHeader(ws: Int) {
        val choice = bottomWindowFor(ws)
        bottomWindowTitles[ws]?.text = if (choice == "player") "~/player" else "~/monitor"
        val win = bottomWindowViews[ws] ?: return
        (win.getChildAt(0) as? LinearLayout)?.let { row ->
            for (i in 1 until row.childCount) {
                val tab = row.getChildAt(i) as? TextView ?: continue
                tab.setTextColor(if (tab.text.toString() == choice) pal.cAccent else pal.cDim)
            }
        }
    }

    private fun updateBottomWindowDisplay(ws: Int, animate: Boolean) {
        val win = bottomWindowViews[ws] ?: return
        val choice = bottomWindowFor(ws)
        val playerContent = bottomWindowContents[ws]
        val monitorContent = bottomMonitorColumns[ws]

        fun applyChoice() {
            updateBottomWindowHeader(ws)

            // Keep the title row, then replace the content slot with exactly
            // one known view. Do not infer which view is active from parent links.
            while (win.childCount > 1) {
                win.removeViewAt(1)
            }
            val selected = if (choice == "player") playerContent else monitorContent
            if (selected != null) {
                (selected.parent as? ViewGroup)?.removeView(selected)
                win.addView(selected, LinearLayout.LayoutParams(-1, 0, 1f))
            }

            if (choice == "player") {
                updatePlayerWindow(ws)
                if (ws == curWs && activityResumed) startPlayerCallbacks()
            } else {
                updateMonitorSummary(ws)
                monitorGraphs[ws]?.setColors(pal.cDim, pal.cBlue, pal.cGreen)
            }
            win.alpha = 1f
            win.requestLayout()
            selected?.requestLayout()
        }

        if (animate) {
            win.animate().cancel()
            win.animate().alpha(0f).setDuration(90).withEndAction {
                applyChoice()
                win.animate().alpha(1f).setDuration(120).start()
            }.start()
        } else {
            applyChoice()
        }
    }

    private fun buildPage(ws: Int): View {
        val p = pal
        val row = TileLayout(this, dp(12)).apply {
            setPadding(0, dp(8), 0, 0)
            setOnLongClickListener { showSettings(); true }
        }
        row.moveModeActive = { moveSource[ws] >= 0 }
        row.onMoveTap = { id -> moveTap(ws, id) }

        // ---- terminal window ----
        val term = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = box(panelColor(), p.cAccent)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val terminalTitle = txt(12.5f, p.cBlue, "~/terminal").apply {
            setOnLongClickListener {
                if (moveSource[ws] >= 0) moveTap(ws, "terminal")
                else startMove(ws, "terminal")
                true
            }
        }
        col.addView(terminalTitle, LinearLayout.LayoutParams(-1, -2))
        col.addView(buildFetch(ws), LinearLayout.LayoutParams(-1, -2))
        val out = txt(12.5f, termFg()).apply {
            setLineSpacing(0f, 1.15f)
            text = history[ws]
        }
        col.addView(out, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
        outViews[ws] = out
        outScrolls[ws] = scroll
        term.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        var input: EditText? = null
        var promptRow: LinearLayout? = null
        if (mode != "drawer") {
            promptRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            promptRow.addView(
                txt(14f, p.cFg, SpannableStringBuilder().add("~ ", p.cBlue).add("❯ ", p.cGreen))
            )
            input = EditText(this).apply {
                setTextColor(termFg())
                setHintTextColor(p.cDim)
                hint = "type an app or command"
                typeface = font
                textSize = 14f
                background = null
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_GO
                setPadding(0, dp(8), 0, dp(8))
                setOnEditorActionListener { v, _, _ ->
                    val cmd = v.text.toString().trim()
                    v.text = ""
                    runCommand(ws, cmd)
                    true
                }
            }
            promptRow!!.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            term.addView(promptRow, LinearLayout.LayoutParams(-1, -2))
        }
        val field = input
        terminalPrompts[ws] = field
        fun focusInput() {
            if (field != null) {
                drawerOwnsIme = false
                field.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        fun focusTerminal(showKeyboard: Boolean) {
            if (moveTap(ws, "terminal")) return
            val pinned = pinWindowForFocus[ws]
            if (pinned != null) setWindowFocus(ws, 0, term, pinned)
            else focusedWindow[ws] = 0
            if (showKeyboard) focusInput()
        }

        promptRow?.setOnClickListener {
            focusTerminal(false)
            focusInput()
        }

        term.setOnClickListener { focusTerminal(false) }
        out.setOnClickListener { focusTerminal(false) }
        col.setOnClickListener { focusTerminal(false) }
        scroll.setOnClickListener { focusTerminal(false) }
        // Long-pressing the terminal surface opens configuration.
        // Only the explicit ~/terminal title is the tile-move handle.
        val openTerminalConfig = View.OnLongClickListener { showSettings(); true }
        term.setOnLongClickListener(openTerminalConfig)
        out.setOnLongClickListener(openTerminalConfig)
        col.setOnLongClickListener(openTerminalConfig)
        scroll.setOnLongClickListener(openTerminalConfig)

        terminalWindowForFocus[ws] = term
        val showTerminalWindow = windowShown(ws, "terminal")
        val showPinnedWindow = windowShown(ws, "pinned")
        val showBottomWindow = windowShown(ws, "bottom")
        term.tag = "terminal"
        row.addView(term, ViewGroup.LayoutParams(-1, -1))

        // ---- pinned window ----
        val pinWin = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = box(panelColor(), p.cDim)
            setOnLongClickListener { showSettings(); true }
        }
        pinWindowForFocus[ws] = pinWin
        pinWin.setOnClickListener {
            if (!moveTap(ws, "pinned")) setWindowFocus(ws, 1, term, pinWin)
        }
        setWindowFocus(ws, focusedWindow[ws], term, pinWin)
        val pinTitle = txt(13f, p.cBlue, "~/pinned")
        pinTitle.setOnLongClickListener { if (moveSource[ws] >= 0) moveTap(ws, "pinned") else startMove(ws, "pinned"); true }
        pinWin.addView(pinTitle)
        val pinList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinBoxes[ws] = pinList
        pinWin.addView(pinList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        val defaultPrompt = txt(12.5f, p.cBlue, "Set as default launcher").apply {
            setPadding(0, dp(12), 0, dp(4))
            visibility = View.GONE
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        defaultLauncherPrompts[ws] = defaultPrompt
        pinWin.addView(defaultPrompt, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
        })
        fillPins(ws)
        val bottomWin = buildBottomWindow(ws)
        bottomWindowForFocus[ws] = bottomWin
        bottomWin.setOnClickListener { if (!moveTap(ws, "bottom")) setWindowFocus(ws, 2, term, pinWin) }
        pinWin.tag = "pinned"
        bottomWin.tag = "bottom"
        row.addView(pinWin, ViewGroup.LayoutParams(-1, -1))
        row.addView(bottomWin, ViewGroup.LayoutParams(-1, -1))
        tileLayouts[ws] = row
        updateBottomWindowDisplay(ws, false)
        updateMonitorSummary(ws)
        updatePlayerWindow(ws)
        row.configure(layoutModeFor(ws), layoutRatioFor(ws),
            tileOrder(ws),
            buildSet {
                if (showTerminalWindow) add("terminal")
                if (showPinnedWindow) add("pinned")
                if (showBottomWindow) add("bottom")
            }, false)
        return row
    }

    // ---- fastfetch block (runs by default on every workspace) ----
    private fun buildFetch(ws: Int): View {
        val p = pal
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        v.addView(
            txt(
                12.5f, p.cFg,
                SpannableStringBuilder().add("~ ", p.cBlue).add("❯ ", p.cGreen).add("fastfetch", termFg())
            )
        )
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        logoView()?.let { r.addView(it) }
        val info = txt(12f, termFg()).apply {
            setLineSpacing(0f, 1.15f)
            text = fetchText()
        }
        fetchInfos[ws] = info
        r.addView(info, LinearLayout.LayoutParams(-2, -2))
        v.addView(r, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun logoDrawable(): Drawable? {
        val s = prefs.getString("logo_uri", null) ?: return null
        return try {
            val uri = Uri.parse(s)
            if (Build.VERSION.SDK_INT >= 28) {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 320) dec.setTargetSize(info.size.width * 320 / m, info.size.height * 320 / m)
                }
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                d
            } else {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    ?.let { BitmapDrawable(resources, it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun logoView(): View? {
        val p = pal
        val name = logoNames[prefs.getInt("logo", 0).coerceIn(0, logoNames.size - 1)]
        if (name == "none") return null
        if (name == "image") {
            val d = logoDrawable()
            if (d != null) {
                return ImageView(this).apply {
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)).apply { marginEnd = dp(18) }
                }
            }
        }
        val art = when (name) {
            "arch" -> archArt
            "omarchy" -> omarchyArt
            else -> androidArt
        }
        return TextView(this).apply {
            text = art
            typeface = Typeface.MONOSPACE
            textSize = 8f
            includeFontPadding = false
            setTextColor(if (name == "arch") p.cBlue else p.cGreen)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) }
        }
    }

    private fun updateMonitorSummary(ws: Int) {
        val view = monitorSummaries[ws] ?: return
        val p = pal
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)

        fun line(label: String, used: Long, total: Long): SpannableStringBuilder {
            val safeTotal = total.coerceAtLeast(1L)
            val pct = ((used.coerceAtLeast(0L) * 100L) / safeTotal).toInt().coerceIn(0, 100)
            val color = when {
                pct > 90 -> Color.RED
                pct > 75 -> Color.rgb(255, 191, 0)
                else -> p.cGreen
            }
            val gb = 1024.0 * 1024 * 1024
            return SpannableStringBuilder().apply {
                append(label.padEnd(5))
                val start = length
                append(bar(pct))
                setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" %.1f/%.1fG %3d%%".format(used.coerceAtLeast(0L) / gb, safeTotal / gb, pct))
            }
        }

        val diskStats = StatFs(Environment.getDataDirectory().path)
        val diskTotal = diskStats.blockCountLong * diskStats.blockSizeLong
        val diskFree = diskStats.availableBlocksLong * diskStats.blockSizeLong
        val diskUsed = (diskTotal - diskFree).coerceAtLeast(0L)

        val sd = sdPath()
        val sdStats = try {
            sd?.let { StatFs(it) }
        } catch (_: Exception) {
            null
        }
        val sdTotal = sdStats?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val sdFree = sdStats?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L

        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)

        val text = SpannableStringBuilder()
        text.append(line("RAM", mi.totalMem - mi.availMem, mi.totalMem)).append("\n")
        text.append(line("Disk", diskUsed, diskTotal)).append("\n")
        if (sdStats == null) {
            text.append("SD   not inserted").append("\n")
        } else {
            text.append(line("SD", sdTotal - sdFree, sdTotal)).append("\n")
        }
        text.append(line("Batt", battery.toLong(), 100L)).append("\n")
        text.append(
            "down ${fmtSpeed(sharedNetHistory.lastDown.toLong())}  " +
                "up ${fmtSpeed(sharedNetHistory.lastUp.toLong())}  " +
                "peak ${fmtSpeed(sharedNetHistory.peak.toLong())}"
        )
        view.text = text
    }

    private fun fetchText(): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        fetchInto(sb)
        return sb
    }

    private fun refreshFetch(ws: Int) {
        fetchInfos[ws]?.text = fetchText()
    }

    private fun disk(path: String): String = try {
        val s = StatFs(path)
        val total = s.blockCountLong * s.blockSizeLong
        val free = s.availableBlocksLong * s.blockSizeLong
        val gb = 1024.0 * 1024 * 1024
        "%.1f / %.1f GiB (%d%%)".format((total - free) / gb, total / gb, ((total - free) * 100 / total).toInt())
    } catch (_: Exception) {
        "n/a"
    }

    private fun sdPath(): String? {
        val dirs = getExternalFilesDirs(null)
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            try {
                if (Environment.isExternalStorageRemovable(d)) return d.path
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun battTemp(): Float? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (t > 0) t / 10f else null
    }

    private fun cpuTemp(): Float? {
        var best: Float? = null
        for (i in 0..19) {
            try {
                val f = File("/sys/class/thermal/thermal_zone$i/temp")
                if (!f.canRead()) continue
                val v = f.readText().trim().toFloat()
                val c = if (v > 1000f) v / 1000f else v
                val b = best
                if (c in 10f..120f && (b == null || c > b)) best = c
            } catch (_: Exception) {
            }
        }
        return best
    }

    private fun fetchInto(sb: SpannableStringBuilder) {
        val p = pal
        val fg = termFg()
        fun kv(k: String, v: String) {
            sb.add(k.padEnd(8), p.cBlue).add(v + "\n", fg)
        }

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gb = 1024.0 * 1024 * 1024
        val dm = resources.displayMetrics
        val mins = SystemClock.elapsedRealtime() / 60000
        val bt = battTemp()
        val ct = cpuTemp()
        val sd = sdPath()

        sb.add("┌─ Hardware ───────────\n", p.cDim)
        kv("PC", "${Build.MANUFACTURER} ${Build.MODEL}")
        kv("SoC", Build.HARDWARE)
        kv("RAM", "%.1f / %.1f GiB".format((mi.totalMem - mi.availMem) / gb, mi.totalMem / gb))
        kv("Screen", "${dm.widthPixels}x${dm.heightPixels}")
        kv(
            "Temp",
            (if (bt != null) "batt %.1f°C".format(bt) else "batt n/a") + "  " +
                (if (ct != null) "cpu %.0f°C".format(ct) else "cpu n/a")
        )
        sb.add("\n┌─ Storage ────────────\n", p.cDim)
        kv("Disk", disk(Environment.getDataDirectory().path))
        kv("SD", if (sd != null) disk(sd) else "not inserted")
        sb.add("\n┌─ Software ───────────\n", p.cDim)
        kv("OS", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        kv("Kernel", System.getProperty("os.version") ?: "?")
        kv("Shell", "Sorairo")
        kv("Apps", "${apps.size} (${allApps.size - apps.size} hidden)")
        kv("Theme", pal.name)
        kv("Up", "${mins / 60}h ${mins % 60}m")
        sb.add("\ntype help for commands\n", p.cDim)
    }

    private fun showPinnedPicker(ws: Int) {
        closeQs()
        fillPinnedPicker(ws)
        setScroll.scrollTo(0, 0)
        showOverlay()
    }

    private fun fillPinnedPicker(ws: Int) {
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/pinned"))
        setBox.addView(txt(11f, p.cDim, "pick the apps pinned to workspace ${ws + 1}").apply { setPadding(0, dp(2), 0, dp(6)) })
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { closeSettings() }
        })
        allApps.forEach { app ->
            val chosen = app.pkg in pins(ws)
            setBox.addView(txt(14f, if (chosen) p.cAccent else p.cFg, displayAppLabel(app)).apply {
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { togglePin(ws, app); fillPinnedPicker(ws) }
            })
        }
    }

    private fun fillPins(ws: Int) {
        val p = pal
        val list = pinBoxes[ws] ?: return
        list.removeAllViews()
        val pinned = pins(ws).mapNotNull { k -> allApps.find { it.pkg == k } }

        if (pinned.isEmpty()) {
            list.addView(
                txt(
                    12.5f, p.cDim,
                    "nothing pinned yet.\nconfigure pinned apps in ~/config"
                )
            )
        } else {
            pinned.forEach { app ->
                list.addView(
                    txt(16f, p.cFg, displayAppLabel(app)).apply {
                        setPadding(0, dp(9), 0, dp(9))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setOnClickListener {
                            val terminal = terminalWindowForFocus[ws]
                            val pinnedWindow = pinWindowForFocus[ws]
                            if (terminal != null && pinnedWindow != null) {
                                setWindowFocus(ws, 1, terminal, pinnedWindow)
                            }
                            launchApp(app)
                        }
                        setOnLongClickListener { true }
                    }
                )
            }
        }

        defaultLauncherPrompts[ws]?.visibility =
            if (isDefaultLauncher()) View.GONE else View.VISIBLE
    }

    private fun updateWsBar() {
        wsLabels.forEachIndexed { i, t ->
            if (i == curWs) {
                t.text = "●"
                t.setTextColor(pal.cFg)
            } else {
                t.text = "${i + 1}"
                t.setTextColor(pal.cDim)
            }
        }
    }

    // ================= settings =================
    private fun showOverlay() {
        // Cancel any stale fade-out callback before reusing the settings overlay.
        settings.animate().cancel()
        settings.visibility = View.VISIBLE
        settings.alpha = 0f
        settings.animate().alpha(1f).setDuration(140).start()
    }

    private fun showSettings() {
        closeDrawer()
        closeQs()
        fillSettings()
        setScroll.scrollTo(0, 0)
        showOverlay()
    }

    // ================= quick settings =================
    private fun openQs() {
        closeDrawer()
        askedWrite = false
        fillQs()
        qs.visibility = View.VISIBLE
        qs.alpha = 0f
        qs.animate().alpha(1f).setDuration(140).start()
    }

    private fun closeQs() {
        if (qs.visibility != View.VISIBLE) return
        qs.animate().alpha(0f).setDuration(120).withEndAction { qs.visibility = View.GONE }.start()
    }

    private fun openSystem(action: String) {
        closeQs()
        closeSettings()
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            Toast.makeText(this, "Can't open that setting", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mediaAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setBrightness(v: Float) {
        try {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                    (v * 255).roundToInt().coerceIn(1, 255)
                )
            } else if (!askedWrite) {
                askedWrite = true
                closeQs()
                Toast.makeText(this, "Allow \"modify system settings\" for brightness", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {
        }
    }

    private fun launchVpn() {
        val app = allApps.find { it.pkg == prefs.getString("vpn_pkg", null) }
        if (app == null) showVpnPicker() else launchApp(app)
    }

    private fun fillVpnPicker() {
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/vpn-app"))
        setBox.addView(
            txt(11f, p.cDim, "pick the app the vpn tile opens").apply { setPadding(0, dp(2), 0, dp(6)) }
        )
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { fillSettings(); setScroll.scrollTo(0, 0) }
        })
        fun fileRow(k: String, v: String, onTap: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { onTap() }
            }
            r.addView(txt(14f, p.cFg, k), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(14f, p.cAccent, v))
            setBox.addView(r)
        }
        fileRow("save list to a file", "export ›") { exportHiddenFile.launch("sorairo-hidden-apps.txt") }
        fileRow("restore list from a file", "import ›") { importHiddenFile.launch(arrayOf("*/*")) }
        allApps.forEach { app ->
            val chosen = app.pkg == prefs.getString("vpn_pkg", null)
            setBox.addView(
                txt(14f, if (chosen) p.cAccent else p.cFg, displayAppLabel(app)).apply {
                    setPadding(0, dp(9), 0, dp(9))
                    setOnClickListener {
                        prefs.edit().putString("vpn_pkg", app.pkg).apply()
                        closeSettings()
                    }
                }
            )
        }
    }

    private fun showVpnPicker() {
        closeQs()
        fillVpnPicker()
        setScroll.scrollTo(0, 0)
        showOverlay()
    }

    private var wifiScanReceiver: android.content.BroadcastReceiver? = null
    private var wifiNetworkList: LinearLayout? = null
    private var wifiScanStatus: TextView? = null

    @Suppress("DEPRECATION")
    private fun showWifiPanel() {
        val p = pal
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasWifi = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = box(p.cBg, p.cDim)
        }
        root.addView(txt(11f, p.cBlue, "~/network").apply { setPadding(0, 0, 0, dp(5)) })
        root.addView(txt(22f, p.cFg, "Wi-Fi").apply { setPadding(0, 0, 0, dp(3)) })
        root.addView(txt(11f, p.cDim, "CURRENT CONNECTION").apply { setPadding(0, 0, 0, dp(12)) })

        val current = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = box(p.cSel, p.cAccent)
        }
        val currentName = try {
            wifi.connectionInfo.ssid?.removePrefix("\"")?.removeSuffix("\"")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?: if (hasWifi) "Connected network · name hidden by Android" else "Not connected"
        } catch (_: Exception) {
            if (hasWifi) "Connected network · name hidden by Android" else "Not connected"
        }
        current.addView(txt(14f, p.cFg, currentName))
        current.addView(txt(11f, p.cAccent, if (hasWifi) "connected" else "not connected").apply {
            setPadding(0, dp(3), 0, 0)
        })
        root.addView(current)

        root.addView(txt(10f, p.cDim, "NEARBY NETWORKS").apply { setPadding(0, dp(14), 0, dp(6)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiNetworkList = list
        root.addView(list)
        val status = txt(10f, p.cDim, "DISCOVERY OFF")
        wifiScanStatus = status
        root.addView(status.apply { setPadding(0, dp(5), 0, 0) })

        fun action(label: String, primary: Boolean = false, onClick: () -> Unit) =
            txt(12f, if (primary) p.cBg else p.cFg, label).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = box(if (primary) p.cAccent else p.cSel, if (primary) p.cAccent else p.cDim)
                setOnClickListener { onClick() }
            }

        fun locationEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
            (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
        } else {
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }

        fun renderNetworks(message: String? = null) {
            list.removeAllViews()
            val permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!permitted) {
                status.text = "DISCOVERY OFF · LOCATION ACCESS NOT GRANTED"
                list.addView(txt(12f, p.cDim, "Nearby network names need Android's Location permission. Your current connection is shown above.")
                    .apply { setPadding(0, dp(6), 0, dp(8)) })
                list.addView(action("Enable discovery", true) {
                    showWifiDiscoveryPrompt {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 741)
                    }
                })
                return
            }
            if (!locationEnabled()) {
                status.text = "DEVICE LOCATION IS OFF"
                list.addView(txt(12f, p.cDim, "Turn on Location only when you want Sorairo to discover nearby networks.")
                    .apply { setPadding(0, dp(6), 0, dp(8)) })
                list.addView(action("Enable Location") {
                    showWifiDiscoveryPrompt {
                        openSystem(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    }
                })
                return
            }
            val results = try { wifi.scanResults } catch (_: Exception) { emptyList<ScanResult>() }
            val unique = results.filter { it.SSID.isNotBlank() && it.SSID != "<unknown ssid>" }
                .distinctBy { it.SSID }.sortedByDescending { it.level }
            if (unique.isEmpty()) {
                status.text = message ?: "NO RESULTS YET · TAP REFRESH"
                list.addView(txt(12f, p.cDim, "No nearby networks returned yet.")
                    .apply { setPadding(0, dp(6), 0, dp(8)) })
            } else {
                status.text = "${unique.size} NETWORKS FOUND"
                unique.take(12).forEach { result ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(9), dp(8), dp(9))
                        background = box(p.cBg, p.cDim)
                        setOnClickListener {
                            wifiDialog?.dismiss()
                            openSystem(Settings.ACTION_WIFI_SETTINGS)
                        }
                    }
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    info.addView(txt(13f, p.cFg, result.SSID))
                    val secure = result.capabilities.contains("WPA") || result.capabilities.contains("WEP")
                    info.addView(txt(10f, p.cDim, if (secure) "secured · open Android to connect" else "open · open Android to connect")
                        .apply { setPadding(0, dp(2), 0, 0) })
                    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(txt(11f, p.cAccent, "${result.level} dBm"))
                    list.addView(row)
                    list.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(1)) })
                }
            }
        }

        fun beginScan() {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !locationEnabled()) {
                renderNetworks()
                return
            }
            status.text = "SCANNING…"
            try {
                if (!wifi.startScan()) renderNetworks("SCAN THROTTLED · SHOWING LAST RESULTS")
            } catch (_: SecurityException) {
                renderNetworks("SCAN PERMISSION DENIED")
            } catch (_: Exception) {
                renderNetworks("SCAN UNAVAILABLE")
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(action("Refresh") { beginScan() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        actions.addView(action("Network settings", true) {
            wifiDialog?.dismiss()
            openSystem(Settings.ACTION_WIFI_SETTINGS)
        })
        root.addView(actions)

        wifiDialog?.dismiss()
        wifiDialog = AlertDialog.Builder(this).create().apply {
            setView(root)
            setOnDismissListener {
                wifiScanReceiver?.let { receiver -> try { unregisterReceiver(receiver) } catch (_: Exception) {} }
                wifiScanReceiver = null
                wifiNetworkList = null
                wifiScanStatus = null
                wifiDialog = null
            }
            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(460)), -2)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    renderNetworks(if (updated) "SCAN COMPLETE" else "SCAN THROTTLED · LAST RESULTS")
                }
            }
        }
        wifiScanReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } catch (_: Exception) {}
        renderNetworks()
        // Do not request location or start scanning just by opening the Wi-Fi panel.
    }

    private fun showWifiDiscoveryPrompt(onContinue: () -> Unit) {
        val p = pal
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            background = box(p.cBg, p.cDim)
        }
        content.addView(txt(11f, p.cBlue, "~/network/discovery"))
        content.addView(txt(18f, p.cFg, "Discover nearby networks").apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(txt(12f, p.cDim,
            "Android requires Location permission and Location services to scan nearby Wi-Fi names. Sorairo only requests this when you choose network discovery. Your current connection remains available without enabling discovery.")
            .apply { setPadding(0, 0, 0, dp(12)) })
        AlertDialog.Builder(this).create().apply {
            setView(content)
            setButton(AlertDialog.BUTTON_NEGATIVE, "CANCEL") { _, _ -> }
            setButton(AlertDialog.BUTTON_POSITIVE, "CONTINUE") { _, _ -> onContinue() }
            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(420)), -2)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 741) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                wifiDialog?.dismiss()
                showWifiPanel()
            } else {
                wifiScanStatus?.text = "LOCATION PERMISSION DENIED"
            }
        } else if (requestCode == 742 && permissionConfigOpen && settings.visibility == View.VISIBLE) {
            fillPermissionSettings()
        }
    }

    private var wifiDialog: AlertDialog? = null

    private fun fillQs() {
        val p = pal
        qsBox.removeAllViews()
        qsBox.addView(txt(15f, p.cBlue, "~/quick"))

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = netLabel()
        val wifiOn = net.startsWith("wifi")
        val btOn = try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
        val ring = am.ringerMode
        val airplane = try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
        val saver = (getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode
        val micMuted = am.isMicrophoneMute
        val vpnOn = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
        val vpnApp = allApps.find { it.pkg == prefs.getString("vpn_pkg", null) }

        fun tile(name: String, state: String, on: Boolean, hold: (() -> Unit)? = null, tap: () -> Unit): View {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = box(
                    if (on) p.cSel else (p.cSel and 0x00FFFFFF) or (0x44 shl 24),
                    if (on) p.cAccent else p.cDim
                )
                setOnClickListener { tap() }
                if (hold != null) setOnLongClickListener { hold(); true }
            }
            t.addView(txt(14f, p.cFg, name))
            t.addView(txt(11f, if (on) p.cAccent else p.cDim, state))
            return t
        }

        val ringState = when (ring) {
            AudioManager.RINGER_MODE_SILENT -> "on"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "off"
        }
        val tiles = listOf(
            tile("wifi", if (wifiOn) net else "off", wifiOn) {
                closeQs()
                showWifiPanel()
            },
            tile("bluetooth", if (btOn) "on" else "off", btOn) {
                openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
            },
            tile("silent", ringState, ring == AudioManager.RINGER_MODE_SILENT) {
                try {
                    am.ringerMode =
                        if (ring == AudioManager.RINGER_MODE_SILENT) AudioManager.RINGER_MODE_NORMAL
                        else AudioManager.RINGER_MODE_SILENT
                    fillQs()
                } catch (_: SecurityException) {
                    Toast.makeText(this, "Allow Do Not Disturb access first", Toast.LENGTH_LONG).show()
                    openSystem(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                }
            },
            tile("airplane", if (airplane) "on" else "off", airplane) {
                openSystem(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            },
            tile("battery saver", if (saver) "on" else "off", saver) {
                openSystem(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            },
            tile("mic", if (micMuted) "muted" else "live", micMuted, { openSystem(Settings.ACTION_PRIVACY_SETTINGS) }) {
                am.setMicrophoneMute(!micMuted)
                fillQs()
            },
            tile("camera", "privacy ›", false) { openSystem(Settings.ACTION_PRIVACY_SETTINGS) },
            tile(
                "vpn", if (vpnOn) "connected" else (vpnApp?.label ?: "pick app"), vpnOn,
                { showVpnPicker() }
            ) { launchVpn() }
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in tiles.indices step 2) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(tiles[i], LinearLayout.LayoutParams(dp(130), dp(62)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            if (i + 1 < tiles.size) {
                r.addView(tiles[i + 1], LinearLayout.LayoutParams(dp(130), dp(62)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            grid.addView(r)
        }

        val bright = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        } catch (_: Exception) {
            0.5f
        }
        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxV

        fun sliderCol(label: String, init: Float, onChange: (Float) -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val pct = txt(11f, p.cDim, "${(init * 100).roundToInt()}%")
            val sl = VSlider(this, p.cAccent, (p.cDim and 0x00FFFFFF) or (0x66 shl 24)) { v ->
                pct.text = "${(v * 100).roundToInt()}%"
                onChange(v)
            }
            sl.value = init
            c.addView(sl, LinearLayout.LayoutParams(dp(46), dp(250)))
            c.addView(pct, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            c.addView(txt(11f, p.cFg, label))
            return c
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(grid)
        body.addView(
            sliderCol("bright", bright) { v -> setBrightness(v) },
            LinearLayout.LayoutParams(dp(60), -2).apply { marginStart = dp(10) }
        )
        body.addView(
            sliderCol("vol", vol) { v ->
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxV).roundToInt(), 0)
            },
            LinearLayout.LayoutParams(dp(60), -2).apply { marginStart = dp(4) }
        )
        qsBox.addView(body)
    }

    // ================= now playing =================
    private fun togglePlay() {
        val c = ctrl ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
        handler.postDelayed({ updateNowPlaying() }, 400)
    }

    private fun updateNowPlaying() {
        var title: String? = null
        var playing = false
        val c = activeController()
        ctrl = c
        val md = c?.metadata
        val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (!t.isNullOrEmpty()) {
            title = if (a.isNullOrEmpty()) t else "$t — $a"
            playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (title == null) {
            song.visibility = View.GONE
            vis.visibility = View.GONE
            vis.playing = false
        } else {
            song.text = title
            song.setTextColor(if (playing) pal.cFg else pal.cDim)
            song.visibility = View.VISIBLE
            vis.visibility = View.VISIBLE
            vis.color = if (playing) pal.cGreen else pal.cDim
            vis.playing = playing
        }
    }

    private fun closeSettings() {
        val closingSettings = settings
        if (closingSettings.visibility != View.VISIBLE) return
        closingSettings.animate().cancel()
        closingSettings.animate().alpha(0f).setDuration(120)
            .withEndAction {
                // Capture the view being closed; never hide a replacement overlay
                // created by rebuild() while this animation is finishing.
                closingSettings.visibility = View.GONE
            }.start()
    }

    private var styleConfigOpen = false
    private var permissionConfigOpen = false

    private fun cycle(key: String, n: Int, def: Int = 0) {
        val returnToStyle = styleConfigOpen
        prefs.edit().putInt(key, (prefs.getInt(key, def) + 1) % n).apply()
        rebuild(false)
        if (returnToStyle) {
            fillStyleSettings()
            setScroll.scrollTo(0, 0)
            showOverlay()
        } else {
            showSettings()
        }
    }

    private fun fillSettings() {
        styleConfigOpen = false
        permissionConfigOpen = false
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/config"))
        setBox.addView(txt(11f, p.cDim, "choose what you want to configure").apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        fun row(k: String, v: String, onTap: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { onTap() }
            }
            r.addView(txt(14f, p.cFg, k), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(14f, p.cAccent, v))
            setBox.addView(r)
        }
        row("style", "appearance & layout ›") {
            fillStyleSettings()
            setScroll.scrollTo(0, 0)
        }
        row("pinned apps", "workspace ${curWs + 1} ›") {
            fillPinnedPicker(curWs); setScroll.scrollTo(0, 0)
        }
        row("default launcher", "change ›") {
            closeSettings()
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        row("perms", "access & system settings ›") {
            fillPermissionSettings()
            setScroll.scrollTo(0, 0)
        }
        row("vpn app", allApps.find { it.pkg == prefs.getString("vpn_pkg", null) }?.label ?: "choose ›") {
            fillVpnPicker(); setScroll.scrollTo(0, 0)
        }
        row("hidden apps", "${allApps.size - apps.size} ›") {
            fillHidden(); setScroll.scrollTo(0, 0)
        }
        row("close", "esc") { closeSettings() }
    }

    private fun fillPermissionSettings() {
        permissionConfigOpen = true
        styleConfigOpen = false
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/config/permissions"))
        setBox.addView(txt(11f, p.cDim, "access Sorairo uses and manage each permission").apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { fillSettings(); setScroll.scrollTo(0, 0) }
        })

        fun locationEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
            (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
        } else {
            Settings.Secure.getInt(
                contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }

        fun permissionRow(
            title: String,
            detail: String,
            state: String,
            granted: Boolean,
            onTap: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { onTap() }
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(txt(14f, p.cFg, title))
            copy.addView(txt(11f, p.cDim, detail).apply {
                setPadding(0, dp(3), dp(8), 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(txt(11f, if (granted) p.cGreen else p.cAccent, state))
            setBox.addView(row)
            setBox.addView(View(this).apply {
                setBackgroundColor(p.cDim and 0x55FFFFFF)
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }

        val homeGranted = isDefaultLauncher()
        permissionRow(
            "Default launcher",
            "Lets Sorairo handle the Home button and display your workspaces.",
            if (homeGranted) "active" else "set up ›",
            homeGranted
        ) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }

        val notificationsGranted = mediaAccess()
        permissionRow(
            "Notification access",
            "Used by the media panel to read media-session and playback information.",
            if (notificationsGranted) "granted" else "enable ›",
            notificationsGranted
        ) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) { openSystem(Settings.ACTION_SETTINGS) }
        }

        val writeGranted = Settings.System.canWrite(this)
        permissionRow(
            "Modify system settings",
            "Allows the brightness control to change the screen brightness directly.",
            if (writeGranted) "granted" else "enable ›",
            writeGranted
        ) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                openSystem(Settings.ACTION_SETTINGS)
            }
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndGranted = notificationManager.isNotificationPolicyAccessGranted
        permissionRow(
            "Do Not Disturb access",
            "Used by the quick-settings silent tile to change the ringer mode.",
            if (dndGranted) "granted" else "enable ›",
            dndGranted
        ) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            catch (_: Exception) { openSystem(Settings.ACTION_SETTINGS) }
        }

        val locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val locationReady = locationGranted && locationEnabled()
        permissionRow(
            "Location for Wi-Fi discovery",
            "Only used when you opt in to scan and display nearby Wi-Fi networks. Not needed to open the Wi-Fi panel.",
            when {
                locationReady -> "ready"
                locationGranted -> "turn on ›"
                else -> "enable ›"
            },
            locationReady
        ) {
            when {
                !locationGranted -> requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 742
                )
                !locationEnabled() -> try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                    openSystem(Settings.ACTION_SETTINGS)
                }
                else -> Toast.makeText(this, "Wi-Fi discovery is ready", Toast.LENGTH_SHORT).show()
            }
        }

        permissionRow(
            "Network & Wi-Fi state",
            "Install-time access used to detect connectivity and read Wi-Fi state. Android grants this automatically.",
            "automatic",
            true
        ) { }

        permissionRow(
            "Audio settings",
            "Install-time access used for supported audio controls. Android grants this automatically.",
            "automatic",
            true
        ) { }

        setBox.addView(txt(
            11f, p.cDim,
            "Sorairo never enables special access silently. Android may ask you to confirm access in its own settings."
        ).apply { setPadding(0, dp(10), 0, dp(4)) })
    }

    private fun fillStyleSettings() {
        styleConfigOpen = true
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/config/style"))
        setBox.addView(txt(11f, p.cDim, "appearance, wallpaper and workspace layout").apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { fillSettings(); setScroll.scrollTo(0, 0) }
        })
        fun row(k: String, v: String, onTap: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { onTap() }
            }
            r.addView(txt(14f, p.cFg, k), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(14f, p.cAccent, v))
            setBox.addView(r)
        }
        row("home screen", "customize ›") {
            fillHomeScreenSettings(); setScroll.scrollTo(0, 0)
        }
        row("wallpaper", "choose ›") { closeSettings(); pickWall.launch(arrayOf("image/*")) }
        row("clear wallpaper", "›") {
            prefs.edit().remove("wall").apply(); wall.setImageDrawable(null)
        }
        row("theme", pal.name) { cycle("theme", pals.size) }
        row("font", fontNames[prefs.getInt("font", 0).coerceIn(0, fontNames.size - 1)]) {
            cycle("font", fontNames.size)
        }
        row("terminal color", termColors[prefs.getInt("tcolor", 0).coerceIn(0, termColors.size - 1)].first) {
            cycle("tcolor", termColors.size)
        }
        row("window opacity", "${opacities[prefs.getInt("opacity", 2).coerceIn(0, opacities.size - 1)]}%") {
            cycle("opacity", opacities.size, 2)
        }
        row("launch style", mode) { cycle("mode", launchModes.size) }
        row("fetch logo", logoNames[prefs.getInt("logo", 0).coerceIn(0, logoNames.size - 1)]) {
            val n = (prefs.getInt("logo", 0) + 1) % logoNames.size
            prefs.edit().putInt("logo", n).apply()
            if (logoNames[n] == "image" && prefs.getString("logo_uri", null) == null) {
                pickLogoFromStyle()
            } else {
                rebuild(false)
                fillStyleSettings()
                setScroll.scrollTo(0, 0)
                showOverlay()
            }
        }
        row("logo image / gif", "choose ›") { pickLogoFromStyle() }
        row("window layout", layoutModeFor(curWs)) {
            val modes = listOf("dwindle", "master", "columns", "rows")
            setLayoutMode(curWs, modes[(modes.indexOf(layoutModeFor(curWs)) + 1) % modes.size])
            fillStyleSettings()
        }
        row("window ratio", "${(layoutRatioFor(curWs) * 100).toInt()}%") {
            val ratios = listOf(40, 50, 60, 70)
            val current = (layoutRatioFor(curWs) * 100).toInt()
            setLayoutRatio(curWs, ratios[(ratios.indexOf(current).coerceAtLeast(0) + 1) % ratios.size])
            fillStyleSettings()
        }
        row("layout scope", if (layoutScopeAll()) "all workspaces" else "this workspace") {
            val makeAll = !layoutScopeAll()
            val currentMode = layoutModeFor(curWs)
            val currentRatio = (layoutRatioFor(curWs) * 100).toInt()
            val edit = prefs.edit().putBoolean("layout_scope_all", makeAll)
            if (makeAll) {
                edit.putString("window_layout_mode_global", currentMode)
                edit.putInt("window_layout_ratio_global", currentRatio)
            } else {
                for (ws in 0..4) {
                    if (!prefs.contains("window_layout_mode_$ws")) edit.putString("window_layout_mode_$ws", currentMode)
                    if (!prefs.contains("window_layout_ratio_$ws")) edit.putInt("window_layout_ratio_$ws", currentRatio)
                }
            }
            edit.apply()
            for (ws in 0..4) applyTileLayout(ws)
            fillStyleSettings()
        }
    }

    private fun fillHomeScreenSettings() {
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/home-screen"))
        setBox.addView(
            txt(11f, p.cDim, "show or hide workspace windows").apply {
                setPadding(0, dp(2), 0, dp(6))
            }
        )
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { fillStyleSettings(); setScroll.scrollTo(0, 0) }
        })

        fun refreshVisibility() {
            if (windowVisibilityScopeAll()) {
                for (ws in 0..4) applyTileLayout(ws)
            } else {
                applyTileLayout(curWs)
            }
        }

        fun scopeRow() {
            val allWorkspaces = windowVisibilityScopeAll()
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    val makeAll = !windowVisibilityScopeAll()
                    val edit = prefs.edit().putBoolean("home_visibility_scope_all", makeAll)
                    if (!makeAll) {
                        // Seed this workspace's window states from the existing shared
                        // values so switching scope never unexpectedly hides a pane.
                        for (id in tileIds) {
                            val key = "home_show_${id}_$curWs"
                            if (!prefs.contains(key)) {
                                edit.putBoolean(key, prefs.getBoolean("home_show_$id", true))
                            }
                        }
                    }
                    edit.apply()
                    refreshVisibility()
                    fillHomeScreenSettings()
                    setScroll.scrollTo(0, 0)
                }
            }
            r.addView(txt(14f, p.cFg, "visibility scope"), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(14f, p.cAccent, if (allWorkspaces) "all workspaces" else "this window"))
            setBox.addView(r)
        }

        fun windowRow(label: String, id: String) {
            val enabled = windowShown(curWs, id)
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    setWindowShown(curWs, id, !windowShown(curWs, id))
                    refreshVisibility()
                    fillHomeScreenSettings()
                    setScroll.scrollTo(0, 0)
                }
            }
            r.addView(txt(14f, p.cFg, label), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(14f, if (enabled) p.cGreen else p.cDim, if (enabled) "shown" else "hidden"))
            setBox.addView(r)
        }

        scopeRow()
        windowRow("terminal window", "terminal")
        windowRow("pinned apps window", "pinned")
        windowRow("bottom window (monitor / player)", "bottom")
        setBox.addView(
            txt(11f, p.cDim,
                if (windowVisibilityScopeAll()) "Changes apply to all five workspaces."
                else "Changes apply only to this window in workspace ${curWs + 1}.").apply {
                setPadding(0, dp(10), 0, dp(2))
            }
        )
    }

    private fun fillHidden() {
        val p = pal
        setBox.removeAllViews()
        setBox.addView(txt(15f, p.cBlue, "~/hidden-apps"))
        setBox.addView(
            txt(11f, p.cDim, "tap an app to hide or show it in this launcher").apply {
                setPadding(0, dp(2), 0, dp(6))
            }
        )
        setBox.addView(txt(14f, p.cAccent, "← back").apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { fillSettings(); setScroll.scrollTo(0, 0) }
        })
        allApps.forEach { app ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val name = txt(14f, p.cFg, app.label)
            val st = txt(14f, p.cGreen, "shown")
            fun paint() {
                val h = app.pkg in hiddenSet()
                name.setTextColor(if (h) p.cDim else p.cFg)
                st.text = if (h) "hidden" else "shown"
                st.setTextColor(if (h) p.cAccent else p.cGreen)
            }
            paint()
            r.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(st)
            r.setOnClickListener { toggleHidden(app); paint() }
            setBox.addView(r)
        }
    }

    private fun rebuild(reopenSettings: Boolean) {
        initHistory()
        buildUi()
        if (reopenSettings) showSettings()
    }

    // ================= hidden apps =================
    private fun hiddenSet(): Set<String> =
        (prefs.getString("hidden", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()

    private fun visibleApps(): List<App> {
        val h = hiddenSet()
        return allApps.filter { it.pkg !in h }
    }

    private fun toggleHidden(app: App) {
        val h = hiddenSet().toMutableSet()
        if (!h.remove(app.pkg)) h.add(app.pkg)
        prefs.edit().putString("hidden", h.joinToString(",")).apply()
        apps = visibleApps()
        adapter.items = apps
        adapter.notifyDataSetChanged()
        for (i in 0..4) {
            fillPins(i)
            refreshFetch(i)
        }
    }

    // ================= terminal =================
    private fun initHistory() {
        for (i in 0..4) history[i].clear()
    }

    private fun refreshOut(ws: Int) {
        outViews[ws]?.text = history[ws]
        outScrolls[ws]?.post { outScrolls[ws]?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun norm(s: String) = s.lowercase().replace(" ", "")

    private fun runCommand(ws: Int, line: String) {
        val p = pal
        val sb = history[ws]
        val fg = termFg()
        sb.add("~ ", p.cBlue).add("❯ ", p.cGreen).add("$line\n", fg)
        fun say(s: String, c: Int = fg) {
            sb.add(s + "\n", c)
        }

        if (line.isNotEmpty()) {
            val parts = line.split(Regex("\\s+"), 2)
            val cmd = parts[0].lowercase()
            val arg = parts.getOrElse(1) { "" }.trim()
            when (cmd) {
                "help" -> listOf(
                    "  help           this list",
                    "  clear          clear screen",
                    "  fetch          refresh system info",
                    "  panel <mode>   set bottom window: monitor/player",
                    "  layout <name>  set layout: dwindle/master/columns/rows",
                    "  ratio <30-70>  set primary split percentage",
                    "  swap <a> <b>  swap terminal/pinned/bottom",
                    "  swap          swap focused window with next",
                    "  focus next|prev  move focus highlight",
                    "  logo [name]    android/arch/omarchy/image/none",
                    "  ws <1-5>       switch workspace",
                    "  theme [name]   change theme",
                    "  font [name]    change font",
                    "  wall           pick wallpaper",
                    "  config         open settings",
                    "  pin <app>      pin to this workspace",
                    "  unpin <app>    unpin it",
                    "  hide <app>     hide app from launcher",
                    "  unhide <app>   show it again",
                    "  hidden         list hidden apps",
                    "  ls             list apps",
                    "  qs             quick settings panel",
                    "  vpn            open your vpn app",
                    "  <app name>     launch (fuzzy match)"
                ).forEach { say(it) }
                "clear" -> sb.clear()
                "fetch", "fastfetch", "neofetch" -> refreshFetch(ws)
                "layout" -> {
                    if (arg.isEmpty()) say("layout: ${layoutModeFor(ws)}", p.cGreen)
                    else if (arg in setOf("dwindle", "master", "columns", "rows")) {
                        setLayoutMode(ws, arg)
                        say("layout set to $arg", p.cGreen)
                    } else say("usage: layout dwindle|master|columns|rows", p.cDim)
                }
                "swap" -> {
                    val names = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val ok = if (names.size >= 2) swapTileNames(ws, names[0], names[1]) else {
                        val visible = tileOrder(ws).filter { windowShown(ws, it) }
                        val current = tileIds.getOrElse(focusedWindow[ws]) { "terminal" }
                        val i = visible.indexOf(current)
                        i >= 0 && visible.size > 1 && swapTileNames(ws, current, visible[(i + 1) % visible.size])
                    }
                    say(if (ok) "windows swapped" else "usage: swap [terminal|pinned|bottom] [terminal|pinned|bottom]", if (ok) p.cGreen else p.cDim)
                }
                "focus" -> when (arg.lowercase()) {
                    "next" -> stepFocus(ws, 1)
                    "prev", "previous" -> stepFocus(ws, -1)
                    else -> say("usage: focus next|prev", p.cDim)
                }
                "ratio" -> {
                    val value = arg.toIntOrNull()
                    if (value != null && value in 30..70) {
                        setLayoutRatio(ws, value)
                        say("layout ratio set to $value%", p.cGreen)
                    } else say("usage: ratio 30-70", p.cDim)
                }
                "panel" -> {
                    if (arg == "monitor" || arg == "player") {
                        setBottomWindowForWorkspace(ws, arg)
                        updateBottomWindowDisplay(ws, true)
                        if (ws == curWs) updateBottomWindowRuntime()
                    } else {
                        say("usage: panel monitor|player", p.cDim)
                    }
                }
                "ws" -> {
                    val n = arg.toIntOrNull()
                    if (n != null && n in 1..5) pager.setCurrentItem(n - 1, true)
                    else say("usage: ws 1-5", p.cDim)
                }
                "wall", "wallpaper" -> pickWall.launch(arrayOf("image/*"))
                "config", "settings" -> showSettings()
                "logo" -> {
                    val i = logoNames.indexOfFirst { it.startsWith(arg.lowercase()) }
                    if (arg.isEmpty() || i < 0) {
                        say("logos: " + logoNames.joinToString(", "), p.cDim)
                    } else if (logoNames[i] == "image") {
                        pickLogo.launch(arrayOf("image/*"))
                    } else {
                        prefs.edit().putInt("logo", i).apply()
                        rebuild(false)
                        return
                    }
                }
                "theme" -> {
                    val i = pals.indexOfFirst { norm(it.name).contains(norm(arg)) }
                    if (arg.isEmpty() || i < 0) {
                        say("themes: " + pals.joinToString(", ") { it.name.lowercase() }, p.cDim)
                    } else {
                        prefs.edit().putInt("theme", i).apply()
                        rebuild(false)
                        return
                    }
                }
                "font" -> {
                    val i = fontNames.indexOfFirst { it.contains(arg.lowercase()) }
                    if (arg.isEmpty() || i < 0) {
                        say("fonts: " + fontNames.joinToString(", "), p.cDim)
                    } else {
                        prefs.edit().putInt("font", i).apply()
                        rebuild(false)
                        return
                    }
                }
                "pin", "unpin" -> {
                    val a = matches(arg).firstOrNull()
                    if (arg.isEmpty() || a == null) {
                        say("usage: $cmd <app>", p.cDim)
                    } else {
                        val has = a.pkg in pins(ws)
                        if ((cmd == "pin" && !has) || (cmd == "unpin" && has)) togglePin(ws, a)
                        say(if (cmd == "pin") "pinned ${a.label}" else "unpinned ${a.label}", p.cGreen)
                    }
                }
                "hide" -> {
                    val a = matches(arg).firstOrNull()
                    if (arg.isEmpty() || a == null) {
                        say("usage: hide <app>", p.cDim)
                    } else {
                        toggleHidden(a)
                        say("hidden ${a.label}", p.cGreen)
                    }
                }
                "unhide" -> {
                    val h = hiddenSet()
                    val a = allApps.firstOrNull {
                        it.pkg in h && it.label.lowercase().contains(arg.lowercase())
                    }
                    if (arg.isEmpty() || a == null) {
                        say("usage: unhide <app>  (see: hidden)", p.cDim)
                    } else {
                        toggleHidden(a)
                        say("unhidden ${a.label}", p.cGreen)
                    }
                }
                "hidden" -> {
                    val h = hiddenSet()
                    val names = allApps.filter { it.pkg in h }.joinToString("  ") { it.label.lowercase() }
                    say(if (names.isEmpty()) "no hidden apps" else names, p.cDim)
                }
                "qs" -> openQs()
                "vpn" -> launchVpn()
                "ls" -> say(apps.joinToString("  ") { it.label.lowercase() }, p.cDim)
                else -> {
                    val a = matches(line).firstOrNull()
                    if (a != null) {
                        say("launching ${a.label}...", p.cGreen)
                        refreshOut(ws)
                        launchApp(a)
                        return
                    } else {
                        say("command not found: $line", p.cAccent)
                    }
                }
            }
        }
        if (sb.length > 8000) sb.delete(0, sb.length - 5000)
        refreshOut(ws)
    }

    // ================= apps / search =================
    private val appInstallTimes = HashMap<String, Long>()

    private fun isNewApp(pkg: String): Boolean {
        val installed = appInstallTimes[pkg] ?: return false
        return System.currentTimeMillis() - installed < NEW_APP_WINDOW_MS
    }

    private fun refreshInstalledApps() {
        val fresh = loadApps()
        if (fresh == allApps) return
        allApps = fresh
        apps = visibleApps()
        val query = search.text?.toString().orEmpty()
        adapter.items = if (query.isBlank()) apps else matches(query)
        adapter.notifyDataSetChanged()
        for (i in 0..4) fillPins(i)
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val pkg = it.activityInfo.packageName
                try {
                    appInstallTimes[pkg] = pm.getPackageInfo(pkg, 0).firstInstallTime
                } catch (_: Exception) {
                }
                App(it.loadLabel(pm).toString(), pkg)
            }
            .sortedBy { it.label.lowercase() }
    }


    private fun score(q: String, l: String): Int {
        if (q.isEmpty() || l.isEmpty()) return 0

        // Exact match, then app-name prefix.
        if (l == q) return 1000
        if (l.startsWith(q)) return 900 - l.length

        // Match the start of a word, e.g. "play" in "Google Play Store".
        val words = l.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 800 - l.length

        // Match only consecutive letters, e.g. "flac" in "SpotiFLAC".
        val idx = l.indexOf(q)
        if (idx >= 0) return 600 - idx

        // No scattered-letter or typo matches.
        return 0
    }

    // Olauncher GPL-3.0 app-label filtering, adapted to Sorairo's App model.
    private val searchSeparators = Regex("[-_+,.\\x60'\\s\\p{Z}]")
    private val searchDiacritics = Regex("\\p{InCombiningDiacriticalMarks}+")

    private fun normalizeAppSearch(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(searchDiacritics, "")
            .replace(searchSeparators, "")

    private fun matches(query: String): List<App> {
        if (query.isBlank()) return apps
        val normalizedQuery = normalizeAppSearch(query)
        return apps.filter { app ->
            val label = displayAppLabel(app)
            label.contains(query.trim(), ignoreCase = true) ||
                (normalizedQuery.isNotEmpty() &&
                    normalizeAppSearch(label).contains(normalizedQuery, ignoreCase = true))
        }
    }

    // Olauncher-style: launch a unique match once per query after filtering completes.
    private fun autoLaunchSearchResult(query: String) {
        if (query == autoLaunchedQuery) return
        autoLaunchedQuery = null
        if (query.isBlank() || query.startsWith("!") || adapter.items.size != 1 ||
            drawer.visibility != View.VISIBLE
        ) return
        val app = adapter.items.first()
        if (app.pkg.isBlank()) return
        autoLaunchedQuery = query
        launchApp(app)
    }

    private fun displayAppLabel(app: App): String = prefs.getString("app_name_${app.pkg}", null)?.takeIf { it.isNotBlank() } ?: app.label

    private fun beginDrawerAppActions(app: App, holder: RowAdapter.VH) {
        if (activeDrawerActionHolder !== holder) activeDrawerActionHolder?.hideActions()
        selectedDrawerApp = app
        activeDrawerActionHolder = holder
        holder.showActions()
    }

    private fun endDrawerAppActions() {
        selectedDrawerApp = null
        activeDrawerActionHolder?.hideActions()
        activeDrawerActionHolder = null
    }

    private fun addDrawerAction(bar: LinearLayout, label: String, glyph: String, onClick: () -> Unit) {
        bar.addView(
            txt(10f, pal.cFg, "$glyph\n$label").apply {
                gravity = Gravity.CENTER
                contentDescription = label
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(0, -1, 1f)
        )
    }

    private fun runDrawerAction(app: App, action: String) {
        when (action) {
            "Uninstall" -> {
                try {
                    val uninstall = Intent(
                        Intent.ACTION_UNINSTALL_PACKAGE,
                        Uri.parse("package:${app.pkg}")
                    ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    startActivity(uninstall)
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open uninstall screen", Toast.LENGTH_SHORT).show()
                }
                endDrawerAppActions()
            }
            "Rename" -> showRenameDialog(app)
            "Hide" -> { endDrawerAppActions(); toggleHidden(app) }
            "Info" -> {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }
                catch (_: Exception) { Toast.makeText(this, "Unable to open app info", Toast.LENGTH_SHORT).show() }
                endDrawerAppActions()
            }
            "Close" -> endDrawerAppActions()
        }
    }

    private fun showRenameDialog(app: App) {
        val p = pal
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(p.cBg, p.cAccent)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        content.addView(
            txt(15f, p.cBlue, "~/rename-app").apply {
                setPadding(0, 0, 0, dp(12))
            }
        )
        val edit = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(displayAppLabel(app))
            setSelection(text.length)
            setTextColor(p.cFg)
            setHintTextColor(p.cDim)
            typeface = font
            textSize = 15f
            background = box(p.cBg, p.cDim)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            hint = "App name"
        }
        content.addView(edit, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        fun dialogButton(label: String, color: Int, onClick: () -> Unit) {
            buttons.addView(
                txt(12f, color, label).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(10), dp(8), dp(10))
                    setOnClickListener { onClick() }
                }
            )
        }
        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialogButton("CANCEL", p.cDim) { dialog.dismiss() }
        dialogButton("SAVE", p.cAccent) {
            val name = edit.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString("app_name_${app.pkg}", name).apply()
                adapter.notifyDataSetChanged()
                endDrawerAppActions()
            }
            dialog.dismiss()
        }
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(min(dp(520), resources.displayMetrics.widthPixels - dp(48)), -2)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        edit.requestFocus()
    }

    private fun launchApp(app: App) {
        closeDrawer()
        closeQs()
        packageManager.getLaunchIntentForPackage(app.pkg)?.let { startActivity(it) }
    }

    private fun pins(ws: Int): List<String> =
        (prefs.getString("pins_$ws", "") ?: "").split(",").filter { it.isNotEmpty() }

    private fun togglePin(ws: Int, app: App): Boolean {
        val l = pins(ws).toMutableList()
        val added = !l.remove(app.pkg)
        if (added) l.add(app.pkg)
        prefs.edit().putString("pins_$ws", l.joinToString(",")).apply()
        fillPins(ws)
        adapter.notifyDataSetChanged()
        return added
    }

    // ================= drawer =================
    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE || settings.visibility == View.VISIBLE) return
        search.setText("")
        drawerOwnsIme = true
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        panel.translationY = dp(80).toFloat()
        drawer.animate().alpha(1f).setDuration(160).start()
        panel.animate().translationY(0f).setDuration(200).start()
        search.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeDrawer() {
        autoLaunchedQuery = null
        endDrawerAppActions()
        if (drawer.visibility != View.VISIBLE) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        drawer.animate().alpha(0f).setDuration(140)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var items: List<App> = emptyList()

        inner class VH(
            val row: FrameLayout,
            val name: TextView,
            val actions: LinearLayout
        ) : RecyclerView.ViewHolder(row) {
            var boundPackage: String? = null

            // Olauncher toggles visibility on the existing ViewHolder itself.
            fun showActions() {
                name.visibility = View.INVISIBLE
                actions.visibility = View.VISIBLE
            }

            fun hideActions() {
                actions.visibility = View.GONE
                name.visibility = View.VISIBLE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                layoutTransition = LayoutTransition()
            }
            val name = txt(16f, pal.cFg).apply {
                setPadding(dp(10), dp(11), dp(10), dp(11))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)
            }
            val actions = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL)
                layoutTransition = LayoutTransition()
            }
            row.addView(name)
            row.addView(actions)
            return VH(row, name, actions)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val app = items[i]
            val selected = selectedDrawerApp?.pkg == app.pkg

            if (h.boundPackage != app.pkg) {
                h.actions.removeAllViews()
                addDrawerAction(h.actions, "Uninstall", "▣") { runDrawerAction(app, "Uninstall") }
                addDrawerAction(h.actions, "Rename", "✎") { runDrawerAction(app, "Rename") }
                addDrawerAction(h.actions, "Hide", "◉") { runDrawerAction(app, "Hide") }
                addDrawerAction(h.actions, "Info", "ⓘ") { runDrawerAction(app, "Info") }
                addDrawerAction(h.actions, "Close", "×") { runDrawerAction(app, "Close") }
                h.boundPackage = app.pkg
            }

            h.name.text = displayAppLabel(app) + if (isNewApp(app.pkg)) NEW_APP_MARKER else ""
            h.name.setTextColor(if (i == 0) pal.cAccent else pal.cFg)
            h.name.setBackgroundColor(if (i == 0) pal.cSel else Color.TRANSPARENT)
            h.name.setOnClickListener { launchApp(app) }
            h.name.setOnLongClickListener {
                beginDrawerAppActions(app, h)
                true
            }

            if (selected) {
                activeDrawerActionHolder = h
                h.showActions()
            } else {
                if (activeDrawerActionHolder === h) activeDrawerActionHolder = null
                h.hideActions()
            }
        }

        override fun onViewRecycled(holder: VH) {
            if (activeDrawerActionHolder === holder) {
                activeDrawerActionHolder = null
                selectedDrawerApp = null
            }
            holder.hideActions()
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = items.size
    }

    // ================= wallpaper / status =================
    /**
     * Moves the wallpaper slightly behind workspace swipes instead of making it feel
     * completely fixed. The 8% horizontal overscan keeps edges covered while shifting.
     */
    private fun updateWallpaperParallax(progress: Float) {
        if (!::wall.isInitialized) return
        val fraction = (progress / 4f).coerceIn(0f, 1f)
        wall.translationX = ((0.5f - fraction) * dp(36)).coerceIn(-dp(18).toFloat(), dp(18).toFloat())
    }

    private fun loadWallpaper() {
        val s = prefs.getString("wall", null) ?: return
        try {
            val uri = Uri.parse(s)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / sample > 2560) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (bmp != null) wall.setImageBitmap(bmp)
        } catch (_: Exception) {
        }
    }

    private fun fmtSpeed(b: Long): String = when {
        b >= 1024 * 1024 -> "%.1f MB/s".format(b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB/s"
        else -> "$b B/s"
    }

    private fun headphones(): String? {
        return try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val types = setOf(3, 4, 7, 8, 22, 26)
            val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in types }
                ?: return null
            val n = d.productName?.toString().orEmpty()
            if (n.isEmpty() || n == Build.MODEL) "headphones" else n.take(16)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun netLabel(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    var s = "wifi"
                    try {
                        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                        val ls = wm.connectionInfo.linkSpeed
                        if (ls > 0) s += " ${ls}M"
                    } catch (_: Exception) {
                    }
                    s
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "lte"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                else -> "net"
            }
        } catch (_: Exception) {
            "net"
        }
    }

    private fun sampleMonitorNetwork(): Pair<Float, Float>? {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        if (rx < 0L || tx < 0L) {
            monitorLastT = 0L
            return null
        }

        if (monitorLastT == 0L || rx < monitorLastRx || tx < monitorLastTx) {
            monitorLastT = now
            monitorLastRx = rx
            monitorLastTx = tx
            return null
        }

        val elapsed = now - monitorLastT
        if (elapsed <= 0L) return null

        val down = ((rx - monitorLastRx) * 1000f / elapsed).coerceAtLeast(0f)
        val up = ((tx - monitorLastTx) * 1000f / elapsed).coerceAtLeast(0f)
        monitorLastT = now
        monitorLastRx = rx
        monitorLastTx = tx
        return down to up
    }

    private fun updateStatus() {
        clock.text = SimpleDateFormat("EEEE h:mm a", Locale.getDefault())
            .format(Date()).replace("AM", "am").replace("PM", "pm")

        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (lastT == 0L) {
            lastT = now; lastRx = rx; lastTx = tx
        } else if (now - lastT >= 1000 && rx >= 0 && tx >= 0) {
            val dt = now - lastT
            downBps = ((rx - lastRx) * 1000 / dt).coerceAtLeast(0)
            upBps = ((tx - lastTx) * 1000 / dt).coerceAtLeast(0)
            lastT = now; lastRx = rx; lastTx = tx
        }

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val parts = ArrayList<String>()
        headphones()?.let { parts.add("♪ $it") }
        val net = netLabel()
        parts.add(net)
        status.text = parts.joinToString("   ")
        battText.text = "▮ ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        updateNowPlaying()
    }
}



fun bar(pct: Int, width: Int = 12): String {
    val f = (pct.coerceIn(0, 100) * width + 50) / 100
    return "█".repeat(f) + "░".repeat(width - f)
}

class NetGraphHistory {
    val size = 62
    val down = FloatArray(size)
    val up = FloatArray(size)
    var count = 0
        private set
    var head = 0
        private set
    var peak = 1024f
        private set
    var lastPushT = 0L
        private set
    var lastDown = 0f
        private set
    var lastUp = 0f
        private set

    fun push(downBps: Float, upBps: Float, now: Long) {
        lastDown += 0.4f * (downBps.coerceAtLeast(0f) - lastDown)
        lastUp += 0.4f * (upBps.coerceAtLeast(0f) - lastUp)
        down[head] = lastDown
        up[head] = lastUp
        head = (head + 1) % size
        if (count < size) count++
        lastPushT = now

        var m = 0f
        for (i in 0 until count) {
            if (down[i] > m) m = down[i]
            if (up[i] > m) m = up[i]
        }
        peak = maxOf(m, 1024f, peak * 0.97f)
    }
}

class NetGraphView(
    context: Context,
    private val history: NetGraphHistory
) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val fillPath = Path()
    private var downColor = Color.WHITE

    fun setColors(grid: Int, d: Int, u: Int) {
        gridPaint.color = grid
        downPaint.color = d
        upPaint.color = u
        downColor = d
        updateShader()
        invalidate()
    }

    private fun updateShader() {
        val h = height.toFloat()
        if (h <= 0f) return
        val base = downColor and 0x00FFFFFF
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            base or (0x66 shl 24), base,
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    /** Builds a smooth curve; the right end glides from the previous sample to the newest one. */
    private fun buildPath(p: Path, data: FloatArray, w: Float, h: Float, frac: Float): Float {
        val n = history.count
        val size = history.size
        val step = w / (size - 2f)
        val start = (history.head - n + size) % size
        val peak = history.peak
        p.reset()
        var px = 0f
        var py = 0f
        var firstX = 0f
        for (k in 0 until n - 1) {
            val x = w - step * ((n - 2 - k) + frac)
            val y = h - 3f - (data[(start + k) % size] / peak) * (h - 8f)
            if (k == 0) {
                p.moveTo(x, y)
                firstX = x
            } else {
                p.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            }
            px = x
            py = y
        }
        val prev = data[(start + n - 2) % size]
        val last = data[(start + n - 1) % size]
        val ty = h - 3f - ((prev + (last - prev) * frac) / peak) * (h - 8f)
        p.quadTo(px, py, (px + w) / 2f, (py + ty) / 2f)
        p.lineTo(w, ty)
        return firstX
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) {
            val y = h * i / 4f
            c.drawLine(0f, y, w, y, gridPaint)
        }
        if (history.count >= 2) {
            val frac = ((SystemClock.elapsedRealtime() - history.lastPushT) / 1000f).coerceIn(0f, 1f)
            val firstX = buildPath(path, history.down, w, h, frac)
            fillPath.set(path)
            fillPath.lineTo(w, h)
            fillPath.lineTo(firstX, h)
            fillPath.close()
            c.drawPath(fillPath, fillPaint)
            c.drawPath(path, downPaint)
            buildPath(path, history.up, w, h, frac)
            c.drawPath(path, upPaint)
        }
        postInvalidateOnAnimation()
    }
}


/** Little animated equalizer bars shown next to the song name. */
class VisualizerView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val phases = floatArrayOf(0f, 1.3f, 2.1f, 0.7f)
    var color = Color.WHITE
    var playing = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val n = 4
        val bw = width / (n * 2f - 1f)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val h = if (playing) 0.25f + 0.75f * (0.5f + 0.5f * sin(t * (5f + i * 1.7f) + phases[i])) else 0.2f
            val bh = h * height
            val left = i * 2 * bw
            canvas.drawRoundRect(left, height - bh, left + bw, height.toFloat(), bw / 2, bw / 2, paint)
        }
        if (playing) postInvalidateOnAnimation()
    }
}

/** Vertical slider used for brightness and volume. */
class VSlider(
    c: Context,
    private val fillColor: Int,
    private val trackColor: Int,
    private val onChange: (Float) -> Unit
) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var value = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = trackColor
        canvas.drawRoundRect(rect, r, r, paint)
        canvas.save()
        canvas.clipRect(0f, height * (1f - value), width.toFloat(), height.toFloat())
        paint.color = fillColor
        canvas.drawRoundRect(rect, r, r, paint)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                value = 1f - e.y / height
                onChange(value)
            }
        }
        return true
    }
}

/** Empty listener: enabling its access lets the launcher read what music is playing. */
class NowPlayingService : NotificationListenerService()