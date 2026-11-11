package ai.muse.gadgeteverywhere

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic, single-shot screen control for the Muse gadget.
 *
 * This is a primitive layer only: tap / swipe / type / read / Back / Home /
 * Recents. It never decides *what* to tap, never answers anything, and never
 * runs on its own — every action is one explicit remote command from Muse,
 * and Muse decides from a fresh `screen.read` or `screen.screenshot` first.
 *
 * Safety guards (see also docs/SCREEN_CONTROL.md):
 * - The service must be enabled by the user in Settings > Accessibility.
 *   Until then every command fails with instructions, never silently.
 * - Tap/type are REFUSED on a sensitive screen: any visible node that is a
 *   focused/editable password field. Password text is redacted in
 *   `screen.read` (reported as "[password]").
 * - No quiz logic, no answer bank, no auto-tapping, no screen recording,
 *   no notification listener, no root. Scenes/automations cannot contain
 *   screen-control commands (enforced in the Python runtime).
 */
class ScreenControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenControlAccessibilityService? = null
            private set

        const val ENABLE_INSTRUCTIONS =
            "Accessibility service is not enabled; on this phone open " +
                "Settings > Accessibility > Downloaded apps > " +
                "Muse Gadget Everywhere and turn it On, then retry. " +
                "(On OPPO/ColorOS it may be under Settings > Additional " +
                "settings > Accessibility.)"

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val manager =
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_GENERIC,
                ).any {
                    it.resolveInfo.serviceInfo.name ==
                        ScreenControlAccessibilityService::class.java.name
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No background watching: the service only acts when a remote
        // command asks it to, and reads the live window tree at that moment.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Foreground package of the active window, or null when there is none. */
    fun foregroundPackage(): String? {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        try {
            return root.packageName?.toString()
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    /**
     * True when the current screen holds a focused/editable password field.
     * Tap and type are refused on such a screen; read redacts password text.
     */
    fun isSensitiveScreen(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        return try {
            var sensitive = false
            walk(root, 200) { node ->
                if (!sensitive && node.isPassword && (node.isFocused || node.isEditable)) {
                    sensitive = true
                }
            }
            sensitive
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    fun requireNotSensitive(action: String) {
        if (isSensitiveScreen()) {
            throw IllegalStateException(
                "refused: $action is not allowed on a password / login screen",
            )
        }
    }

    /** Tap one screen point. Fire-and-forget: returns "dispatched", like tv.launch. */
    fun tapAt(x: Int, y: Int): JSONObject {
        requireNotSensitive("screen.tap")
        require(x in 0..20000 && y in 0..20000) { "x and y must be 0..20000" }
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: throw IllegalStateException("no active window; open an app first")
        try { root.recycle() } catch (_: Exception) { }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        // dispatchGesture reports only acceptance here; Muse verifies the
        // result with a fresh screen.read / screen.screenshot.
        if (!dispatchGesture(gesture, null, null)) {
            throw IllegalStateException("tap was not accepted by the system")
        }
        return JSONObject().put("status", "dispatched").put("x", x).put("y", y)
    }

    /** Tap the node at [index] from the most recent screen.read listing. */
    fun tapIndex(index: Int): JSONObject {
        requireNotSensitive("screen.tap")
        val nodes = collectNodes(200)
        if (index < 0 || index >= nodes.size) {
            throw IllegalArgumentException(
                "node_index $index is out of range; screen.read listed ${nodes.size} nodes",
            )
        }
        return tapEntry(nodes[index], index)
    }

    /** Tap the first node whose text matches [text] exactly (then contains). */
    fun tapText(text: String): JSONObject {
        requireNotSensitive("screen.tap_text")
        require(text.isNotBlank() && text.length <= 200) { "text must be 1..200 characters" }
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: throw IllegalStateException("no active window; open an app first")
        try {
            val candidates = try {
                root.findAccessibilityNodeInfosByText(text) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            try {
                if (candidates.isEmpty()) {
                    throw IllegalStateException("no on-screen text matches \"$text\"")
                }
                // Exact match first, then case-insensitive exact, then contains.
                val ordered = candidates.sortedBy { node ->
                    val t = node.text?.toString() ?: ""
                    when {
                        t == text -> 0
                        t.equals(text, ignoreCase = true) -> 1
                        else -> 2
                    }
                }
                for (node in ordered) {
                    if (clickNodeOrAncestor(node)) {
                        return JSONObject().put("status", "tapped")
                            .put("text", node.text?.toString()?.take(200))
                    }
                }
                // Nothing clickable: tap the centre of the first match instead.
                val bounds = Rect()
                ordered.first().getBoundsInScreen(bounds)
                return tapAt(bounds.centerX(), bounds.centerY())
            } finally {
                for (node in candidates) {
                    try { node.recycle() } catch (_: Exception) { }
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    /** Swipe from (x1,y1) to (x2,y2). Fire-and-forget, like tapAt. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject {
        requireNotSensitive("screen.swipe")
        for (v in listOf(x1, y1, x2, y2)) require(v in 0..20000) { "coordinates must be 0..20000" }
        require(durationMs in 50..5000) { "duration_ms must be 50..5000" }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        if (!dispatchGesture(gesture, null, null)) {
            throw IllegalStateException("swipe was not accepted by the system")
        }
        return JSONObject().put("status", "dispatched")
            .put("from", JSONArray(listOf(x1, y1))).put("to", JSONArray(listOf(x2, y2)))
    }

    /**
     * Type [text] into the focused field, or into the node at [nodeIndex].
     * Refused on password fields / login screens (see requireNotSensitive).
     */
    fun typeText(text: String, nodeIndex: Int?): JSONObject {
        requireNotSensitive("screen.type")
        require(text.isNotBlank() && text.length <= 1000) { "text must be 1..1000 characters" }
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: throw IllegalStateException("no active window; open an app first")
        try {
            val target: AccessibilityNodeInfo?
            val owned = ArrayList<AccessibilityNodeInfo>()
            var focusNode: AccessibilityNodeInfo? = null
            try {
                target = if (nodeIndex != null) {
                    val entries = collectNodes(200)
                    if (nodeIndex < 0 || nodeIndex >= entries.size) {
                        throw IllegalArgumentException(
                            "node_index $nodeIndex is out of range; screen.read listed ${entries.size} nodes",
                        )
                    }
                    // Same traversal order as collectNodes, matched on bounds.
                    owned.addAll(liveNodes(root))
                    owned.firstOrNull { node -> boundsOf(node) == boundsOf(entries[nodeIndex]) }
                } else {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT).also { focusNode = it }
                }
                val node = target
                    ?: throw IllegalStateException(
                        "no focused text field; tap the field first, then send screen.type",
                    )
                if (node.isPassword) {
                    throw IllegalStateException("refused: screen.type is not allowed in a password field")
                }
                if (!node.isEditable) {
                    throw IllegalStateException("the focused node is not an editable text field")
                }
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text,
                    )
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    throw IllegalStateException("the field did not accept the text")
                }
                return JSONObject().put("status", "typed").put("length", text.length)
            } finally {
                for (node in owned) {
                    // liveNodes includes root; root is recycled just below.
                    if (node !== root) {
                        try { node.recycle() } catch (_: Exception) { }
                    }
                }
                // findFocus can in principle return the root itself.
                if (focusNode !== null && focusNode !== root) {
                    try { focusNode.recycle() } catch (_: Exception) { }
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    fun pressBack(): JSONObject = globalAction(
        AccessibilityService.GLOBAL_ACTION_BACK, "back",
    )

    fun pressHome(): JSONObject = globalAction(
        AccessibilityService.GLOBAL_ACTION_HOME, "home",
    )

    fun openRecents(): JSONObject = globalAction(
        AccessibilityService.GLOBAL_ACTION_RECENTS, "recents",
    )

    private fun globalAction(action: Int, name: String): JSONObject {
        if (!performGlobalAction(action)) {
            throw IllegalStateException("system did not accept $name")
        }
        return JSONObject().put("status", "dispatched").put("action", name)
    }

    /**
     * List visible on-screen nodes, in traversal order, with the index
     * screen.tap / screen.type use. Password text is redacted.
     */
    fun readScreen(maxNodes: Int): JSONObject {
        val limit = maxNodes.coerceIn(1, 200)
        // Read one extra: truncated is true only when a node was actually cut.
        val all = collectNodes(limit + 1)
        val nodes = all.take(limit)
        val array = JSONArray()
        for ((index, entry) in nodes.withIndex()) {
            array.put(
                JSONObject()
                    .put("index", index)
                    .put("text", entry.text)
                    .put("content_description", entry.description)
                    .put("class", entry.className)
                    .put("package", entry.packageName)
                    .put("bounds", JSONArray(listOf(entry.left, entry.top, entry.right, entry.bottom)))
                    .put("center", JSONArray(listOf(entry.centerX, entry.centerY)))
                    .put("clickable", entry.clickable)
                    .put("editable", entry.editable)
                    .put("password", entry.password)
                    .put("focused", entry.focused),
            )
        }
        return JSONObject()
            .put("package", foregroundPackage())
            .put("count", nodes.size)
            .put("truncated", all.size > limit)
            .put("nodes", array)
    }

    // ---- internals ----

    private data class NodeEntry(
        val text: String?,
        val description: String?,
        val className: String?,
        val packageName: String?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val centerX: Int,
        val centerY: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val focused: Boolean,
    )

    /** Depth-first walk of [root], calling [visit] for each node (max [limit]). */
    private fun walk(
        root: AccessibilityNodeInfo,
        limit: Int,
        visit: (AccessibilityNodeInfo) -> Unit,
        stopWhen: (() -> Boolean)? = null,
    ) {
        var seen = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && seen < limit) {
            if (stopWhen?.invoke() == true) break
            val node = stack.removeLast()
            seen++
            visit(node)
            for (i in node.childCount - 1 downTo 0) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Exception) { }
            }
            if (node !== root) {
                try { node.recycle() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Flatten the live window tree into indexed entries. A node is listed
     * when it has text / a content description, is clickable, or is an
     * editable field — pure layout containers are skipped.
     */
    private fun collectNodes(maxNodes: Int): List<NodeEntry> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: throw IllegalStateException("no active window; open an app first")
        val out = ArrayList<NodeEntry>()
        try {
            walk(root, 2000, stopWhen = { out.size >= maxNodes }) { node ->
                val rawText = node.text?.toString()?.take(120)
                val rawDesc = node.contentDescription?.toString()?.take(120)
                val password = node.isPassword
                if (rawText.isNullOrBlank() && rawDesc.isNullOrBlank() &&
                    !node.isClickable && !node.isEditable
                ) {
                    return@walk
                }
                val bounds = Rect()
                try { node.getBoundsInScreen(bounds) } catch (_: Exception) { }
                out.add(
                    NodeEntry(
                        text = if (password) "[password]" else rawText,
                        description = if (password) "[password]" else rawDesc,
                        className = node.className?.toString(),
                        packageName = node.packageName?.toString(),
                        left = bounds.left, top = bounds.top,
                        right = bounds.right, bottom = bounds.bottom,
                        centerX = bounds.centerX(), centerY = bounds.centerY(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        password = password,
                        focused = node.isFocused,
                    ),
                )
            }
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
        return out
    }

    /** Tap [entry]: click it (or its nearest clickable ancestor), else centre-tap. */
    private fun tapEntry(entry: NodeEntry, index: Int): JSONObject {
        requireNotSensitive("screen.tap")
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: throw IllegalStateException("no active window; open an app first")
        val live = liveNodes(root)
        try {
            // liveNodes and collectNodes share traversal order; match on bounds.
            val match = live.firstOrNull { node -> boundsOf(node) == boundsOf(entry) }
                ?: return tapAt(entry.centerX, entry.centerY)
            if (clickNodeOrAncestor(match)) {
                return JSONObject().put("status", "tapped").put("index", index)
            }
            return tapAt(entry.centerX, entry.centerY)
        } finally {
            for (node in live) {
                // liveNodes includes root; root is recycled just below.
                if (node !== root) {
                    try { node.recycle() } catch (_: Exception) { }
                }
            }
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    /** Click [node] or walk up to the nearest clickable ancestor. */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        // Nodes from getParent() are owned here and must be recycled; the
        // input node itself belongs to the caller and is left alone.
        val owned = ArrayList<AccessibilityNodeInfo>()
        try {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    return try { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                }
                val parent = try { current.parent } catch (_: Exception) { null }
                if (parent != null) owned.add(parent)
                current = parent
            }
            return false
        } finally {
            for (n in owned) {
                try { n.recycle() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Depth-first list of the live nodes under [root], in the same order
     * collectNodes visits them. Unlike walk(), nothing is recycled here:
     * the caller owns the list and must recycle every node when done.
     */
    private fun liveNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < 2000) {
            val node = stack.removeLast()
            out.add(node)
            for (i in node.childCount - 1 downTo 0) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Exception) { }
            }
        }
        return out
    }

    private fun boundsOf(node: AccessibilityNodeInfo): List<Int> {
        val bounds = Rect()
        try { node.getBoundsInScreen(bounds) } catch (_: Exception) { }
        return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun boundsOf(entry: NodeEntry): List<Int> =
        listOf(entry.left, entry.top, entry.right, entry.bottom)
}
