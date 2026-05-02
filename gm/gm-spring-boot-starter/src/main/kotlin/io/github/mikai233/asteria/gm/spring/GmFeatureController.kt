package io.github.mikai233.asteria.gm.spring

import io.github.mikai233.asteria.gm.core.GmFeatureDescriptor
import io.github.mikai233.asteria.gm.core.GmFeatureRegistry
import io.github.mikai233.asteria.gm.core.GmMenuItem
import io.github.mikai233.asteria.gm.core.GmPermission
import io.github.mikai233.asteria.gm.core.GmRoute
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping($$"${asteria.gm.api-prefix:/gm/api}")
class GmFeatureController(
    private val registry: GmFeatureRegistry,
) {
    @GetMapping("/features")
    fun features(): List<GmFeatureDescriptor> {
        return registry.features()
    }

    @GetMapping("/menus")
    fun menus(): List<GmMenuItem> {
        return registry.menus()
    }

    @GetMapping("/routes")
    fun routes(): List<GmRoute> {
        return registry.routes()
    }

    @GetMapping("/permissions")
    fun permissions(): List<GmPermission> {
        return registry.permissions()
    }
}
