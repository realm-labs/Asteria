package io.github.mikai233.asteria.persistence

import kotlin.reflect.KClass

class DataManager<ID : Any>(
    private val scope: DataScope<ID>,
    private val modules: Iterable<DataModule<ID>>,
) {
    private val dataByType: MutableMap<KClass<out MemData>, MemData> = linkedMapOf()
    private val autoFlushData: MutableList<AutoFlushMemData> = mutableListOf()

    suspend fun loadAll() {
        modules.forEach { module ->
            val data = module.create(scope)
            data.load()
            dataByType[data::class] = data
            if (data is AutoFlushMemData) {
                autoFlushData.add(data)
            }
        }
    }

    fun <T : MemData> get(type: KClass<T>): T {
        @Suppress("UNCHECKED_CAST")
        return dataByType[type] as? T ?: error("mem data ${type.qualifiedName} not found")
    }

    suspend fun tick() {
        autoFlushData.forEach { it.tick() }
    }

    suspend fun flush(): Boolean {
        return autoFlushData.all { it.flush() }
    }
}
