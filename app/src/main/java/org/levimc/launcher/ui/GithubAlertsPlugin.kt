package org.levimc.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.levimc.launcher.R

class GithubAlertsPlugin(context: Context) : AbstractMarkwonPlugin() {

    private class Alert(val label: String, val color: Int)

    private val density = context.resources.displayMetrics.density
    private val barWidth = (3f * density).toInt()
    private val marginWidth = (12f * density).toInt()
    private val neutralColor = context.getColor(R.color.text_secondary)
    private val alerts = mapOf(
        "note" to Alert("Note", context.getColor(R.color.alert_note)),
        "tip" to Alert("Tip", context.getColor(R.color.alert_tip)),
        "important" to Alert("Important", context.getColor(R.color.changelog_important)),
        "warning" to Alert("Warning", context.getColor(R.color.alert_warning)),
        "caution" to Alert("Caution", context.getColor(R.color.alert_caution)),
    )

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(BlockQuote::class.java) { visitor, blockQuote ->
            val alert = detectAlert(blockQuote)
            if (alert != null) {
                injectLabel(blockQuote, alert)
            }
            visitor.ensureNewLine()
            val start = visitor.length()
            visitor.visitChildren(blockQuote)
            visitor.setSpans(start, CalloutSpan(alert?.color ?: neutralColor, barWidth, marginWidth))
        }
    }

    private fun detectAlert(blockQuote: BlockQuote): Alert? {
        val paragraph = blockQuote.firstChild as? Paragraph ?: return null
        val text = paragraph.firstChild as? Text ?: return null
        val match = MARKER_REGEX.find(text.literal) ?: return null
        val alert = alerts[match.groupValues[1].lowercase()] ?: return null
        val rest = match.groupValues[2]
        val softBreak = text.next as? SoftLineBreak
        text.unlink()
        if (rest.isEmpty()) {
            softBreak?.unlink()
        } else {
            paragraph.prependChild(Text(rest))
        }
        return alert
    }

    private fun injectLabel(blockQuote: BlockQuote, alert: Alert) {
        val label = Paragraph()
        val strong = StrongEmphasis()
        strong.appendChild(Text(alert.label))
        label.appendChild(strong)
        blockQuote.prependChild(label)
    }

    private class CalloutSpan(
        private val color: Int,
        private val barWidth: Int,
        private val marginWidth: Int,
    ) : LeadingMarginSpan {

        override fun getLeadingMargin(firstLine: Boolean): Int = marginWidth

        override fun drawLeadingMargin(
            canvas: Canvas,
            paint: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            first: Boolean,
            layout: Layout,
        ) {
            val previousColor = paint.color
            val previousStyle = paint.style
            paint.color = color
            paint.style = Paint.Style.FILL
            val left = if (dir > 0) x else x - barWidth
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + barWidth).toFloat(), bottom.toFloat(), paint)
            paint.style = previousStyle
            paint.color = previousColor
        }
    }

    private companion object {
        val MARKER_REGEX = Regex("""^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*(.*)$""")
    }
}
