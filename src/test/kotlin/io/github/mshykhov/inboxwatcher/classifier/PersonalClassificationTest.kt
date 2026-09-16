package io.github.mshykhov.inboxwatcher.classifier

import io.github.mshykhov.inboxwatcher.core.Category
import io.github.mshykhov.inboxwatcher.core.Importance
import io.github.mshykhov.inboxwatcher.core.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonalClassificationTest {
    @Test
    fun `personal informational messages stay important even if model contradicts its category`() {
        val result =
            parseClassification(
                """{"category":"personal","importance":"not_important","urgency":"not_urgent","summary":"Друг благополучно добрался домой","action":"","company":"","reason":"A friend shares a personal update"}""",
            )
        assertEquals(Category.PERSONAL, result.category)
        assertEquals(Importance.IMPORTANT, result.importance)
        assertEquals(Urgency.NOT_URGENT, result.urgency)
        assertNull(result.action)
    }

    @Test
    fun `marketing remains unimportant`() {
        val result =
            parseClassification(
                """{"category":"other","importance":"not_important","urgency":"not_urgent","summary":"Рассылка скидок","reason":"Bulk marketing"}""",
            )
        assertEquals(Importance.NOT_IMPORTANT, result.importance)
    }
}
