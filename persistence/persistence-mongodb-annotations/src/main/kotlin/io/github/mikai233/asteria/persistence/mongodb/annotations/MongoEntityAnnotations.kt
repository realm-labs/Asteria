package io.github.mikai233.asteria.persistence.mongodb.annotations

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
 * A generated tracked wrapper requires exactly one id property. In most cases this is the same property used as the
 * business entity id.
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
 * Excludes a persisted property from generated scan-based dirty tracking.
 *
 * Use this only when the field is maintained by another write path. The generated wrapper path is unaffected.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoScanIgnore

/**
 * Forces generated scan-based dirty tracking to compare and write a collection as one whole field.
 *
 * Map properties are scanned by key by default. Mark a map with this annotation when per-key updates are not wanted.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoScanWholeField

/**
 * Reserved for a future keyed-list storage model.
 *
 * This annotation is currently rejected by the Mongo KSP processor. Mongo array paths are positional, so writing
 * `field.<element id>` would not mean "the list element whose id is `<element id>`". Model keyed, independently updated
 * collections as `Map<ID, Value>` for scan-based dirty tracking.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class AsteriaMongoScanListById(
    val property: String,
)

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
