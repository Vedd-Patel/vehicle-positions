package org.onebusaway.vehiclepositions.util

import org.junit.Assert.assertNull
import org.junit.Test

class LocationStateHolderTest {

    private val holder = LocationStateHolder()

    @Test
    fun `initial location is null`() {
        assertNull(holder.lastLocation.value)
    }

    @Test
    fun `hasLocation returns false when no location set`() {
        assertNull(holder.lastLocation.value)
    }
}