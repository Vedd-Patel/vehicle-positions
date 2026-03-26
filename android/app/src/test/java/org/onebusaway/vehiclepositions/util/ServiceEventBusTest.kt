package org.onebusaway.vehiclepositions.util

import org.junit.Assert.assertNotNull
import org.junit.Test

class ServiceEventBusTest {

    @Test
    fun `ServiceEventBus initializes without error`() {
        val bus = ServiceEventBus()
        assertNotNull(bus)
    }
}