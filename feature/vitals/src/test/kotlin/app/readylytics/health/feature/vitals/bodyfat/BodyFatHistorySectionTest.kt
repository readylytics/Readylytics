package app.readylytics.health.feature.vitals.bodyfat

import app.readylytics.health.core.model.domain.model.BodyFatCategory
import app.readylytics.health.feature.vitals.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyFatHistorySectionTest {
    @Test
    fun `every canonical body fat category has its synchronized history label`() {
        val expected =
            mapOf(
                BodyFatCategory.BELOW_ESSENTIAL to R.string.body_fat_category_below_essential,
                BodyFatCategory.ESSENTIAL to R.string.body_fat_category_essential,
                BodyFatCategory.ATHLETIC to R.string.body_fat_category_athletic,
                BodyFatCategory.FITNESS to R.string.body_fat_category_fitness,
                BodyFatCategory.ACCEPTABLE to R.string.body_fat_category_acceptable,
                BodyFatCategory.OBESE to R.string.body_fat_category_obese,
                BodyFatCategory.BELOW_REFERENCE to R.string.body_fat_category_below_reference,
                BodyFatCategory.WITHIN_REFERENCE to R.string.body_fat_category_within_reference,
                BodyFatCategory.ABOVE_REFERENCE to R.string.body_fat_category_above_reference,
            )

        expected.forEach { (category, label) ->
            assertEquals(label, bodyFatCategoryLabelRes(category))
        }
    }
}
