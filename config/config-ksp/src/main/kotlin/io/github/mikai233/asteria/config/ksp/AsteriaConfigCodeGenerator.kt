package io.github.mikai233.asteria.config.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object AsteriaConfigCodeGenerator {
    fun buildFile(config: ConfigCodegenConfig, tables: List<ConfigTableModel>): FileSpec {
        val sortedTables = tables.sortedBy { it.tableName }
        validateTables(sortedTables)
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
                    .initializer("%M(%S)", CONFIG_TABLE_REF_FACTORY, table.tableName)
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
                                REQUIRE_TABLE,
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
            .addStatement("return %M(%T.%L)", REQUIRE_TABLE, tablesClass, table.refName)
            .build()
    }

    private fun buildServiceExtension(
        tablesClass: ClassName,
        table: ConfigTableModel,
    ): FunSpec {
        return FunSpec.builder(table.propertyName)
            .receiver(CONFIG_SERVICE)
            .returns(table.tableType())
            .addStatement("return current().%M(%T.%L)", REQUIRE_TABLE, tablesClass, table.refName)
            .build()
    }

    private fun ConfigTableModel.refType(): TypeName {
        return CONFIG_TABLE_REF.parameterizedBy(keyType, rowType)
    }

    private fun ConfigTableModel.tableType(): TypeName {
        return CONFIG_TABLE.parameterizedBy(keyType, rowType)
    }

    private val CONFIG_SERVICE = ClassName("io.github.mikai233.asteria.config", "ConfigService")
    private val CONFIG_SNAPSHOT = ClassName("io.github.mikai233.asteria.config", "ConfigSnapshot")
    private val CONFIG_TABLE = ClassName("io.github.mikai233.asteria.config", "ConfigTable")
    private val CONFIG_TABLE_REF = ClassName("io.github.mikai233.asteria.config", "ConfigTableRef")
    private val CONFIG_TABLE_REF_FACTORY = MemberName("io.github.mikai233.asteria.config", "configTableRef")
    private val REQUIRE_TABLE = MemberName("io.github.mikai233.asteria.config", "requireTable")
}

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
    val keyType: TypeName,
    val rowType: TypeName,
    val refName: String = tableName.toUpperCamelIdentifier(),
    val propertyName: String = tableName.toLowerCamelIdentifier(),
) {
    init {
        require(tableName.isNotBlank()) { "config table name must not be blank" }
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

private fun String.isValidKotlinIdentifier(): Boolean {
    if (isBlank()) {
        return false
    }
    val first = first()
    if (first != '_' && !first.isLetter()) {
        return false
    }
    return drop(1).all { it == '_' || it.isLetterOrDigit() }
}
