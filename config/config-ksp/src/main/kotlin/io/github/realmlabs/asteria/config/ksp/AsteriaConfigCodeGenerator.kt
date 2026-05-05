package io.github.realmlabs.asteria.config.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.realmlabs.asteria.config.annotations.AsteriaConfigTableShape

object AsteriaConfigCodeGenerator {
    fun buildFiles(config: ConfigCodegenConfig, tables: List<ConfigTableModel>): List<ConfigGeneratedFile> {
        val sortedTables = tables.sortedBy { it.tableName }
        validateTables(sortedTables)
        if (sortedTables.size <= TABLE_EXTENSION_CHUNK_SIZE) {
            return listOf(ConfigGeneratedFile(config.accessorClassName, buildSingleFile(config, sortedTables)))
        }
        val mainFile = ConfigGeneratedFile(config.accessorClassName, buildMainFile(config, sortedTables))
        val chunkFiles = sortedTables.chunked(TABLE_EXTENSION_CHUNK_SIZE).mapIndexed { index, chunk ->
            val chunkName = "${config.accessorClassName}ExtensionsChunk$index"
            ConfigGeneratedFile(chunkName, buildExtensionChunkFile(config, chunkName, chunk))
        }
        return listOf(mainFile) + chunkFiles
    }

    fun buildFile(config: ConfigCodegenConfig, tables: List<ConfigTableModel>): FileSpec {
        val sortedTables = tables.sortedBy { it.tableName }
        validateTables(sortedTables)
        return buildSingleFile(config, sortedTables)
    }

    private fun buildSingleFile(
        config: ConfigCodegenConfig,
        sortedTables: List<ConfigTableModel>,
    ): FileSpec {
        val tablesClass = ClassName(config.packageName, config.tablesObjectName)
        return FileSpec.builder(config.packageName, config.accessorClassName)
            .addType(buildTablesObject(tablesClass, sortedTables))
            .addType(buildAccessorClass(config.accessorClassName, tablesClass, sortedTables))
            .apply {
                sortedTables.forEach { table ->
                    addFunction(buildSnapshotExtension(tablesClass, table))
                    addFunction(buildServiceExtension(tablesClass, table))
                }
            }
            .build()
    }

    private fun buildMainFile(
        config: ConfigCodegenConfig,
        sortedTables: List<ConfigTableModel>,
    ): FileSpec {
        val tablesClass = ClassName(config.packageName, config.tablesObjectName)
        return FileSpec.builder(config.packageName, config.accessorClassName)
            .addType(buildTablesObject(tablesClass, sortedTables))
            .addType(buildAccessorClass(config.accessorClassName, tablesClass, sortedTables))
            .build()
    }

    private fun buildExtensionChunkFile(
        config: ConfigCodegenConfig,
        chunkName: String,
        tables: List<ConfigTableModel>,
    ): FileSpec {
        val tablesClass = ClassName(config.packageName, config.tablesObjectName)
        return FileSpec.builder(config.packageName, chunkName)
            .apply {
                tables.forEach { table ->
                    addFunction(buildSnapshotExtension(tablesClass, table))
                    addFunction(buildServiceExtension(tablesClass, table))
                }
            }
            .build()
    }

