package com.ft8ok.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Which finger the tab swipe must ignore. A drag that starts on a
 * slider belongs to the slider: on the phone the Roster window slider lost its drags
 * to the swipe, so leaving it to gesture order was not enough.
 */
class TabSwipeGate {
    /** The pointer that went down in an exempt area. Ids are never reused, so a stale one matches nothing. */
    var exempt: PointerId? = null
}

val LocalTabSwipeGate = staticCompositionLocalOf { TabSwipeGate() }

/**
 * A drag that starts here never changes tab. The press is noted on the Initial pass,
 * before anything else sees it, and nothing is consumed, so the controls underneath
 * behave exactly as they would with no swipe at all.
 */
fun Modifier.noTabSwipe(): Modifier = composed {
    val gate = LocalTabSwipeGate.current
    pointerInput(gate) {
        awaitEachGesture {
            gate.exempt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).id
        }
    }
}
