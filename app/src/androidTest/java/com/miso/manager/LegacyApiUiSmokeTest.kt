package com.miso.manager

import android.Manifest
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyApiUiSmokeTest {
    @get:Rule
    val storagePermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @Test
    fun sortingDoesNotCrashOnLegacyAndroid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.toggleSort() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun themeCanBeChangedOnLegacyAndroid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showThemePicker()
                val dialog = activity.activeThemeDialog
                assertNotNull("Theme picker dialog should be open", dialog)
                val option = findByContentDescription(dialog!!.window!!.decorView, "Ocean Blue")
                assertNotNull("Ocean Blue option should be visible", option)
                option!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val savedTheme = activity.getSharedPreferences("miso", 0).getString("color_theme", null)
                assertEquals("ocean", savedTheme)
            }
        }
    }

    private fun findByContentDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByContentDescription(view.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }
}
