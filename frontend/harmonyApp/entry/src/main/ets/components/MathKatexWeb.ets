/**
 * 离线 KaTeX：仅渲染公式块，本地 rawfile 资源，失败时 HTML 内保留文本兜底（不空白）
 */
import { webview } from '@kit.ArkWeb';

function encodeToJsString(s: string): string {
  const src = s ? s : '';
  let result: string = '"';
  for (let i = 0; i < src.length; i++) {
    const c: string = src[i];
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

@Component
export struct MathKatexWeb {
  @Prop latex: string = '';
  @Prop webHeight: number = 88;
  private controller: webview.WebviewController = new webview.WebviewController();

  build() {
    Web({
      src: $rawfile('katex/katex_shell.html'),
      controller: this.controller
    })
      .width('100%')
      .height(this.webHeight)
      .javaScriptAccess(true)
      .domStorageAccess(false)
      .mixedMode(MixedMode.All)
      .cacheMode(CacheMode.Default)
      .backgroundColor('#EEF4FF')
      .onPageEnd(() => {
        const script = `try{start(${encodeToJsString(this.latex)});}catch(e){}`;
        this.controller.runJavaScript(script).catch((): void => {
        });
      });
  }
}
