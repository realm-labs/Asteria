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
 * [implementationType] is exposed so business code can build its own instantiation and indexing rules when needed.
 * [create] is a generated zero-argument factory for the common object/no-arg-class case.
 */
data class AsteriaContributionDescriptor<T : Any>(
    val implementationType: KClass<out T>,
    val order: Int = 0,
    val create: () -> T,
)
