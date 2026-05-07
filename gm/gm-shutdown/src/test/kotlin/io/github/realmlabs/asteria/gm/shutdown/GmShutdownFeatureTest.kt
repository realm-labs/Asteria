package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.gm.core.GmFeatureId
import io.github.realmlabs.asteria.gm.core.discoverGmFeatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GmShutdownFeatureTest {
    @Test
    fun `shutdown feature is discoverable through service loader`() {
        val features = discoverGmFeatures()

        assertTrue(features.any { it.descriptor.id == GmFeatureId("asteria.shutdown") })
    }

    @Test
    fun `shutdown feature exposes high risk operation permissions`() {
        val feature = GmShutdownFeature().descriptor
        val permissions = feature.permissions.map { it.key }.toSet()

        assertContains(permissions, GmShutdownPermissions.Read)
        assertContains(permissions, GmShutdownPermissions.Prepare)
        assertContains(permissions, GmShutdownPermissions.Start)
        assertContains(permissions, GmShutdownPermissions.Force)
        assertTrue(feature.permissions.single { it.key == GmShutdownPermissions.Start }.highRisk)
        assertTrue(feature.permissions.single { it.key == GmShutdownPermissions.Force }.highRisk)
    }
}
