package com.github.momostifler96.gitaireviewer.ui

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser

object Markdown {

    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().build()

    /** Renders an AI Markdown report to a standalone HTML page for the tool window. */
    fun toHtml(header: String, markdown: String): String {
        val body = renderer.render(parser.parse(markdown))
        return """
        <html>
        <head>
        <style>
          body { font-family: Segoe UI, sans-serif; font-size: 12px; margin: 12px 16px; }
          h1 { font-size: 16px; }
          h2 { font-size: 14px; }
          h3 { font-size: 13px; }
          pre { background-color: #F5F5F5; padding: 6px 10px; }
          code { background-color: #F5F5F5; }
          blockquote { color: #808080; }
          .meta { color: #808080; font-size: 11px; margin-bottom: 12px; }
        </style>
        </head>
        <body>
        <div class="meta">${header.escapeHtml()}</div>
        $body
        </body>
        </html>
        """
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
