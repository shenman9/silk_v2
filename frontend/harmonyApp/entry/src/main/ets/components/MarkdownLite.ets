/**
 * 轻量 Markdown 渲染组件（Harmony 稳定版）
 * 纯 ArkUI 渲染，避免 WebView 带来的闪退与加载不确定性。
 */
import { Theme } from '../common/Theme';
import { MathKatexWeb } from './MathKatexWeb';

interface MarkdownBlock {
  kind: string; // heading | bullet | ordered | quote | code | divider | text | empty | task | table | math
  text: string;
  level: number;
  marker: string;
}

@Component
export struct MarkdownLite {
  @Prop content: string = '';

  private getBlocks(): MarkdownBlock[] {
    const source = this.content ? this.content.replace(/\r/g, '') : '';
    const lines = source.split('\n');
    const blocks: MarkdownBlock[] = [];
    let inCodeBlock = false;
    let codeBuffer = '';
    let inMathBlock = false;
    let mathBuffer = '';

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const trimmed = line.trim();
      if (trimmed === '$$' || trimmed === '\\[') {
        if (inMathBlock) {
          blocks.push({ kind: 'math', text: mathBuffer, level: 0, marker: '' });
          mathBuffer = '';
          inMathBlock = false;
        } else {
          inMathBlock = true;
        }
        continue;
      }
      if (trimmed === '\\]') {
        if (inMathBlock) {
          blocks.push({ kind: 'math', text: mathBuffer, level: 0, marker: '' });
          mathBuffer = '';
          inMathBlock = false;
        }
        continue;
      }
      if (trimmed.startsWith('```')) {
        if (inCodeBlock) {
          blocks.push({ kind: 'code', text: codeBuffer, level: 0, marker: '' });
          codeBuffer = '';
          inCodeBlock = false;
        } else {
          inCodeBlock = true;
        }
        continue;
      }

      if (inCodeBlock) {
        if (codeBuffer.length > 0) {
          codeBuffer += '\n';
        }
        codeBuffer += line;
        continue;
      }

      if (inMathBlock) {
        if (mathBuffer.length > 0) {
          mathBuffer += '\n';
        }
        mathBuffer += line;
        continue;
      }

      const singleLineMath = this.extractSingleLineMath(trimmed);
      if (singleLineMath.length > 0) {
        blocks.push({ kind: 'math', text: singleLineMath, level: 0, marker: '' });
        continue;
      }

      if (trimmed.length === 0) {
        blocks.push({ kind: 'empty', text: '', level: 0, marker: '' });
        continue;
      }

      const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
      if (headingMatch) {
        const marks = headingMatch[1];
        const title = headingMatch[2];
        blocks.push({ kind: 'heading', text: this.cleanInlineMarkdown(title), level: marks.length, marker: '' });
        continue;
      }

      if (trimmed.match(/^(-{3,}|\*{3,}|_{3,})$/)) {
        blocks.push({ kind: 'divider', text: '', level: 0, marker: '' });
        continue;
      }

      const quoteMatch = trimmed.match(/^((>\s*)+)(.*)$/);
      if (quoteMatch) {
        const prefix = quoteMatch[1] ? quoteMatch[1] : '>';
        const quoteLevel = prefix.replace(/[^>]/g, '').length;
        const quoteText = quoteMatch[3] ? quoteMatch[3].trim() : '';
        const normalizedLevel = quoteLevel > 0 ? quoteLevel : 1;
        const cleanedQuote = this.cleanInlineMarkdown(quoteText);
        const lastIndex = blocks.length - 1;
        if (lastIndex >= 0 && blocks[lastIndex].kind === 'quote') {
          const lastBlock = blocks[lastIndex];
          if (cleanedQuote.length > 0) {
            if (lastBlock.text.length > 0) {
              lastBlock.text += '\n';
            }
            lastBlock.text += cleanedQuote;
          }
          if (normalizedLevel > lastBlock.level) {
            lastBlock.level = normalizedLevel;
          }
        } else {
          blocks.push({
            kind: 'quote',
            text: cleanedQuote,
            level: normalizedLevel,
            marker: ''
          });
        }
        continue;
      }

      const taskMatch = trimmed.match(/^[-*+]\s+\[([ xX])\]\s+(.+)$/);
      if (taskMatch) {
        const checked = taskMatch[1].toLowerCase() === 'x';
        const itemText = taskMatch[2];
        blocks.push({
          kind: 'task',
          text: this.cleanInlineMarkdown(itemText),
          level: 0,
          marker: checked ? '☑' : '☐'
        });
        continue;
      }

      const bulletMatch = trimmed.match(/^[-*+]\s+(.+)$/);
      if (bulletMatch) {
        const itemText = bulletMatch[1];
        blocks.push({ kind: 'bullet', text: this.cleanInlineMarkdown(itemText), level: 0, marker: '•' });
        continue;
      }

      const orderedMatch = trimmed.match(/^(\d+)\.\s+(.+)$/);
      if (orderedMatch) {
        blocks.push({
          kind: 'ordered',
          text: this.cleanInlineMarkdown(orderedMatch[2]),
          level: 0,
          marker: `${orderedMatch[1]}.`
        });
        continue;
      }

      const tableMatch = trimmed.match(/^\|(.+)\|$/);
      if (tableMatch) {
        const row = tableMatch[1].split('|').map((part: string) => part.trim()).join(' | ');
        blocks.push({ kind: 'table', text: row, level: 0, marker: '' });
        continue;
      }

      blocks.push({ kind: 'text', text: this.cleanInlineMarkdown(line), level: 0, marker: '' });
    }

