package io.github.realmlabs.asteria.gm.cluster

import io.github.realmlabs.asteria.gm.core.GmFeatureId
import io.github.realmlabs.asteria.gm.core.GmRiskLevel
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
    fun `cluster feature exposes high risk control actions`() {
        val feature = GmClusterFeature().descriptor
        val actions = feature.actions.map { it.key }.toSet()

        assertContains(actions, GmClusterActions.Leave)
        assertContains(actions, GmClusterActions.Join)
        assertContains(actions, GmClusterActions.Down)
        assertContains(actions, GmClusterActions.ManagementRaw)
        assertTrue(feature.actions.single { it.key == GmClusterActions.Down }.risk == GmRiskLevel.High)
    }
}
