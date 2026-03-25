/**
 * Markdown 渲染组件
 * 使用 Web 组件 + KaTeX 渲染 Markdown 内容
 * 解决鸿蒙端数学公式显示问题，与 Android/Web 端保持一致的渲染效果
 */
import { webview } from '@kit.ArkWeb';

// CDN URLs
const KATEX_CSS: string = 'https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.css';
const MARKED_JS: string = 'https://cdn.jsdelivr.net/npm/marked@12.0.0/marked.min.js';
const KATEX_JS: string = 'https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.js';
const KATEX_RENDER_JS: string = 'https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/contrib/auto-render.min.js';
const HIGHLIGHT_JS: string = 'https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.9.0/highlight.min.js';
const HIGHLIGHT_CSS: string = 'https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.9.0/styles/github-dark.min.css';

// Markdown CSS 样式
const MARKDOWN_CSS: string = `
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
`;

/**
 * 检测是否包含复杂公式，适合使用 KaTeX 增强渲染。
 */
export function contentNeedsMathWebRender(content: string): boolean {
  const source = content ? content : '';
  if (source.indexOf('$$') >= 0 || source.indexOf('\\[') >= 0 || source.indexOf('\\(') >= 0) {
    return true;
  }
  const keywords: string[] = ['\\frac', '\\sqrt', '\\sum', '\\int', '\\prod', '\\left', '\\right'];
  for (let i = 0; i < keywords.length; i++) {
    if (source.indexOf(keywords[i]) >= 0) {
      return true;
    }
  }
  return false;
}

/**
 * 将字符串编码为 JavaScript 字符串字面量
 */
function encodeToJsString(s: string): string {
  let result: string = '"';
  for (let i = 0; i < s.length; i++) {
    const c: string = s[i];
    const code: number = c.charCodeAt(0);
    if (c === '\\') {
      result += '\\\\';
    } else if (c === '"') {
      result += '\\"';
    } else if (c === '\n') {
      result += '\\n';
    } else if (c === '\r') {
      result += '\\r';
    } else if (c === '\t') {
      result += '\\t';
    } else if (code < 32 || code > 127) {
      result += '\\u' + code.toString(16).padStart(4, '0');
    } else {
      result += c;
    }
  }
  result += '"';
  return result;
}

/**
 * 生成完整的 HTML 页面
 */
function generateMarkdownHtml(content: string): string {
  const jsContent: string = encodeToJsString(content);
  
  const html: string = `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="${KATEX_CSS}">
    <link rel="stylesheet" href="${HIGHLIGHT_CSS}">
    <script src="${MARKED_JS}"></script>
    <script src="${KATEX_JS}"></script>
    <script src="${KATEX_RENDER_JS}"></script>
    <script src="${HIGHLIGHT_JS}"></script>
    <style>
        html, body { margin: 0; padding: 8px; background: transparent; }
        ${MARKDOWN_CSS}
    </style>
</head>
<body>
    <div id="fallback" class="markdown-body"></div>
    <div id="content" class="markdown-body" style="display:none"></div>
    <script>
    (function() {
        var hljsReady = typeof hljs !== 'undefined';
        var retries = 0;
        var maxRetries = 15;
        var rawContent = ${jsContent};
        var fallbackEl = document.getElementById('fallback');
        var contentEl = document.getElementById('content');

        function renderFallback() {
            fallbackEl.textContent = rawContent;
            fallbackEl.style.display = 'block';
            contentEl.style.display = 'none';
        }

        function renderByMarkedOnly() {
            if (typeof marked === 'undefined') {
              return false;
            }
            try {
                marked.setOptions({ breaks: true, gfm: true });
                var mathBlocks = [];
                var mathInlines = [];
                var idx = 0;
                
                rawContent = rawContent.replace(/\\$\\$([\\s\\S]+?)\\$\\$/g, function(m, f) {
                    mathBlocks[idx] = f;
                    return ' MATHBLOCK' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\\\\[([\\s\\S]+?)\\\\]/g, function(m, f) {
                    mathBlocks[idx] = f;
                    return ' MATHBLOCK' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\\$([^\\$\\n]+?)\\$/g, function(m, f) {
                    mathInlines[idx] = f;
                    return ' MATHINLINE' + (idx++) + 'END ';
                });
                
                rawContent = rawContent.replace(/\\\\((.+?)\\\\)/g, function(m, f) {
                    mathInlines[idx] = f;
                    return ' MATHINLINE' + (idx++) + 'END ';
                });
                
                var parsed = marked.parse(rawContent);
                
                parsed = parsed.replace(/MATHBLOCK(\\d+)END/g, function(m, i) {
                    return '<div class="math-block">$$' + mathBlocks[i] + '$$</div>';
                });
                
                parsed = parsed.replace(/MATHINLINE(\\d+)END/g, function(m, i) {
                    return '$' + mathInlines[i] + '$';
                });
                contentEl.innerHTML = parsed;
                
                if (typeof hljs !== 'undefined') {
                    contentEl.querySelectorAll('pre code').forEach(function(block) {
                        try { hljs.highlightElement(block); } catch(e) {}
                    });
                }

                if (typeof renderMathInElement !== 'undefined') {
                  renderMathInElement(contentEl, {
                      throwOnError: false,
                      strict: 'ignore',
                      ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
                      delimiters: [
                          {left: '$$', right: '$$', display: true},
                          {left: '\\\\[', right: '\\\\]', display: true},
                          {left: '$', right: '$', display: false},
                          {left: '\\\\(', right: '\\\\)', display: false}
                      ]
                  });
                }
                
                contentEl.querySelectorAll('a').forEach(function(link) {
                    link.setAttribute('target', '_blank');
                    link.setAttribute('rel', 'noopener noreferrer');
                });

                fallbackEl.style.display = 'none';
                contentEl.style.display = 'block';
                return true;
            } catch(e) {
                return false;
            }
        }

        function renderContent() {
          if (renderByMarkedOnly()) {
            return;
          }
          retries += 1;
          if (retries >= maxRetries) {
            renderFallback();
            return;
          }
          if (!hljsReady && typeof hljs !== 'undefined') {
            hljsReady = true;
          }
          setTimeout(renderContent, 120);
        }

        renderFallback();
        renderContent();
    })();
    </script>
</body>
</html>`;
  
  return html;
}

/**
 * Markdown 渲染组件
 * 使用 Web 组件渲染 Markdown 内容
 */
@Component
export struct MarkdownWeb {
  @Prop content: string = '';
  @Prop webHeight: number = 120;
  private webController: webview.WebviewController = new webview.WebviewController();
  
  build() {
    Column() {
      Web({ 
        src: 'about:blank',
        controller: this.webController
      })
        .width('100%')
        .height(this.webHeight)
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .mixedMode(MixedMode.All)
        .cacheMode(CacheMode.Default)
        .backgroundColor(Color.Transparent)
        .onControllerAttached(() => {
          // Web 控制器附加后立即加载内容
          const htmlContent = generateMarkdownHtml(this.content);
          this.webController.loadData(htmlContent, 'text/html', 'UTF-8');
        })
    }
    .width('100%')
  }
}
