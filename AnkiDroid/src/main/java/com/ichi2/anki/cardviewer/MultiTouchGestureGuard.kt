// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.view.MotionEvent

/**
 * Prevents the legacy study screen's gesture detector from misreading a
 * pinch-to-zoom as a tap, swipe or double-tap.
 *
 * The WebView consumes the pinch itself (built-in zoom controls), but the
 * activity's overlay gesture listener sees the same raw stream, and the
 * gesture detector may still fire callbacks for it — e.g. a pinch where both
 * fingers lift near their start looks like a [android.view.GestureDetector]
 * "tap", which would answer the card mid-zoom.
 *
 * The guard tracks the multi-touch cycle: from the second finger down until
 * shortly after the last finger lifts, [shouldSuppressGesture] returns true.
 * One extra suppressed cycle after the fingers lift swallows the callback
 * synthesized from the pinch's up event.
 */
class MultiTouchGestureGuard {
    /** Whether the current touch cycle has seen more than one pointer. */
    private var isMultiTouchCycle = false

    /**
     * Whether one gesture callback should be discarded after the multi-touch
     * cycle ends: the gesture detector can emit a stale tap/fling for the pinch.
     */
    private var discardNextGesture = false

    /** Feeds one raw touch event of the gesture stream. */
    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> isMultiTouchCycle = true
            MotionEvent.ACTION_DOWN ->
                // a genuinely fresh single-finger cycle starts here; a DOWN
                // reaching us while armed comes from the pinch's own stream
                if (!isMultiTouchCycle) discardNextGesture = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isMultiTouchCycle) {
                    isMultiTouchCycle = false
                    discardNextGesture = true
                }
            }
        }
    }

    /**
     * Whether the caller should suppress the gesture callback it is about to
     * process. Must be consulted from each gesture-detector callback (single
     * tap confirmed, double tap, fling).
     */
    fun shouldSuppressGesture(): Boolean {
        if (isMultiTouchCycle) return true
        if (discardNextGesture) {
            discardNextGesture = false
            return true
        }
        return false
    }
}
