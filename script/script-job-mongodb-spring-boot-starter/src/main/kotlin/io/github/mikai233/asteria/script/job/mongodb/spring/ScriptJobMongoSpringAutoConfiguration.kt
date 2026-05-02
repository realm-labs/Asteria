package io.github.mikai233.asteria.script.job.mongodb.spring

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.mikai233.asteria.script.job.ScriptJobPermitRepository
import io.github.mikai233.asteria.script.job.ScriptJobRepository
import io.github.mikai233.asteria.script.job.mongodb.MongoScriptJobPermitRepository
import io.github.mikai233.asteria.script.job.mongodb.MongoScriptJobRepository
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for Mongo-backed script job persistence.
 *
 * The starter only wires the Mongo adapter. Core job semantics remain in `:script:script-job`, and applications can
 * still provide any other [ScriptJobRepository] implementation to replace this one.
 */
@AutoConfiguration
@ConditionalOnClass(MongoClient::class, MongoScriptJobRepository::class)
@ConditionalOnProperty(
    prefix = "asteria.script.job.mongodb",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(ScriptJobMongoProperties::class)
class ScriptJobMongoSpringAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MongoClient::class)
    @ConditionalOnProperty(prefix = "asteria.script.job.mongodb", name = ["uri"])
    fun asteriaScriptJobMongoClient(properties: ScriptJobMongoProperties): MongoClient {
        return MongoClient.create(requireNotNull(properties.uri) { "MongoDB URI must not be null" })
    }

    @Bean("asteriaScriptJobMongoDatabase")
    @ConditionalOnBean(MongoClient::class)
    @ConditionalOnMissingBean(name = ["asteriaScriptJobMongoDatabase"])
    fun asteriaScriptJobMongoDatabase(
        client: MongoClient,
        properties: ScriptJobMongoProperties,
    ): MongoDatabase {
        return client.getDatabase(properties.database)
    }

    @Bean
    @ConditionalOnMissingBean(value = [ScriptJobRepository::class], search = SearchStrategy.CURRENT)
    fun mongoScriptJobRepository(
        @Qualifier("asteriaScriptJobMongoDatabase") database: MongoDatabase,
        properties: ScriptJobMongoProperties,
    ): MongoScriptJobRepository {
        return MongoScriptJobRepository(
            database = database,
            jobsCollectionName = properties.jobsCollectionName,
            itemsCollectionName = properties.itemsCollectionName,
        )
    }

    @Bean
    @ConditionalOnMissingBean(value = [ScriptJobPermitRepository::class], search = SearchStrategy.CURRENT)
    fun mongoScriptJobPermitRepository(
        @Qualifier("asteriaScriptJobMongoDatabase") database: MongoDatabase,
        properties: ScriptJobMongoProperties,
    ): MongoScriptJobPermitRepository {
        return MongoScriptJobPermitRepository(
            database = database,
            collectionName = properties.permitsCollectionName,
        )
    }

    @Bean
    @ConditionalOnBean(MongoScriptJobRepository::class)
    @ConditionalOnProperty(
        prefix = "asteria.script.job.mongodb",
        name = ["ensure-indexes"],
        havingValue = "true",
    )
    fun mongoScriptJobIndexInitializer(repository: MongoScriptJobRepository): SmartInitializingSingleton {
        return SmartInitializingSingleton {
            runBlocking {
                repository.ensureIndexes()
            }
        }
    }

    @Bean
    @ConditionalOnBean(MongoScriptJobPermitRepository::class)
    @ConditionalOnProperty(
        prefix = "asteria.script.job.mongodb",
        name = ["ensure-indexes"],
        havingValue = "true",
    )
    fun mongoScriptJobPermitIndexInitializer(repository: MongoScriptJobPermitRepository): SmartInitializingSingleton {
        return SmartInitializingSingleton {
            runBlocking {
                repository.ensureIndexes()
            }
        }
    }
}
