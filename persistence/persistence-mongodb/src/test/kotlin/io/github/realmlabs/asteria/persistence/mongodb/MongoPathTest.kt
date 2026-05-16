package io.github.realmlabs.asteria.persistence.mongodb

import io.github.realmlabs.asteria.persistence.mongodb.common.MongoPath
import io.github.realmlabs.asteria.persistence.mongodb.common.mongoValueOf
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

class MongoPathTest {
    @Test
    fun `path encoding is reversible for mongo special keys`() {
        val parts = listOf(
            "plain",
            "a.b",
            $$"a$b",
            "a%b",
            "a%2Eb",
            "a%24b",
            "a%25b",
            "",
            "%EMPTY",
            "a\u0000b",
        )

        val encoded = parts.map(MongoPath::encodePathPart)

        assertEquals(parts.size, encoded.toSet().size)
        assertEquals(parts, encoded.map(MongoPath::decodePathPart))
    }

    @Test
    fun `empty child key does not create an empty update path segment`() {
        val path = MongoPath("player", 1001L, "bag").child("")

        assertEquals("bag.%EMPTY", path.fieldPath)
    }

    @Test
    fun `persistent map value uses the same key encoding`() {
        val value = mongoValueOf(
            mapOf(
                "a.b" to 1,
                "a%2Eb" to 2,
                "" to 3,
            ),
        )

        assertEquals(
            Document(
                mapOf(
                    "a%2Eb" to 1,
                    "a%252Eb" to 2,
                    "%EMPTY" to 3,
                ),
            ),
            value,
        )
    }
}
