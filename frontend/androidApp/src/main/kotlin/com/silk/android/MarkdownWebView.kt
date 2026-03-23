package com.silk.android

import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 使用 WebView + KaTeX 渲染 Markdown 内容
 * 解决 APK 端数学公式显示问题，与 Web 端保持一致的渲染效果
 */

// CDN URLs
private const val KATEX_CSS = "https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.css"
private const val MARKED_JS = "https://cdn.jsdelivr.net/npm/marked@12.0.0/marked.min.js"
private const val KATEX_JS = "https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.js"
private const val KATEX_RENDER_JS = "https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/contrib/auto-render.min.js"
private const val HIGHLIGHT_JS = "https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.9.0/highlight.min.js"
private const val HIGHLIGHT_CSS = "https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.9.0/styles/github-dark.min.css"

// Markdown CSS 样式
private const val MARKDOWN_CSS = """
.markdown-body {
    color: #1E293B;
    font-size: 14px;
    line-height: 1.8;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Roboto", sans-serif;
    overflow-wrap: anywhere;
    padding: 0;
    margin: 0;
}
.markdown-body > :first-child { margin-top: 0 !important; }
.markdown-body > :last-child { margin-bottom: 0 !important; }
.markdown-body p { margin: 0.5em 0; white-space: pre-wrap; }
.markdown-body h1 { font-size: 1.5em; font-weight: 700; color: #C9A86C; margin: 1em 0 0.5em 0; }
.markdown-body h2 { font-size: 1.25em; font-weight: 700; color: #4A4038; margin: 0.8em 0 0.4em 0; }
.markdown-body h3 { font-size: 1.1em; font-weight: 600; color: #4A4038; margin: 0.6em 0 0.3em 0; }
.markdown-body pre { background: #0F172A !important; border-radius: 12px; padding: 14px 16px; overflow-x: auto; margin: 0.5em 0; }
.markdown-body pre code { background: transparent !important; font-size: 13px; font-family: "Fira Code", "Monaco", "Consolas", monospace; white-space: pre-wrap; word-break: break-word; display: block; }
.markdown-body pre code:not(.hljs) { color: #E5E7EB; }
.markdown-body code:not(pre code) { background: rgba(59, 130, 246, 0.10); color: #1D4ED8; border-radius: 4px; padding: 0.15em 0.35em; font-size: 0.9em; }
.markdown-body blockquote { color: #475569; background: linear-gradient(180deg, rgba(59, 130, 246, 0.10), rgba(59, 130, 246, 0.04)); border-left: 4px solid #3B82F6; border-radius: 0 12px 12px 0; padding: 12px 16px; margin: 0.5em 0; }
.markdown-body table { display: block; width: max-content; max-width: 100%; overflow-x: auto; border-radius: 10px; border-collapse: collapse; margin: 0.5em 0; }
.markdown-body table thead tr { background: #EFF6FF; }
.markdown-body table th, .markdown-body table td { border: 1px solid #E2E8F0; padding: 8px 12px; text-align: left; }
.markdown-body table th { font-weight: 600; }
.markdown-body hr { height: 1px; border: 0; background: linear-gradient(90deg, rgba(226, 232, 240, 0), rgba(148, 163, 184, 0.75), rgba(226, 232, 240, 0)); margin: 1em 0; }
.markdown-body ul, .markdown-body ol { margin: 0.5em 0; padding-left: 1.5em; }
.markdown-body li { margin: 0.25em 0; white-space: pre-wrap; }
.markdown-body ul li::marker { color: #C9A86C; }
.markdown-body .task-list-item { list-style: none; margin-left: -1.5em; }
.markdown-body .task-list-item-checkbox { margin: 0 0.5em 0 0; transform: scale(1.1); }
.markdown-body img { max-width: 100%; border-radius: 8px; }
.markdown-body a { color: #3B82F6; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body .katex-display { overflow-x: auto; overflow-y: hidden; padding: 0.5em 0; margin: 0.5em 0; text-align: center; }
.markdown-body .katex { font-size: 1.1em; }
.math-block { display: block; text-align: center; margin: 1em 0; overflow-x: auto; background: #f8f9fa; padding: 0.8em; border-radius: 8px; }
"""

