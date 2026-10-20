package dev.pacenote.ui.support

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JPanel

class PillBubbleTest : BasePlatformTestCase() {
    private fun laidOut(wanted: Int, row: Int): java.awt.Component {
        val content = JPanel().apply { preferredSize = Dimension(10, 20) }
        val bubble = PillBubble(content, { wanted })
        bubble.setSize(row, 100)
        UIUtil.layoutRecursively(bubble)
        return bubble.getComponent(0)
    }

    fun testABubbleIsAsWideAsItsTextAndSitsOnTheRight() {
        val inner = laidOut(wanted = 100, row = 400)

        assertTrue("width=${inner.width}", inner.width in 100..160)
        assertEquals(400, inner.x + inner.width)
    }

    fun testABubbleStopsAtThreeQuartersOfTheRow() {
        val inner = laidOut(wanted = 5000, row = 400)

        assertEquals(300, inner.width)
        assertEquals(100, inner.x)
    }
}
