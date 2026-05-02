package io.github.mikai233.asteria.gm.script

import io.github.mikai233.asteria.gm.core.GmFeatureId
import io.github.mikai233.asteria.gm.core.discoverGmFeatures
import kotlin.test.Test
import kotlin.test.assertTrue

class GmScriptFeatureTest {
    @Test
    fun `script feature is discoverable through service loader`() {
        val features = discoverGmFeatures()

        assertTrue(features.any { it.descriptor.id == GmFeatureId("asteria.script") })
    }
}
