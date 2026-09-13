// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.view.MotionEvent
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [MultiTouchGestureGuard]: pinch-to-zoom must not be misread as
 * tap/swipe/double-tap gestures on the legacy study screen.
 *
 * The guard observes the raw touch stream fed to the gesture detector:
 * - a second finger down (multi-touch) arms the guard;
 * - until the last finger lifts, all single-finger gesture callbacks are
 *   suppressed;
 * - when the last finger lifts, one further "stale" cycle is discarded to
 *   swallow the tap the gesture detector may have synthesized from the pinch.
 */
@RunWith(RobolectricTestRunner::class)
class MultiTouchGestureGuardTest {
    private val guard = MultiTouchGestureGuard()

    private fun motionEvent(
        action: Int,
        pointerCount: Int = 1,
    ): MotionEvent =
        MotionEvent.obtain(
            // downTime =
            0L,
            // eventTime =
            0L,
            // action =
            action,
            // x =
            0f,
            // y =
            0f,
            // pressure =
            1f,
            // size =
            1f,
            // metaState =
            0,
            // xPrecision =
            1f,
            // yPrecision =
            1f,
            // deviceId =
            0,
            // edgeFlags =
            0,
        )

    @Test
    fun `single finger gestures are unaffected when never multitouched`() {
        assertThat(guard.shouldSuppressGesture(), equalTo(false))
    }

    @Test
    fun `second finger down suppresses gestures`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        assertThat(guard.shouldSuppressGesture(), equalTo(true))
    }

    @Test
    fun `suppression continues while two pointers remain`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_MOVE))
        assertThat(guard.shouldSuppressGesture(), equalTo(true))
    }

    @Test
    fun `pointer lift does not re-enable gestures while a finger remains`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_UP))
        // still in a multi-touch cycle until all fingers lift
        assertThat(guard.shouldSuppressGesture(), equalTo(true))
    }

    @Test
    fun `all fingers lifted discards one stale cycle then re-enables`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        // the gesture detector may still emit a synthesized tap for the pinch
        assertThat(guard.shouldSuppressGesture(), equalTo(true))

        // after consuming the stale cycle, gestures work again
        assertThat(guard.shouldSuppressGesture(), equalTo(false))
    }

    @Test
    fun `cancel event discards one stale cycle then re-enables`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_CANCEL))
        assertThat(guard.shouldSuppressGesture(), equalTo(true))
        assertThat(guard.shouldSuppressGesture(), equalTo(false))
    }

    @Test
    fun `down after pinch starts a fresh cycle without suppression`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        assertThat(guard.shouldSuppressGesture(), equalTo(true)) // stale
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        assertThat(guard.shouldSuppressGesture(), equalTo(false))
    }

    @Test
    fun `second pinch re-arms the guard`() {
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        assertThat(guard.shouldSuppressGesture(), equalTo(true)) // stale
        assertThat(guard.shouldSuppressGesture(), equalTo(false))

        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_POINTER_DOWN))
        assertThat(guard.shouldSuppressGesture(), equalTo(true))
        guard.onTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        assertThat(guard.shouldSuppressGesture(), equalTo(true)) // stale
        assertThat(guard.shouldSuppressGesture(), equalTo(false))
    }
}
