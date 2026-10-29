package com.smsforwarder.desktop

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Simple generated tray icon (no asset file required). */
val TrayIcon: Painter by lazy {
    val size = 64
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(25, 118, 210)
    g.fillRoundRect(4, 4, size - 8, size - 8, 16, 16)
    g.color = Color.WHITE
    g.font = Font("SansSerif", Font.BOLD, 28)
    val fm = g.fontMetrics
    val text = "S"
    val x = (size - fm.stringWidth(text)) / 2
    val y = (size - fm.height) / 2 + fm.ascent
    g.drawString(text, x, y)
    g.dispose()
    img.toPainter()
}
