package io.github.mikai233.asteria.persistence

/**
 * Marks a storage DTO as a Mongo document that should receive a generated tracked wrapper.
 *
 * The annotated class remains the serialization shape used by the Mongo driver. The generated wrapper is the mutable
 * actor-local view used by business logic; writes on that wrapper enqueue Mongo `$set` / `$unset` patches through the
 * tracking runtime.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoEntity(
    /**
     * Mongo collection name.
     */
    val collection: String,
    /**
     * Generated wrapper class name. Defaults to `Tracked<entity class name>`.
     */
    val wrapperName: String = "",
    /**
     * Generated helper object name. Defaults to `<entity class name>Mongo`.
     */
    val helperName: String = "",
)

/**
 * Marks the document id property.
 *
 * A generated tracked wrapper requires exactly one id property. In most cases this is the same property used to
 * implement [Entity.id].
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoId

/**
 * Overrides the Mongo field name used by the generated wrapper.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoField(
    val name: String = "",
)

/**
 * Excludes a property from generated tracking and persistence mapping.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoIgnore

/**
 * Marks a project-defined value type as safe to persist through the Mongo driver without generated field-level
 * tracking.
 *
 * Use this for value classes, custom codecs, or other immutable objects that the project has explicitly made
 * Mongo-serializable. Mutable business objects should usually be modeled as data classes that the KSP processor can
 * validate recursively, or as generated tracked document wrappers.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoValue
