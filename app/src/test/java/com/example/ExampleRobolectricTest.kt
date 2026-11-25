package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrustPointRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TrustPoint", appName)
    }

    @Test
    fun `repository prevents duplicate otp within window`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = TrustPointRepository.getInstance(context)
        repo.clearOtpHistoryAsync()
        val first = repo.recordOtpIfUnique("+8801700000001", "123456", "Bangladesh", "test")
        val duplicate = repo.recordOtpIfUnique("+8801700000001", "123456", "Bangladesh", "test")
        assertTrue(first)
        assertFalse(duplicate)
    }

    @Test
    fun `automation runtime state supports on off and stop controls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AutomationRuntimeState.update(context) {
            it.copy(igAutoEnabled = true, igAutoRunning = true, igStepText = "Running")
        }
        assertTrue(AutomationRuntimeState.state.value.igAutoEnabled)
        assertTrue(AutomationRuntimeState.state.value.igAutoRunning)

        AutomationRuntimeState.requestToggleAutomationEnabled(context, false)
        assertFalse(AutomationRuntimeState.state.value.igAutoEnabled)
        assertFalse(AutomationRuntimeState.state.value.igAutoRunning)
        assertEquals("Automation OFF", AutomationRuntimeState.state.value.igStepText)
    }
}
