package io.github.realmlabs.asteria.message.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsteriaMessageCodegenPluginTest {
    @Test
    fun `plugin registers extension with message catalog disabled by default`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply(AsteriaMessageCodegenPlugin::class.java)

        val extension = project.extensions.findByType(AsteriaMessageCodegenExtension::class.java)
        assertNotNull(extension)
        assertEquals(false, extension.messageCatalogEnabled.get())
    }
}
