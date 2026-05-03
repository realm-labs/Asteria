package io.github.realmlabs.asteria.gm.cluster

import io.github.realmlabs.asteria.gm.core.GmFeatureId
import io.github.realmlabs.asteria.gm.core.discoverGmFeatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GmClusterFeatureTest {
    @Test
    fun `cluster feature is discoverable through service loader`() {
        val features = discoverGmFeatures()

        assertTrue(features.any { it.descriptor.id == GmFeatureId("cluster") })
    }

    @Test
    fun `cluster feature exposes high risk control permissions`() {
        val feature = GmClusterFeature().descriptor
        val permissions = feature.permissions.map { it.key }.toSet()

        assertContains(permissions, GmClusterPermissions.Leave)
        assertContains(permissions, GmClusterPermissions.Join)
        assertContains(permissions, GmClusterPermissions.Down)
        assertContains(permissions, GmClusterPermissions.ManagementRaw)
    }
}
