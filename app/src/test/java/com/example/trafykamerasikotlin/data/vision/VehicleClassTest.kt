package com.example.trafykamerasikotlin.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleClassTest {

    @Test fun `known coco indices map to the right classes`() {
        assertEquals(VehicleClass.PERSON,     VehicleClass.fromCocoIndex(0))
        assertEquals(VehicleClass.BICYCLE,    VehicleClass.fromCocoIndex(1))
        assertEquals(VehicleClass.CAR,        VehicleClass.fromCocoIndex(2))
        assertEquals(VehicleClass.MOTORCYCLE, VehicleClass.fromCocoIndex(3))
        assertEquals(VehicleClass.BUS,        VehicleClass.fromCocoIndex(5))
        assertEquals(VehicleClass.TRUCK,      VehicleClass.fromCocoIndex(7))
    }

    @Test fun `unknown coco indices fall back to UNKNOWN`() {
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.fromCocoIndex(4))   // airplane
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.fromCocoIndex(6))   // train
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.fromCocoIndex(15))  // cat
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.fromCocoIndex(-1))
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.fromCocoIndex(9999))
    }

    @Test fun `whitelist does not include UNKNOWN`() {
        assertFalse(VehicleClass.UNKNOWN in VehicleClass.WHITELIST)
    }

    @Test fun `whitelist includes every named road agent`() {
        assertTrue(VehicleClass.PERSON     in VehicleClass.WHITELIST)
        assertTrue(VehicleClass.BICYCLE    in VehicleClass.WHITELIST)
        assertTrue(VehicleClass.CAR        in VehicleClass.WHITELIST)
        assertTrue(VehicleClass.MOTORCYCLE in VehicleClass.WHITELIST)
        assertTrue(VehicleClass.BUS        in VehicleClass.WHITELIST)
        assertTrue(VehicleClass.TRUCK      in VehicleClass.WHITELIST)
    }
}
