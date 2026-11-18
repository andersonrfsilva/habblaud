package br.com.xlinx.habblaud

import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SvgEntitiesInstrumentedTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"><rect width="20" height="10"/></svg>"""

    @Test fun rendersSvgWithViewBox() {
        val size = mermaidPainter(svg.encodeToByteArray(), Density(1f)).intrinsicSize
        assertTrue(size.width > 0f && size.height > 0f)
    }

    @Test fun rejectsSvgWithDoctype() {
        val document = """<!DOCTYPE svg [<!ENTITY a "x">]>$svg"""
        assertThrows(IllegalArgumentException::class.java) {
            mermaidPainter(document.encodeToByteArray(), Density(1f))
        }
    }
}
