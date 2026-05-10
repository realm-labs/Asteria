package io.github.realmlabs.asteria.gm.shutdown

import io.github.realmlabs.asteria.gm.core.GmFeatureId
import io.github.realmlabs.asteria.gm.core.GmRiskLevel
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
    fun `shutdown feature exposes high risk operation actions`() {
        val feature = GmShutdownFeature().descriptor
        val actions = feature.actions.map { it.key }.toSet()

        assertContains(actions, GmShutdownActions.Read)
        assertContains(actions, GmShutdownActions.Prepare)
        assertContains(actions, GmShutdownActions.Start)
        assertContains(actions, GmShutdownActions.Force)
        assertTrue(feature.actions.single { it.key == GmShutdownActions.Start }.risk == GmRiskLevel.High)
        assertTrue(feature.actions.single { it.key == GmShutdownActions.Force }.risk == GmRiskLevel.High)
    }
}
