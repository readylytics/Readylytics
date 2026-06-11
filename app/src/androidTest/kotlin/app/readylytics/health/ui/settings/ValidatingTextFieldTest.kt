package app.readylytics.health.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.validation.IntRangeRule
import app.readylytics.health.ui.components.ValidatingTextField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ValidatingTextFieldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val rule = IntRangeRule(1, 100, "Value: 1–100")

    @Test
    fun validatingTextField_rendersWithValidValue() {
        composeTestRule.setContent {
            Surface {
                ValidatingTextField(
                    value = "50",
                    onValueChange = {},
                    rule = rule,
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun validatingTextField_rendersWithEmptyValue() {
        composeTestRule.setContent {
            Surface {
                ValidatingTextField(
                    value = "",
                    onValueChange = {},
                    rule = rule,
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun validatingTextField_rendersWithInvalidValue() {
        composeTestRule.setContent {
            Surface {
                ValidatingTextField(
                    value = "150",
                    onValueChange = {},
                    rule = rule,
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }
}
