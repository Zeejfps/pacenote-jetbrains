package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.util.Disposer
import dev.diffdino.pairing.pairing.NoticeTone
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedCard
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.awt.Color

/** Something the plugin says on the session's behalf, in a card tinted by what kind of thing it is. */
class NoticeRow(text: String, background: Color, lifetime: Lifetime) : RoundedCard(background, ChatColors.border) {
    init {
        val pane = WrappingHtmlPane()
        Disposer.register(lifetime, pane)
        pane.showPlain(text)
        add(pane, BorderLayout.CENTER)
    }

    companion object {
        fun of(text: String, tone: NoticeTone, lifetime: Lifetime): NoticeRow = NoticeRow(text, background(tone), lifetime)

        fun background(tone: NoticeTone): Color = when (tone) {
            NoticeTone.Info -> ChatColors.raised
            NoticeTone.Refused -> ChatColors.refusedNoticeBackground
            NoticeTone.Error -> ChatColors.errorNoticeBackground
        }
    }
}