/**
 * 将字符串编码为 JavaScript 字符串字面量
 */
private fun encodeToJsString(s: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 32 || c.code > 127) {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * 生成完整的 HTML 页面
 */
private fun generateMarkdownHtml(content: String): String {
    val jsContent = encodeToJsString(content)
    
    val delimitersConfig = """
            delimiters: [
                {left: '$$$', right: '$$$', display: true},
                {left: '\\[', right: '\\]', display: true},
                {left: '$', right: '$', display: false},
                {left: '\\(', right: '\\)', display: false}
            ]
    """.trimIndent().replace("$$$", "$$")
    
    return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="$KATEX_CSS">
    <link rel="stylesheet" href="$HIGHLIGHT_CSS">
    <script src="$MARKED_JS"></script>
    <script src="$KATEX_JS"></script>
    <script src="$KATEX_RENDER_JS"></script>
    <script src="$HIGHLIGHT_JS"></script>
    <style>
        html, body { margin: 0; padding: 8px; background: transparent; }
        $MARKDOWN_CSS
    </style>
</head>
<body>
    <div id="content" class="markdown-body"></div>
    <script>
    (function() {
        var hljsReady = typeof hljs !== 'undefined';
        
        function renderContent() {
            try {
                if (typeof marked === 'undefined') { setTimeout(renderContent, 50); return; }
                if (typeof renderMathInElement === 'undefined') { setTimeout(renderContent, 50); return; }
                if (!hljsReady && typeof hljs !== 'undefined') { hljsReady = true; }
                if (!hljsReady && renderContent.waitCount < 10) {
                    renderContent.waitCount = (renderContent.waitCount || 0) + 1;
                    setTimeout(renderContent, 50);
                    return;
                }
                
                marked.setOptions({ breaks: true, gfm: true });
                
                var rawContent = $jsContent;
                var mathBlocks = [];
                var mathInlines = [];
                var idx = 0;
                
                rawContent = rawContent.replace(/\$\$([\s\S]+?)\$\$/g, function(m, f) {
                    mathBlocks[idx] = f;
                    return ' MATHBLOCK' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\\\[([\s\S]+?)\\\]/g, function(m, f) {
                    mathBlocks[idx] = f;
                    return ' MATHBLOCK' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\$([^\$\n]+?)\$/g, function(m, f) {
                    mathInlines[idx] = f;
                    return ' MATHINLINE' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\\\((.+?)\\\)/g, function(m, f) {
                    mathInlines[idx] = f;
                    return ' MATHINLINE' + (idx++) + 'END ';
                });
                
                var html = marked.parse(rawContent);
                
                html = html.replace(/MATHBLOCK(\d+)END/g, function(m, i) {
                    return '<div class="math-block">$$' + mathBlocks[i] + '$$</div>';
                });
                
                html = html.replace(/MATHINLINE(\d+)END/g, function(m, i) {
                    return '$' + mathInlines[i] + '$';
                });
                
                document.getElementById('content').innerHTML = html;
                
                if (typeof hljs !== 'undefined') {
                    document.querySelectorAll('pre code').forEach(function(block) {
                        try { hljs.highlightElement(block); } catch(e) {}
                    });
                }
                
                renderMathInElement(document.getElementById('content'), {
                    throwOnError: false,
                    strict: 'ignore',
                    ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
                    $delimitersConfig
                });
                
                document.querySelectorAll('a').forEach(function(link) {
                    link.setAttribute('target', '_blank');
                    link.setAttribute('rel', 'noopener noreferrer');
                });
                
            } catch(e) {
                console.error('Render error:', e);
                document.getElementById('content').textContent = 'Error: ' + e.message;
            }
        }
        renderContent();
    })();
    </script>
</body>
</html>"""
}

/**
 * Composable 函数：使用 WebView 渲染 Markdown
 * 简化版本 - 使用 heightIn 限制最小高度，允许自适应
 */
@Composable
fun MarkdownWebView(
    content: String,
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(content) { generateMarkdownHtml(content) }
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.blockNetworkImage = false
                settings.blockNetworkLoads = false
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://cdn.jsdelivr.net",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .wrapContentHeight(unbounded = true)
    )
}
