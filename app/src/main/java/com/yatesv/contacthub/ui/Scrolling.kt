package com.yatesv.contacthub.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A fling behavior that makes lists scroll faster: the flick's velocity is multiplied by [factor],
 * so one flick carries further. Finger-tracking stays 1:1; only the momentum changes.
 * factor 1 = normal; Orbit defaults a little above 1.
 */
@Composable
fun rememberScrollFling(factor: Float): FlingBehavior {
    val base = ScrollableDefaults.flingBehavior()
    return remember(base, factor) {
        if (factor <= 1.01f) base
        else object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                with(base) { performFling(initialVelocity * factor) }
        }
    }
}
