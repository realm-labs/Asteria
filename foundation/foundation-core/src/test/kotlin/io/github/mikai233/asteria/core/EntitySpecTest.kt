package io.github.mikai233.asteria.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntitySpecTest {
    @Test
    fun entityRoleIsOptionalByDefault() {
        val app = gameApplication {
            entity<Long>("player")
        }

        assertNull(app.entities.single().role)
        assertEquals(emptySet(), app.roles)
        assertEquals(emptySet(), app.declaredRoles)
    }

    @Test
    fun explicitEntityRoleIsAddedToDeclaredRoles() {
        val app = gameApplication {
            entity<Long>("player") {
                role("gameplay")
            }
        }

        assertEquals(RoleKey("gameplay"), app.entities.single().role)
        assertEquals(setOf(RoleKey("gameplay")), app.roles)
        assertEquals(setOf(RoleKey("gameplay")), app.declaredRoles)
    }
}
