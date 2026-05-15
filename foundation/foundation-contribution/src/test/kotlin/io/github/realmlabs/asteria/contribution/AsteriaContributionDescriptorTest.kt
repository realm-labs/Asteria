package io.github.realmlabs.asteria.contribution

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AsteriaContributionDescriptorTest {
    @Test
    fun createWithTypeKeepsImplementationTypeAndInstanceTogether() {
        val descriptor: AsteriaContributionDescriptor<ActivityService, LoginActivityService> =
            AsteriaContributionDescriptor(LoginActivityService::class) { LoginActivityService("login") }

        val registry = TypeSafeRegistry()

        descriptor.createWithType { type, service ->
            registry.register(type, service)
        }

        assertEquals("login", registry.require(LoginActivityService::class).name)
    }

    @Test
    fun wildcardDescriptorListsCanBeRegisteredWithoutUncheckedCasts() {
        val descriptors: List<AsteriaContributionDescriptor<ActivityService, out ActivityService>> = listOf(
            AsteriaContributionDescriptor(LoginActivityService::class) { LoginActivityService("login") },
            AsteriaContributionDescriptor(SharedActivityService::class) { SharedActivityService },
        )
        val registry = TypeSafeRegistry()

        descriptors.forEach { descriptor ->
            descriptor.createWithType { type, service ->
                registry.register(type, service)
            }
        }

        assertEquals("login", registry.require(LoginActivityService::class).name)
        assertSame(SharedActivityService, registry.require(SharedActivityService::class))
    }

    private class TypeSafeRegistry {
        private val services = mutableMapOf<KClass<*>, Any>()

        fun <T : Any> register(type: KClass<T>, service: T) {
            services[type] = service
        }

        fun <T : Any> require(type: KClass<T>): T {
            @Suppress("UNCHECKED_CAST")
            return services.getValue(type) as T
        }
    }

    private interface ActivityService {
        val name: String
    }

    private data class LoginActivityService(
        override val name: String,
    ) : ActivityService

    private data object SharedActivityService : ActivityService {
        override val name: String = "shared"
    }
}
