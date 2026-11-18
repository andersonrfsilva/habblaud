package br.com.xlinx.habblaud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationServiceRestartInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun startReturnsFalseAndDoesNotThrowWhenDenied() {
        // Revogar a permissão durante o teste pode encerrar o processo instrumentado.
        assumeTrue(
            "POST_NOTIFICATIONS deve estar negada antes de rodar este teste",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED,
        )
        assertFalse(NotificationService.start(context))
    }

    @Test
    fun startReturnsTrueWhenGrantedAndForeground() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(NotificationService.start(context))
            }
        } finally {
            NotificationService.stop(context)
        }
    }
}
