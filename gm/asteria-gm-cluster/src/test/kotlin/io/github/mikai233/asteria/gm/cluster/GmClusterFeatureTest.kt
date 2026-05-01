package io.github.mikai233.asteria.gm.cluster

import io.github.mikai233.asteria.gm.core.GmFeatureId
import io.github.mikai233.asteria.gm.core.discoverGmFeatures
import kotlin.test.Test
import kotlin.test.assertTrue

class GmClusterFeatureTest {
    @Test
    fun `cluster feature is discoverable through service loader`() {
        val features = discoverGmFeatures()

        assertTrue(features.any { it.descriptor.id == GmFeatureId("cluster") })
    }
}