    private fun validateTables(tables: List<ConfigTableModel>) {
        tables.groupBy { it.tableName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { table ->
            error("duplicate config table name $table")
        }
        tables.groupBy { it.refName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { ref ->
            error("duplicate generated config table ref name $ref")
        }
        tables.groupBy { it.propertyName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { property ->
            error("duplicate generated config accessor property name $property")
        }
    }

    private fun buildTablesObject(
        tablesClass: ClassName,
        tables: List<ConfigTableModel>,
    ): TypeSpec {
        val builder = TypeSpec.objectBuilder(tablesClass)
        tables.forEach { table ->
            builder.addProperty(
                PropertySpec.builder(table.refName, table.refType())
                    .initializer("%M(%S)", table.refFactory(), table.tableName)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun buildAccessorClass(
        accessorClassName: String,
        tablesClass: ClassName,
        tables: List<ConfigTableModel>,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(accessorClassName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(ParameterSpec.builder("configService", CONFIG_SERVICE).build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("configService", CONFIG_SERVICE)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("configService")
                    .build(),
            )
        tables.forEach { table ->
            builder.addProperty(
                PropertySpec.builder(table.propertyName, table.tableType())
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement(
                                "return configService.current().%M(%T.%L)",
                                table.requireFunction(),
                                tablesClass,
                                table.refName,
                            )
                            .build(),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun buildSnapshotExtension(
        tablesClass: ClassName,
        table: ConfigTableModel,
    ): FunSpec {
        return FunSpec.builder(table.propertyName)
            .receiver(CONFIG_SNAPSHOT)
            .returns(table.tableType())
            .addStatement("return %M(%T.%L)", table.requireFunction(), tablesClass, table.refName)
            .build()
    }

    private fun buildServiceExtension(
        tablesClass: ClassName,
        table: ConfigTableModel,
    ): FunSpec {
        return FunSpec.builder(table.propertyName)
            .receiver(CONFIG_SERVICE)
            .returns(table.tableType())
            .addStatement("return current().%M(%T.%L)", table.requireFunction(), tablesClass, table.refName)
            .build()
    }

    private fun ConfigTableModel.refType(): TypeName {
        return when (shape) {
            AsteriaConfigTableShape.KEYED -> CONFIG_TABLE_REF.parameterizedBy(requireNotNull(keyType), rowType)
            AsteriaConfigTableShape.LIST,
            AsteriaConfigTableShape.SINGLETON,
                -> ROW_CONFIG_TABLE_REF.parameterizedBy(rowType)
        }
    }

    private fun ConfigTableModel.tableType(): TypeName {
        return when (shape) {
            AsteriaConfigTableShape.KEYED -> KEYED_CONFIG_TABLE.parameterizedBy(requireNotNull(keyType), rowType)
            AsteriaConfigTableShape.LIST -> LIST_CONFIG_TABLE.parameterizedBy(rowType)
            AsteriaConfigTableShape.SINGLETON -> SINGLE_CONFIG_TABLE.parameterizedBy(rowType)
        }
    }

    private fun ConfigTableModel.refFactory(): MemberName {
        return when (shape) {
            AsteriaConfigTableShape.KEYED -> CONFIG_TABLE_REF_FACTORY
            AsteriaConfigTableShape.LIST,
            AsteriaConfigTableShape.SINGLETON,
                -> ROW_CONFIG_TABLE_REF_FACTORY
        }
    }

    private fun ConfigTableModel.requireFunction(): MemberName {
        return when (shape) {
            AsteriaConfigTableShape.KEYED -> REQUIRE_TABLE
            AsteriaConfigTableShape.LIST -> REQUIRE_LIST_TABLE
            AsteriaConfigTableShape.SINGLETON -> REQUIRE_SINGLE_TABLE
        }
    }

    private val CONFIG_SERVICE = ClassName("io.github.realmlabs.asteria.config", "ConfigService")
    private val CONFIG_SNAPSHOT = ClassName("io.github.realmlabs.asteria.config", "ConfigSnapshot")
    private val KEYED_CONFIG_TABLE = ClassName("io.github.realmlabs.asteria.config", "KeyedConfigTable")
    private val LIST_CONFIG_TABLE = ClassName("io.github.realmlabs.asteria.config", "ListConfigTable")
    private val SINGLE_CONFIG_TABLE = ClassName("io.github.realmlabs.asteria.config", "SingleConfigTable")
    private val CONFIG_TABLE_REF = ClassName("io.github.realmlabs.asteria.config", "ConfigTableRef")
    private val ROW_CONFIG_TABLE_REF = ClassName("io.github.realmlabs.asteria.config", "RowConfigTableRef")
    private val CONFIG_TABLE_REF_FACTORY = MemberName("io.github.realmlabs.asteria.config", "configTableRef")
    private val ROW_CONFIG_TABLE_REF_FACTORY = MemberName("io.github.realmlabs.asteria.config", "rowConfigTableRef")
    private val REQUIRE_TABLE = MemberName("io.github.realmlabs.asteria.config", "requireTable")
    private val REQUIRE_LIST_TABLE = MemberName("io.github.realmlabs.asteria.config", "requireListTable")
    private val REQUIRE_SINGLE_TABLE = MemberName("io.github.realmlabs.asteria.config", "requireSingleTable")
    private const val TABLE_EXTENSION_CHUNK_SIZE = 200
}

data class ConfigGeneratedFile(
    val fileName: String,
    val file: FileSpec,
)

data class ConfigCodegenConfig(
    val packageName: String,
    val tablesObjectName: String,
    val accessorClassName: String,
) {
    init {
        require(packageName.isNotBlank()) { "generated config package name must not be blank" }
        require(tablesObjectName.isValidKotlinIdentifier()) { "invalid generated tables object name $tablesObjectName" }
        require(accessorClassName.isValidKotlinIdentifier()) { "invalid generated accessor class name $accessorClassName" }
    }
}

data class ConfigTableModel(
    val tableName: String,
    val shape: AsteriaConfigTableShape = AsteriaConfigTableShape.KEYED,
    val keyType: TypeName? = null,
    val rowType: TypeName,
    val refName: String = tableName.toUpperCamelIdentifier(),
    val propertyName: String = tableName.toLowerCamelIdentifier(),
) {
    init {
        require(tableName.isNotBlank()) { "config table name must not be blank" }
        require(shape != AsteriaConfigTableShape.KEYED || keyType != null) {
            "keyed config table $tableName requires keyType"
        }
        require(refName.isValidKotlinIdentifier()) { "invalid generated config table ref name $refName" }
        require(propertyName.isValidKotlinIdentifier()) { "invalid generated config accessor property name $propertyName" }
    }
}

internal fun String.toUpperCamelIdentifier(): String {
    return words().joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }
}

internal fun String.toLowerCamelIdentifier(): String {
    val words = words()
    if (words.isEmpty()) {
        return "_"
    }
    val value = words.mapIndexed { index, word ->
        if (index == 0) {
            word.replaceFirstChar(Char::lowercaseChar)
        } else {
            word.replaceFirstChar(Char::uppercaseChar)
        }
    }.joinToString("")
    return if (value.firstOrNull()?.isDigit() == true) "_$value" else value
}

private fun String.words(): List<String> {
    return split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .ifEmpty { listOf("_") }
}

internal fun String.isValidKotlinIdentifier(): Boolean {
    if (isBlank()) {
        return false
    }
    val first = first()
    if (first != '_' && !first.isLetter()) {
        return false
    }
    return drop(1).all { it == '_' || it.isLetterOrDigit() }
}
