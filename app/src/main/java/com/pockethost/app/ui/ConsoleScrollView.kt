package com.pockethost.app.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * ScrollView for the console log.
 *
 * The log is an editable terminal (EditText): every text/cursor update makes
 * it ask the parent to reveal the cursor, which would yank the viewport back
 * to the last line and make reading history impossible (scroll feels "pinned"
 * to the bottom). Child-driven scrolls are therefore suppressed — the
 * activity moves the viewport explicitly (live follow / Top / Bottom jumps),
 * while finger scroll + fling stay fully native.
 */
class ConsoleScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** When false, focus/cursor rectangle requests from the child are ignored. */
    var allowChildScroll: Boolean = false

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        if (!allowChildScroll) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}
