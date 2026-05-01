package io.github.mikai233.asteria.script.job.mongodb.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Mongo-backed script job persistence.
 */
@ConfigurationProperties(prefix = "asteria.script.job.mongodb")
class ScriptJobMongoProperties {
    /**
     * MongoDB connection URI used when the application does not provide its own coroutine MongoClient bean.
     */
    var uri: String? = null

    /**
     * Database used by the script job repository.
     */
    var database: String = "asteria"

    /**
     * Collection storing one document per submitted script job.
     */
    var jobsCollectionName: String = "script_jobs"

    /**
     * Collection storing one document per concrete script job item.
     */
    var itemsCollectionName: String = "script_job_items"

    /**
     * Whether the starter should create expected indexes during application startup.
     */
    var ensureIndexes: Boolean = false
}
