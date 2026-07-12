package com.noop.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.noop.NoopApplication

/**
 * Reads visible text from the official WHOOP app (com.whoop.android) and extracts
 * Recovery % / Day Strain 0–21 for automatic model labels.
 *
 * User must enable: Settings → Accessibility → NOOP WHOOP app capture.
 * Opt-out: NOOP Settings → Auto-capture WHOOP app scores OFF.
 */
class WhoopAppAccessibilityService : AccessibilityService() {

    private var lastScanMs = 0L

    override fun onServiceConnected() {
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 400
            packageNames = arrayOf(WhoopAppAutoCapture.WHOOP_PACKAGE)
        } ?: AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(WhoopAppAutoCapture.WHOOP_PACKAGE)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!WhoopAppAutoCapture.isEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WhoopAppAutoCapture.WHOOP_PACKAGE) return
        val now = System.currentTimeMillis()
        if (now - lastScanMs < 2_500L) return
        lastScanMs = now

        val root = rootInActiveWindow ?: return
        val buf = StringBuilder(8_192)
        collectText(root, buf, 0)
        root.recycle()
        val text = buf.toString()
        if (text.length < 8) return
        val parsed = WhoopAppScoreParser.parseScreenText(text)
        if (parsed.recoveryPct == null && parsed.dayStrain021 == null) return
        val repo = (application as? NoopApplication)?.repository
        WhoopAppAutoCapture.ingestParsed(this, parsed, source = "accessibility", repository = repo)
    }

    override fun onInterrupt() {}

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectText(c, out, depth + 1)
            c.recycle()
        }
    }
}
