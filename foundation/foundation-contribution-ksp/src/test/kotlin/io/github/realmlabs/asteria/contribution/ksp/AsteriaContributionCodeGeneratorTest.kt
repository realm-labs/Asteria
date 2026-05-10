package io.github.realmlabs.asteria.contribution.ksp

import com.squareup.kotlinpoet.ClassName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AsteriaContributionCodeGeneratorTest {
    @Test
    fun generatesDescriptorAndInstanceLists() {
        val file = AsteriaContributionCodeGenerator.buildFiles(
            config = ContributionCodegenConfig(
                packageName = "com.example.activity",
                className = "GeneratedActivityServices",
                contractType = ACTIVITY_SERVICE,
            ),
            contributions = listOf(
                ContributionModel(SEVEN_DAY, objectDeclaration = true, order = 20),
                ContributionModel(RECHARGE, objectDeclaration = false, order = 10),
            ),
        ).single().file.toString()

        assertContains(file, "object GeneratedActivityServices")
        assertContains(file, "val CONTRIBUTIONS: List<AsteriaContributionDescriptor<ActivityService>> = listOf(")
        assertContains(file, "implementationType = RechargeActivityService::class, order = 10")
        assertContains(file, "create = { RechargeActivityService() }")
        assertContains(file, "implementationType = SevenDayActivityService::class, order = 20")
        assertContains(file, "create = { SevenDayActivityService }")
        assertContains(file, "fun createAll(): List<ActivityService>")
        assertContains(file, "val ALL: List<ActivityService> = createAll()")
    }

    @Test
    fun chunksLargeContributionLists() {
        val files = AsteriaContributionCodeGenerator.buildFiles(
            config = ContributionCodegenConfig(
                packageName = "com.example.activity",
                className = "GeneratedActivityServices",
                contractType = ACTIVITY_SERVICE,
                chunkSize = 2,
            ),
            contributions = (0 until 3).map { index ->
                ContributionModel(
                    ClassName("com.example.activity", "ActivityService$index"),
                    objectDeclaration = false,
                    order = index,
                )
            },
        )

        val fileNames = files.map { it.fileName }
        val aggregator = files.first { it.fileName == "GeneratedActivityServices" }.file.toString()
        val chunk0 = files.first { it.fileName == "GeneratedActivityServicesChunk0" }.file.toString()

        assertEquals(
            listOf("GeneratedActivityServices", "GeneratedActivityServicesChunk0", "GeneratedActivityServicesChunk1"),
            fileNames,
        )
        assertContains(aggregator, "addAll(GeneratedActivityServicesChunk0.CONTRIBUTIONS)")
        assertContains(aggregator, "addAll(GeneratedActivityServicesChunk1.CONTRIBUTIONS)")
        assertContains(chunk0, "internal object GeneratedActivityServicesChunk0")
    }

    @Test
    fun contributionTypeNamePartPreservesCamelCase() {
        assertEquals("ActivityService", "ActivityService".toContributionTypeNamePart())
        assertEquals("ActivityService", "activity-service".toContributionTypeNamePart())
    }

    private companion object {
        val ACTIVITY_SERVICE = ClassName("com.example.activity", "ActivityService")
        val SEVEN_DAY = ClassName("com.example.activity", "SevenDayActivityService")
        val RECHARGE = ClassName("com.example.activity", "RechargeActivityService")
    }
}
