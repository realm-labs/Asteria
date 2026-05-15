package io.github.realmlabs.asteria.contribution

import kotlin.reflect.KClass

/**
 * Marks a public implementation as a generated contribution for [contract].
 *
 * The annotated declaration must be a public concrete class with a zero-argument primary constructor or a public
 * object declaration, and it must implement the declared contract. The processor only generates contribution lists;
 * business code decides whether to turn those entries into maps, grouped indexes, patchable registries, or plain lists.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaContribution(
    val contract: KClass<*>,
    val order: Int = 0,
)

/**
 * Configures the generated contribution catalog for one [contract].
 *
 * The catalog annotation is optional. Without it, the processor generates a catalog in the contract package named
 * `Generated<ContractSimpleName>Contributions`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaContributionCatalog(
    val contract: KClass<*>,
    val packageName: String = "",
    val className: String = "",
    val chunkSize: Int = 200,
)

/**
 * One generated contribution entry.
 *
 * [T] is the shared contribution contract. [I] is the concrete implementation type for this entry. Keeping both type
 * parameters preserves the relationship between [implementationType] and the instance returned by [create].
 */
data class AsteriaContributionDescriptor<T : Any, I : T>(
    val implementationType: KClass<I>,
    val order: Int = 0,
    val create: () -> I,
) {
    /**
     * Creates the contribution and passes it together with its concrete type.
     */
    fun <R> createWithType(block: (KClass<I>, I) -> R): R {
        return block(implementationType, create())
    }
}