    if (inCodeBlock && codeBuffer.length > 0) {
      blocks.push({ kind: 'code', text: codeBuffer, level: 0, marker: '' });
    }
    if (inMathBlock && mathBuffer.length > 0) {
      blocks.push({ kind: 'math', text: mathBuffer, level: 0, marker: '' });
    }

    return blocks;
  }

  private extractSingleLineMath(trimmed: string): string {
    const doubleDollar = trimmed.match(/^\$\$(.+)\$\$$/);
    if (doubleDollar) {
      return doubleDollar[1].trim();
    }
    const bracketMath = trimmed.match(/^\\\[(.+)\\\]$/);
    if (bracketMath) {
      return bracketMath[1].trim();
    }
    return '';
  }

  private normalizeMathExpression(source: string): string {
    let text = source ? source.trim() : '';
    const original = text;
    if (text.length === 0) {
      return '';
    }
    text = text.replace(/\\left/g, '').replace(/\\right/g, '');
    text = text.replace(/\\,/g, ' ').replace(/\\;/g, ' ').replace(/\\!/g, '');
    text = text.replace(/\\quad/g, ' ').replace(/\\qquad/g, '  ');
    text = text.replace(/\\times/g, '×').replace(/\\cdot/g, '·').replace(/\\div/g, '÷');
    text = text.replace(/\\pm/g, '±').replace(/\\mp/g, '∓');
    text = text.replace(/\\leq/g, '≤').replace(/\\geq/g, '≥').replace(/\\neq/g, '≠');
    text = text.replace(/\\approx/g, '≈').replace(/\\infty/g, '∞');
    text = text.replace(/\\to/g, '→').replace(/\\rightarrow/g, '→').replace(/\\leftarrow/g, '←');
    text = text.replace(/\\leftrightarrow/g, '↔').replace(/\\Rightarrow/g, '⇒').replace(/\\Leftarrow/g, '⇐');
    text = text.replace(/\\alpha/g, 'α').replace(/\\beta/g, 'β').replace(/\\gamma/g, 'γ');
    text = text.replace(/\\delta/g, 'δ').replace(/\\theta/g, 'θ').replace(/\\lambda/g, 'λ');
    text = text.replace(/\\mu/g, 'μ').replace(/\\pi/g, 'π').replace(/\\sigma/g, 'σ').replace(/\\omega/g, 'ω');
    text = text.replace(/\\sum/g, '∑').replace(/\\prod/g, '∏').replace(/\\int/g, '∫');
    text = text.replace(/\\sin/g, 'sin').replace(/\\cos/g, 'cos').replace(/\\tan/g, 'tan');
    text = text.replace(/\\log/g, 'log').replace(/\\ln/g, 'ln').replace(/\\exp/g, 'exp');
    text = text.replace(/\\lim/g, 'lim').replace(/\\max/g, 'max').replace(/\\min/g, 'min');
    text = text.replace(/\\sqrt\{([^{}]+)\}/g, '√($1)');
    // 迭代处理简单分式，避免无限循环
    for (let i = 0; i < 8; i++) {
      const next = text.replace(/\\frac\{([^{}]+)\}\{([^{}]+)\}/g, '($1)/($2)');
      if (next === text) {
        break;
      }
      text = next;
    }
    text = text.replace(/\^\{([^{}]+)\}/g, '^($1)');
    text = text.replace(/_\{([^{}]+)\}/g, '_($1)');
    text = text.replace(/([A-Za-z0-9\)\]])\^([A-Za-z0-9+\-=/])/g, (_m: string, a: string, b: string) => `${a}${this.toSuperScript(b)}`);
    text = text.replace(/([A-Za-z0-9\)\]])_([A-Za-z0-9+\-=])/g, (_m: string, a: string, b: string) => `${a}${this.toSubScript(b)}`);
    text = text.replace(/\\/g, '');
    text = text.replace(/\{([^{}]+)\}/g, '($1)');
    text = text.replace(/\s+/g, ' ').trim();
    if (text.length === 0) {
      return original;
    }
    return text;
  }

  private toSuperScript(value: string): string {
    const map: Record<string, string> = {
      '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴',
      '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹',
      '+': '⁺', '-': '⁻', '=': '⁼', '(': '⁽', ')': '⁾',
      'n': 'ⁿ', 'i': 'ⁱ'
    };
    const converted = map[value];
    return converted ? converted : `^(${value})`;
  }

  private toSubScript(value: string): string {
    const map: Record<string, string> = {
      '0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄',
      '5': '₅', '6': '₆', '7': '₇', '8': '₈', '9': '₉',
      '+': '₊', '-': '₋', '=': '₌', '(': '₍', ')': '₎'
    };
    const converted = map[value];
    return converted ? converted : `_(${value})`;
  }

  private getRenderedMathText(raw: string): string {
    const normalized = this.normalizeMathExpression(raw);
    if (normalized.length > 0) {
      return normalized;
    }
    const fallback = raw ? raw.trim() : '';
    return fallback.length > 0 ? fallback : '[公式]';
  }

  private cleanInlineMarkdown(text: string): string {
    let cleaned = text ? text : '';
    cleaned = cleaned.replace(/!\[([^\]]*)\]\([^)]+\)/g, '[图片]');
    cleaned = cleaned.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '$1 ($2)');
    cleaned = cleaned.replace(/\*\*(.*?)\*\*/g, '$1');
    cleaned = cleaned.replace(/__(.*?)__/g, '$1');
    cleaned = cleaned.replace(/\*(.*?)\*/g, '$1');
    cleaned = cleaned.replace(/_(.*?)_/g, '$1');
    cleaned = cleaned.replace(/`([^`]+)`/g, '$1');
    cleaned = cleaned.replace(/\$([^$\n]+)\$/g, (_match: string, expr: string) => `⟮${this.normalizeMathExpression(expr)}⟯`);
    cleaned = cleaned.replace(/\\\(([^)]+)\\\)/g, (_match: string, expr: string) => `⟮${this.normalizeMathExpression(expr)}⟯`);
    return cleaned;
  }

  private estimateMathWebHeight(latex: string): number {
    const body = latex ? latex : '';
    const lines = body.split('\n').length;
    const base = 48 + lines * 20;
    const byLen = Math.floor(body.length * 0.42);
    const h = base + byLen;
    return h > 380 ? 380 : (h < 72 ? 72 : h);
  }

  private headingFontSize(level: number): number {
    if (level <= 1) {
      return 19;
    }
    if (level === 2) {
      return 17;
    }
    if (level === 3) {
      return 16;
    }
    return 15;
  }

  private quoteLeftPadding(level: number): number {
    if (level <= 1) {
      return 2;
    }
    const value = 2 + (level - 1) * 2;
    return value > 8 ? 8 : value;
  }

  private quotePrefix(level: number): string {
    if (level <= 1) {
      return '> ';
    }
    if (level === 2) {
      return '>> ';
    }
    return '>>> ';
  }

  @Builder
  private renderBlock(block: MarkdownBlock) {
    if (block.kind === 'empty') {
      Blank().height(8);
    } else if (block.kind === 'divider') {
      Row()
        .width('100%')
        .height(1)
        .backgroundColor('#22000000')
        .margin({ top: 6, bottom: 6 });
    } else if (block.kind === 'heading') {
      Text(block.text)
        .fontSize(this.headingFontSize(block.level))
        .fontWeight(FontWeight.Medium)
        .fontColor(Theme.colors.textPrimary)
        .lineHeight(24)
        .margin({ top: 4, bottom: 2 });
    } else if (block.kind === 'quote') {
      Text(`${this.quotePrefix(block.level)}${block.text}`)
        .width('100%')
        .fontSize(13)
        .fontColor('#6B7280')
        .lineHeight(18)
        .padding({
          left: this.quoteLeftPadding(block.level),
          right: 2,
          top: 1,
          bottom: 1
        })
        .margin({ top: 0, bottom: 0 });
    } else if (block.kind === 'code') {
      Text(block.text)
        .width('100%')
        .fontSize(13)
        .fontColor('#E8EDF4')
        .fontFamily('monospace')
        .lineHeight(20)
        .padding(10)
        .backgroundColor('#1F2937')
        .borderRadius(8)
        .margin({ top: 2, bottom: 2 });
    } else if (block.kind === 'math') {
      MathKatexWeb({
        latex: block.text ? block.text : '',
        webHeight: this.estimateMathWebHeight(block.text ? block.text : '')
      })
        .width('100%')
        .borderRadius(8)
        .clip(true)
        .margin({ top: 3, bottom: 3 });
    } else if (block.kind === 'bullet') {
      Row() {
        Text(`${block.marker} `)
          .fontSize(14)
          .fontColor('#BFA16A')
          .margin({ top: 1 });
        Text(block.text)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .lineHeight(22)
      }
      .width('100%')
      .margin({ top: 1, bottom: 1 });
    } else if (block.kind === 'task') {
      Row() {
        Text(`${block.marker} `)
          .fontSize(14)
          .fontColor(block.marker === '☑' ? '#4D9A63' : Theme.colors.textLight)
          .margin({ top: 1 });
        Text(block.text)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .lineHeight(22)
      }
      .width('100%')
      .margin({ top: 1, bottom: 1 });
    } else if (block.kind === 'ordered') {
      Row() {
        Text(`${block.marker} `)
          .fontSize(14)
          .fontColor('#BFA16A')
          .margin({ top: 1 });
        Text(block.text)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(Theme.colors.textPrimary)
          .lineHeight(22)
      }
      .width('100%')
      .margin({ top: 1, bottom: 1 });
    } else if (block.kind === 'table') {
      Text(block.text)
        .width('100%')
        .fontSize(13)
        .fontColor('#334155')
        .fontFamily('monospace')
        .lineHeight(20)
        .padding({ left: 8, right: 8, top: 6, bottom: 6 })
        .backgroundColor('#EEF2F7')
        .borderRadius(6)
        .margin({ top: 1, bottom: 1 });
    } else {
      Text(block.text)
        .fontSize(14)
        .fontColor(Theme.colors.textPrimary)
        .lineHeight(22)
        .margin({ top: 1, bottom: 1 });
    }
  }

  build() {
    Column() {
      ForEach(this.getBlocks(), (block: MarkdownBlock, index?: number) => {
        this.renderBlock(block);
      }, (block: MarkdownBlock, index?: number) => {
        const idx = index !== undefined ? index : 0;
        if (block.kind === 'math') {
          return `math_${idx}_${block.text}`;
        }
        return `${block.kind}_${idx}`;
      });
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
