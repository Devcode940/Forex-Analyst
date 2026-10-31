package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.forex.data.model.CurrencyPair
import com.example.forex.data.model.PairCategory
import com.example.forex.data.model.Timeframe
import com.example.forex.ui.components.LiveTickerHeader
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val samplePair = CurrencyPair("EUR/USD", "Euro / US Dollar", PairCategory.MAJOR, "EUR", "USD", 1.0854, +0.35, 1.0880, 1.0820)
    composeTestRule.setContent {
      MyApplicationTheme {
        LiveTickerHeader(pair = samplePair, selectedTimeframe = Timeframe.H1, onTimeframeSelected = {})
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
