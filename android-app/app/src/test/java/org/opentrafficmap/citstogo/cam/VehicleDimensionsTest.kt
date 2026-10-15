package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.packet.Tshark
import org.opentrafficmap.citstogo.srem.SremProfile

class VehicleDimensionsTest {
    @Test
    fun defaultsFollowIssue11AndLeaveOutVulnerableRoadUsers() {
        assertEquals(VehicleDimensions(45, 18, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.PASSENGER_CAR))
        assertEquals(VehicleDimensions(120, 26, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.PUBLIC_TRANSPORT_BUS))
        assertEquals(VehicleDimensions(20, 8, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.MOTORCYCLE))
        for (profile in listOf(SremProfile.PEDESTRIAN, SremProfile.BICYCLE)) {
            assertFalse(VehicleDimensions.applies(profile))
            assertEquals(VehicleDimensions.UNAVAILABLE, VehicleDimensions.defaults(profile))
        }
        SremProfile.entries.filter(VehicleDimensions::applies).forEach { profile ->
            val defaults = VehicleDimensions.defaults(profile)
            assertTrue("$profile", defaults.lengthDm != null && defaults.widthDm != null)
        }
    }

    @Test
    fun valuesUseTheCddRangesAndTheirUnavailableCodes() {
        val unavailable = VehicleDimensions.UNAVAILABLE
        assertEquals(1_023, unavailable.lengthValue)
        assertEquals(4, unavailable.lengthConfidenceIndication)
        assertEquals(62, unavailable.widthValue)

        val roadTrain = VehicleDimensions(1_500, 80, VehicleDimensions.Trailer.KNOWN_LENGTH)
        assertEquals(1_022, roadTrain.lengthValue)
        assertEquals(1, roadTrain.lengthConfidenceIndication)
        assertEquals(61, roadTrain.widthValue)

        assertEquals(2, VehicleDimensions(165, 26, VehicleDimensions.Trailer.UNKNOWN_LENGTH).lengthConfidenceIndication)
    }

    @Test
    fun withoutALengthEveryTrailerIsSentWithAnUnknownLength() {
        val unknownLength = VehicleDimensions(null, 25, VehicleDimensions.Trailer.UNKNOWN_LENGTH)
        assertEquals(1_023, unknownLength.lengthValue)
        assertEquals(2, unknownLength.lengthConfidenceIndication)

        // A trailer set as included in a length that is not given still goes out, with its length unknown.
        val knownLength = VehicleDimensions(null, 25, VehicleDimensions.Trailer.KNOWN_LENGTH)
        assertEquals(1_023, knownLength.lengthValue)
        assertEquals(2, knownLength.lengthConfidenceIndication)

        val noTrailer = VehicleDimensions(null, 25, VehicleDimensions.Trailer.NONE)
        assertEquals(1_023, noTrailer.lengthValue)
        assertEquals(4, noTrailer.lengthConfidenceIndication)
    }

    @Test
    fun trailerIsReportedWhenTheLengthIndicationSaysSo() {
        assertTrue(VehicleDimensions(165, 26, VehicleDimensions.Trailer.KNOWN_LENGTH).reportsTrailer)
        assertTrue(VehicleDimensions(165, null, VehicleDimensions.Trailer.UNKNOWN_LENGTH).reportsTrailer)
        assertTrue(VehicleDimensions(null, 26, VehicleDimensions.Trailer.UNKNOWN_LENGTH).reportsTrailer)
        assertFalse(VehicleDimensions(165, 26, VehicleDimensions.Trailer.NONE).reportsTrailer)
        assertTrue(VehicleDimensions(null, 26, VehicleDimensions.Trailer.KNOWN_LENGTH).reportsTrailer)
        assertFalse(VehicleDimensions.UNAVAILABLE.reportsTrailer)
        SremProfile.entries.forEach { assertFalse("$it", VehicleDimensions.defaults(it).reportsTrailer) }
        // The rule behind the map's own trailer: the indication that the CAMs carry says a trailer is present.
        for (trailer in VehicleDimensions.Trailer.entries) {
            for (size in listOf(VehicleDimensions(45, 18, trailer), VehicleDimensions(null, null, trailer))) {
                assertEquals("$size", size.lengthConfidenceIndication in 1..2, size.reportsTrailer)
            }
        }
    }

