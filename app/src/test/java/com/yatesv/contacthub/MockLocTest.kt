package com.yatesv.contacthub

import com.yatesv.contacthub.data.Drift
import com.yatesv.contacthub.data.LatLng
import com.yatesv.contacthub.data.MockItem
import com.yatesv.contacthub.data.MockMath
import com.yatesv.contacthub.data.RoutePlan
import com.yatesv.contacthub.data.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocTest {
    private val deridder = LatLng(30.8466, -93.2891)

    @Test fun parsesPastedCoordinates() {
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("30.8466, -93.2891"))
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("30.8466 -93.2891"))
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("(30.8466,-93.2891)"))
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("https://www.google.com/maps/place/X/@30.8466,-93.2891,17z/data=abc"))
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("https://maps.google.com/?q=30.8466,-93.2891"))
        assertEquals(LatLng(30.8466, -93.2891), MockMath.parseCoords("geo:30.8466,-93.2891?z=15"))
        assertNull(MockMath.parseCoords("123 Main St"))
        assertNull(MockMath.parseCoords("95.0, 10.0"))
    }

    @Test fun measuresDistanceAndBearing() {
        val north = MockMath.offset(deridder, 1000.0, 0.0)
        assertEquals(1000.0, MockMath.meters(deridder, north), 1.0)
        assertEquals(0.0, MockMath.bearing(deridder, north), 0.5)
        val east = MockMath.offset(deridder, 0.0, 1000.0)
        assertEquals(90.0, MockMath.bearing(deridder, east), 0.5)
        assertEquals(90.0, MockMath.turnAngle(deridder, north, MockMath.offset(north, 0.0, 500.0)), 1.0)
    }

    @Test fun routeStartsAndEndsStoppedAndSlowsAtTurns() {
        val a = deridder
        val b = MockMath.offset(a, 1000.0, 0.0)
        val c = MockMath.offset(b, 0.0, 1000.0)
        val plan = RoutePlan(listOf(a, b, c), 35.0)
        assertEquals(2000.0, plan.totalMeters, 5.0)
        assertEquals(0f, plan.at(0.0).speedMps, 0.01f)
        val cruise = 35 * MockMath.MPS_PER_MPH
        // About 2 km at ~35 mph with slowdowns: a bit over 128 s
        assertTrue("took ${plan.totalSeconds}", plan.totalSeconds in 130.0..200.0)
        val straight = plan.at(plan.totalSeconds * 0.25).speedMps
        assertEquals(cruise, straight.toDouble(), 0.5)
        // Find the corner point in the timeline and check it's slow
        val cornerIdx = plan.points.indices.minByOrNull { MockMath.meters(plan.points[it], b) }!!
        assertTrue(plan.speeds[cornerIdx] < cruise * 0.4)
        val end = plan.at(plan.totalSeconds + 1)
        assertTrue(end.done)
        assertEquals(c.lat, end.lat, 1e-6); assertEquals(c.lng, end.lng, 1e-6)
    }

    @Test fun endBehaviors() {
        val a = deridder
        val b = MockMath.offset(a, 500.0, 0.0)
        val plan = RoutePlan(listOf(a, b), 20.0)
        val t = plan.totalSeconds
        assertTrue(plan.play(t + 10, "stay").done)
        assertTrue(plan.play(t + 10, "stop").done)
        val loop = plan.play(t + 1, "loop")
        assertFalse(loop.done)
        assertTrue(MockMath.meters(LatLng(loop.lat, loop.lng), a) < 20)
        val back = plan.play(t * 1.5, "pingpong")
        assertFalse(back.done)
        assertEquals(180.0, back.bearing.toDouble(), 2.0)
        assertTrue(MockMath.meters(LatLng(back.lat, back.lng), MockMath.lerp(a, b, 0.5)) < 30)
    }

    @Test fun driftStaysWithinAFewFeet() {
        val d = Drift(seed = 42)
        repeat(500) {
            val (p, acc) = d.next(deridder)
            assertTrue(MockMath.meters(p, deridder) <= 5.0)
            assertTrue(acc in 3f..8f)
        }
    }

    @Test fun itemsRoundTrip() {
        val r = MockItem(name = "Route to school", type = "route", points = listOf(deridder, MockMath.offset(deridder, 100.0, 0.0)),
            path = listOf(deridder, MockMath.offset(deridder, 50.0, 0.0), MockMath.offset(deridder, 100.0, 0.0)),
            speedMph = 12.0, endMode = "pingpong", profile = "bike", startFromReal = true)
        val back = MockItem.listFromJson(MockItem.listToJson(listOf(r))).single()
        assertEquals(r, back)
        assertEquals(r.path.last(), back.target)
    }

    @Test fun samplesEvenly() {
        val l = (0 until 1000).toList()
        val s = l.sample(100)
        assertEquals(100, s.size); assertEquals(0, s.first()); assertEquals(999, s.last())
        assertEquals(listOf(1, 2), listOf(1, 2).sample(100))
    }
}
