package com.voyagerroc.laserdeck.ptt.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesyTest {

    @Test
    fun `one degree along the equator is a times pi over 180`() {
        val g = Geodesy.inverse(0.0, 0.0, 0.0, 1.0)
        assertEquals(111319.4908, g.meters, 0.001)
        assertEquals(90.0, g.bearingDeg, 1e-9)
    }

    @Test
    fun `one degree along the meridian from the equator`() {
        // Bilinen değer: ekvatorda 1° enlem yayı 110 574,389 m.
        val g = Geodesy.inverse(0.0, 0.0, 1.0, 0.0)
        assertEquals(110574.3886, g.meters, 0.001)
        assertEquals(0.0, g.bearingDeg, 1e-9)
    }

    @Test
    fun `flinders peak to buninyong matches the published vincenty example`() {
        // Geoscience Australia'nın klasik örneği: s = 54 972,271 m, α1 = 306°52'05,37".
        val g = Geodesy.inverse(
            -(37.0 + 57.0 / 60 + 3.72030 / 3600), 144.0 + 25.0 / 60 + 29.52440 / 3600,
            -(37.0 + 39.0 / 60 + 10.15610 / 3600), 143.0 + 55.0 / 60 + 35.38390 / 3600
        )
        assertEquals(54972.271, g.meters, 0.002)
        assertEquals(306.0 + 52.0 / 60 + 5.37 / 3600, g.bearingDeg, 0.0001)
    }

    @Test
    fun `ten metres north agrees with the meridian radius of curvature`() {
        val lat = 41.0
        val dLat = 10.0 / Geodesy.metersPerDegreeLat(lat)
        val g = Geodesy.inverse(lat, 29.0, lat + dLat, 29.0)
        assertEquals(10.0, g.meters, 0.001)
        assertEquals(0.0, g.bearingDeg, 0.001)
    }

    @Test
    fun `ten metres east agrees with the parallel radius and bears ninety`() {
        val lat = 41.0
        val dLon = 10.0 / Geodesy.metersPerDegreeLon(lat)
        val g = Geodesy.inverse(lat, 29.0, lat, 29.0 + dLon)
        assertEquals(10.0, g.meters, 0.001)
        assertEquals(90.0, g.bearingDeg, 0.01)
    }

    @Test
    fun `coincident points are zero`() {
        assertEquals(0.0, Geodesy.inverse(41.0, 29.0, 41.0, 29.0).meters, 0.0)
    }

    @Test
    fun `distance is symmetric`() {
        val ab = Geodesy.inverse(41.0082, 28.9784, 41.0086, 28.9790)
        val ba = Geodesy.inverse(41.0086, 28.9790, 41.0082, 28.9784)
        assertEquals(ab.meters, ba.meters, 1e-6)
        assertEquals((ab.bearingDeg + 180.0) % 360.0, ba.bearingDeg, 0.01)
    }
}

class PositionAveragerTest {

    private val lat = 41.0082
    private val lon = 28.9784

    @Test
    fun `too few fixes give no result`() {
        val avg = PositionAverager()
        repeat(3) { avg.add(lat, lon, 4f) }
        assertNull(avg.result(minFixes = 4))
        avg.add(lat, lon, 4f)
        assertNotNull(avg.result(minFixes = 4))
    }

    @Test
    fun `identical fixes keep the position and cap the gain at n_eff`() {
        val avg = PositionAverager()
        repeat(8) { avg.add(lat, lon, 4f) }
        val r = avg.result(4)!!
        assertEquals(lat, r.lat, 1e-9)
        assertEquals(lon, r.lon, 1e-9)
        assertEquals(0f, r.scatterM, 1e-6f)
        // 8 örnek √8 değil, en fazla √3 kazandırır.
        assertEquals(4f / Math.sqrt(3.0).toFloat(), r.sigmaM, 1e-4f)
    }

    @Test
    fun `symmetric scatter lands in the middle and widens sigma`() {
        val dLon = 5.0 / Geodesy.metersPerDegreeLon(lat) // 5 m doğu
        val avg = PositionAverager()
        repeat(4) {
            avg.add(lat, lon - dLon, 3f)
            avg.add(lat, lon + dLon, 3f)
        }
        val r = avg.result(4)!!
        assertEquals(lon, r.lon, 1e-10)
        assertEquals(5f, r.scatterM, 0.01f)
        // Saçılım (5 m) bildirilen doğruluktan (3/√3 ≈ 1,7 m) büyük: σ saçılımı izler.
        assertEquals(5f, r.sigmaM, 0.01f)
    }

    @Test
    fun `a wild fix with poor accuracy barely moves the mean`() {
        val dLat = 50.0 / Geodesy.metersPerDegreeLat(lat) // 50 m kuzeyde sapkın fix
        val avg = PositionAverager()
        repeat(7) { avg.add(lat, lon, 3f) }
        avg.add(lat + dLat, lon, 60f)
        val r = avg.result(4)!!
        val shift = Geodesy.inverse(lat, lon, r.lat, r.lon).meters
        // Ağırlık 1/σ²: 60 m'lik fix, 3 m'liklerin 400'de biri kadar sayılır.
        assertTrue("sapkın fix ortalamayı $shift m kaydırdı", shift < 0.1)
    }

    @Test
    fun `invalid accuracies are ignored`() {
        val avg = PositionAverager()
        avg.add(lat, lon, 0f)
        avg.add(lat, lon, -1f)
        avg.add(lat, lon, Float.NaN)
        assertEquals(0, avg.count)
    }
}
