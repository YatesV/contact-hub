package com.yatesv.contacthub

import com.yatesv.contacthub.ui.foldFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fold geometry: settled screens are flat and sharp; folding-away screens tilt, shrink and blur. */
class FoldTransitionTest {
    @Test fun settledScreenIsFlatSharpOpaque() {
        for (fwd in listOf(true, false)) for (ent in listOf(true, false)) {
            val f = foldFrame(p = 1f, forward = fwd, entering = ent, intensity = 0.5f)
            assertEquals(0f, f.rotationY, 0.001f)
            assertEquals(1f, f.scale, 0.001f)
            assertEquals(0f, f.blur, 0.001f)
            assertEquals(1f, f.alpha, 0.001f)
        }
    }

    @Test fun foldedAwayScreenTiltsShrinksBlursAndDims() {
        val f = foldFrame(p = 0f, forward = true, entering = false, intensity = 1f)
        assertTrue("tilted", kotlin.math.abs(f.rotationY) > 1f)
        assertTrue("shrunk", f.scale < 1f)
        assertTrue("blurred", f.blur > 1f)
        assertTrue("dimmed", f.alpha < 1f)
    }

    @Test fun enteringAndLeavingTiltOppositeWays() {
        val entering = foldFrame(p = 0.3f, forward = true, entering = true, intensity = 0.6f)
        val leaving = foldFrame(p = 0.3f, forward = true, entering = false, intensity = 0.6f)
        assertTrue("opposite directions", entering.rotationY * leaving.rotationY < 0f)
    }

    @Test fun intensityScalesTheEffect() {
        val subtle = foldFrame(p = 0f, forward = true, entering = true, intensity = 0f)
        val dramatic = foldFrame(p = 0f, forward = true, entering = true, intensity = 1f)
        assertTrue(kotlin.math.abs(dramatic.rotationY) > kotlin.math.abs(subtle.rotationY))
        assertTrue(dramatic.blur > subtle.blur)
        assertTrue(dramatic.scale < subtle.scale)
    }

    @Test fun progressIsClamped() {
        // Values outside 0..1 don't blow up
        assertEquals(1f, foldFrame(p = 2f, forward = true, entering = true, intensity = 0.5f).scale, 0.001f)
        assertTrue(foldFrame(p = -1f, forward = true, entering = true, intensity = 0.5f).blur > 0f)
    }
}