    @Test
    fun aCyclistHasOnlyATrailerSettingAndAPedestrianNone() {
        assertFalse(VehicleDimensions.hasTrailerSetting(SremProfile.PEDESTRIAN))
        assertTrue(VehicleDimensions.hasTrailerSetting(SremProfile.BICYCLE))
        assertFalse(VehicleDimensions.applies(SremProfile.BICYCLE))
        SremProfile.entries.filter(VehicleDimensions::applies).forEach {
            assertTrue("$it", VehicleDimensions.hasTrailerSetting(it))
        }

        val withTrailer = VehicleDimensions.UNAVAILABLE.copy(trailer = VehicleDimensions.Trailer.UNKNOWN_LENGTH)
        val bicycle = VehicleDimensions.of(SremProfile.BICYCLE, mapOf(SremProfile.BICYCLE to withTrailer))
        assertEquals(withTrailer, bicycle)
        assertEquals(1_023, bicycle.lengthValue)
        assertEquals(2, bicycle.lengthConfidenceIndication)
        assertEquals(62, bicycle.widthValue)
        assertTrue(bicycle.reportsTrailer)

        // Neither of them sends a size, whatever is stored.
        val stored = SremProfile.entries.associateWith {
            VehicleDimensions(30, 10, VehicleDimensions.Trailer.UNKNOWN_LENGTH)
        }
        assertEquals(withTrailer, VehicleDimensions.of(SremProfile.BICYCLE, stored))
        assertEquals(VehicleDimensions.UNAVAILABLE, VehicleDimensions.of(SremProfile.PEDESTRIAN, stored))
        assertEquals(stored[SremProfile.PASSENGER_CAR], VehicleDimensions.of(SremProfile.PASSENGER_CAR, stored))
        SremProfile.entries.forEach {
            assertEquals("$it", VehicleDimensions.defaults(it), VehicleDimensions.of(it, emptyMap()))
        }
    }

    @Test
    fun settingsFieldsReadMetresWithPointOrComma() {
        assertEquals(45, VehicleDimensions.parseMeters("4.5", VehicleDimensions.MAX_LENGTH_DM))
        assertEquals(26, VehicleDimensions.parseMeters(" 2,55 ", VehicleDimensions.MAX_WIDTH_DM))
        assertEquals(1, VehicleDimensions.parseMeters("0.01", VehicleDimensions.MAX_WIDTH_DM))
        assertEquals(61, VehicleDimensions.parseMeters("7", VehicleDimensions.MAX_WIDTH_DM))
        for (text in listOf("", " ", "0", "-3", "long", "NaN", "Infinity")) {
            assertNull("'$text'", VehicleDimensions.parseMeters(text, VehicleDimensions.MAX_LENGTH_DM))
        }
        assertEquals("4.5", VehicleDimensions.formatMeters(45))
        assertEquals("12.0", VehicleDimensions.formatMeters(120))
        assertEquals("", VehicleDimensions.formatMeters(null))
    }

    @Test
    fun camCarriesTheSizeAsTsharkReadsIt() {
        val identity = CamIdentity(stationId = 0x0102_0304, macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5))
        val position = CamPosition(
            latitude = 482_024_036,
            longitude = 163_691_773,
            semiMajorConfidenceCm = 50,
            semiMinorConfidenceCm = 40,
            semiMajorOrientation = 900,
            altitudeCm = 18_000,
            altitudeConfidence = 8,
            heading = 1_350,
            headingConfidence = 10,
            speedCms = 140,
            speedConfidence = 10,
            positionAccurate = true,
        )
        val known = VehicleDimensions.Trailer.KNOWN_LENGTH
        val unknown = VehicleDimensions.Trailer.UNKNOWN_LENGTH
        val bicycleWithTrailer = VehicleDimensions.of(
            SremProfile.BICYCLE,
            mapOf(SremProfile.BICYCLE to VehicleDimensions.UNAVAILABLE.copy(trailer = unknown)),
        )
        // Each size with the station type of its CAM and the length, indication and width that tshark reads.
        val cams = listOf(
            Triple(StationType.HEAVY_TRUCK, VehicleDimensions(165, 26, known), "165 1 26"),
            Triple(StationType.PASSENGER_CAR, VehicleDimensions(45, 18, VehicleDimensions.Trailer.NONE), "45 0 18"),
            Triple(StationType.HEAVY_TRUCK, VehicleDimensions.UNAVAILABLE, "1023 4 62"),
            Triple(StationType.CYCLIST, bicycleWithTrailer, "1023 2 62"),
            Triple(StationType.PASSENGER_CAR, VehicleDimensions(null, 18, unknown), "1023 2 18"),
            Triple(StationType.PASSENGER_CAR, VehicleDimensions(null, 18, known), "1023 2 18"),
        )
        val frames = cams.mapIndexed { index, (stationType, size, _) ->
            val counters = ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = index, initialGbcSequenceId = 0)
            ItsG5FrameBuilder.camFrame(identity, stationType, position, 1_785_242_510_349L, counters, size)
        }

        val wireshark = Tshark.fields(
            frames,
            listOf(
                "its.stationType",
                "its.vehicleLengthValue",
                "its.vehicleLengthConfidenceIndication",
                "cam.vehicleWidth",
                "_ws.malformed",
            ),
        )

        wireshark.zip(cams).forEach { (fields, cam) ->
            val (stationType, size, expected) = cam
            assertEquals(stationType.code.toString(), fields["its.stationType"])
            assertEquals(size.lengthValue.toString(), fields["its.vehicleLengthValue"])
            assertEquals(size.lengthConfidenceIndication.toString(), fields["its.vehicleLengthConfidenceIndication"])
            assertEquals(size.widthValue.toString(), fields["cam.vehicleWidth"])
            assertEquals(
                expected,
                listOf("its.vehicleLengthValue", "its.vehicleLengthConfidenceIndication", "cam.vehicleWidth")
                    .joinToString(" ") { fields.getValue(it) },
            )
            assertEquals("", fields["_ws.malformed"])
        }
    }
}
