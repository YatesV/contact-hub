package com.yatesv.contacthub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Faster-than-your-finger scrolling.
 *
 * With [factor] > 1 the page moves further than your finger does: drag 1 cm and the list moves
 * factor × 1 cm. Flings get the same boost, so a flick also carries further.
 *
 * How: the list's own touch scrolling is switched off ([native] = false → pass as
 * `userScrollEnabled`) and [modifier] adds our own scrollable that feeds every drag/fling delta
 * into the list multiplied by [factor]. factor ≤ 1.01 leaves the normal 1:1 list untouched.
 *
 * Usage:
 *   val sp = rememberSpeedScroll(settings.scrollSpeed)
 *   LazyColumn(Modifier.fillMaxSize().then(sp.modifier), state = sp.state, userScrollEnabled = sp.native) { … }
 */
class SpeedScroll(val state: LazyListState, val modifier: Modifier, val native: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberSpeedScroll(
    factor: Float,
    reverseLayout: Boolean = false,
    state: LazyListState = rememberLazyListState(),
): SpeedScroll {
    val f = factor.coerceIn(1f, 4f)
    // rememberScrollableState keeps the latest lambda, so slider changes apply instantly.
    val boosted = rememberScrollableState { delta ->
        // Report consumption back in finger space so nested scrolling / edges stay correct.
        state.dispatchRawDelta(delta * f) / f
    }
    val fling = ScrollableDefaults.flingBehavior()
    val overscroll = ScrollableDefaults.overscrollEffect()
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, reverseLayout)
    if (f <= 1.01f) return SpeedScroll(state, Modifier, native = true)
    val mod = Modifier
        .overscroll(overscroll)
        .scrollable(
            state = boosted,
            orientation = Orientation.Vertical,
            overscrollEffect = overscroll,
            reverseDirection = reverse,
            flingBehavior = fling,
        )
    return SpeedScroll(state, mod, native = false)
}
