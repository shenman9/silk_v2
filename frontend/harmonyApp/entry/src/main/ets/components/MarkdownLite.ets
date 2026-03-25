/**
 * 轻量 Markdown 渲染组件（Harmony 稳定版）
 * 纯 ArkUI 渲染，避免 WebView 带来的闪退与加载不确定性。
 */
import { Theme } from '../common/Theme';
import { MathKatexWeb } from './MathKatexWeb';

interface MarkdownBlock {
  kind: string; // heading | bullet | ordered | quote | code | divider | text | empty | task | table | table_grid | math
  text: string;
  level: number;
  marker: string;
  tableRow?: string[];
  tableData?: string[][];
}

interface CodeToken {
  text: string;
  cat: string; // keyword | string | comment | number | normal | ws
}

const CODE_KW_JS: string[] = [
  'break', 'case', 'catch', 'class', 'const', 'continue', 'debugger', 'default', 'delete', 'do',
  'else', 'export', 'extends', 'finally', 'for', 'function', 'if', 'import', 'in', 'instanceof',
  'let', 'new', 'return', 'super', 'switch', 'this', 'throw', 'try', 'typeof', 'var', 'void',
  'while', 'with', 'yield', 'await', 'async', 'from', 'as', 'of', 'get', 'set',
  'interface', 'type', 'implements', 'package', 'private', 'protected', 'public', 'static',
  'abstract', 'enum', 'true', 'false', 'null', 'undefined', 'readonly', 'declare', 'namespace',
  'module', 'require', 'symbol', 'bigint'
];

const CODE_KW_KOTLIN: string[] = [
  'abstract', 'annotation', 'as', 'break', 'class', 'continue', 'do', 'else', 'enum', 'false',
  'final', 'for', 'fun', 'if', 'import', 'in', 'inner', 'interface', 'internal', 'is', 'lateinit',
  'object', 'open', 'operator', 'out', 'override', 'package', 'private', 'protected', 'public',
  'return', 'sealed', 'super', 'suspend', 'this', 'throw', 'true', 'try', 'typealias', 'typeof',
  'val', 'var', 'when', 'where', 'while', 'companion', 'constructor', 'data', 'init', 'by', 'crossinline',
  'dynamic', 'field', 'file', 'get', 'param', 'property', 'receiver', 'set', 'setparam', 'value',
  'vararg', 'noreturn', 'tailrec', 'inline', 'external', 'expect', 'actual'
];

const CODE_KW_PYTHON: string[] = [
  'and', 'as', 'assert', 'async', 'await', 'break', 'class', 'continue', 'def', 'del', 'elif',
  'else', 'except', 'False', 'finally', 'for', 'from', 'global', 'if', 'import', 'in', 'is',
  'lambda', 'None', 'nonlocal', 'not', 'or', 'pass', 'raise', 'return', 'True', 'try', 'while',
  'with', 'yield', 'print', 'range', 'len', 'self'
];

const CODE_KW_SQL: string[] = [
  'select', 'from', 'where', 'join', 'inner', 'left', 'right', 'outer', 'on', 'and', 'or', 'not',
  'insert', 'into', 'values', 'update', 'set', 'delete', 'create', 'table', 'alter', 'drop',
  'index', 'view', 'as', 'order', 'by', 'group', 'having', 'limit', 'offset', 'distinct', 'case',
  'when', 'then', 'else', 'end', 'null', 'is', 'between', 'like', 'in', 'exists', 'primary', 'key',
  'foreign', 'references', 'constraint', 'default', 'unique', 'check', 'cursor', 'transaction',
  'commit', 'rollback', 'grant', 'revoke'
];

function buildKeywordRecord(words: string[]): Record<string, boolean> {
  const rec: Record<string, boolean> = {};
  for (let i = 0; i < words.length; i++) {
    rec[words[i]] = true;
    rec[words[i].toLowerCase()] = true;
  }
  return rec;
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
    let codeLang = '';
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
          blocks.push({ kind: 'code', text: codeBuffer, level: 0, marker: codeLang });
          codeBuffer = '';
          codeLang = '';
          inCodeBlock = false;
        } else {
          const fence = trimmed.match(/^```\s*([\w+#.-]*)?\s*$/);
          codeLang = fence && fence[1] ? fence[1].trim().toLowerCase() : '';
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
        const inner = tableMatch[1];
        const parts = inner.split('|');
        const cells: string[] = [];
        for (let pi = 0; pi < parts.length; pi++) {
          cells.push(parts[pi].trim());
        }
        blocks.push({
          kind: 'table',
          text: cells.join('\t'),
          level: 0,
          marker: '',
          tableRow: cells
        });
        continue;
      }

      blocks.push({ kind: 'text', text: this.cleanInlineMarkdown(line), level: 0, marker: '' });
    }

    if (inCodeBlock && codeBuffer.length > 0) {
      blocks.push({ kind: 'code', text: codeBuffer, level: 0, marker: codeLang });
    }
    if (inMathBlock && mathBuffer.length > 0) {
      blocks.push({ kind: 'math', text: mathBuffer, level: 0, marker: '' });
    }

    return this.mergeTableBlocks(blocks);
  }

  private mergeTableBlocks(blocks: MarkdownBlock[]): MarkdownBlock[] {
    const result: MarkdownBlock[] = [];
    let i = 0;
    while (i < blocks.length) {
      const b = blocks[i];
      if (b.kind !== 'table') {
        result.push(b);
        i++;
        continue;
      }
      const grid: string[][] = [];
      while (i < blocks.length && blocks[i].kind === 'table') {
        const row = blocks[i].tableRow;
        if (row && this.isMarkdownTableSeparatorRow(row)) {
          i++;
          continue;
        }
        if (row && row.length > 0) {
          grid.push(row);
        }
        i++;
      }
      if (grid.length > 0) {
        result.push({
          kind: 'table_grid',
          text: '',
          level: 0,
          marker: '',
          tableData: grid
        });
      }
    }
    return result;
  }

  private isMarkdownTableSeparatorRow(cells: string[]): boolean {
    if (cells.length === 0) {
      return false;
    }
    for (let ci = 0; ci < cells.length; ci++) {
      const c = cells[ci].replace(/\s/g, '');
      if (!/^:?-{3,}:?$/.test(c)) {
        return false;
      }
    }
    return true;
  }

  private codeKeywordRecord(lang: string): Record<string, boolean> {
    const l = lang ? lang.toLowerCase() : '';
    if (l === 'kotlin' || l === 'kt' || l === 'kts') {
      return buildKeywordRecord(CODE_KW_KOTLIN);
    }
    if (l === 'python' || l === 'py') {
      return buildKeywordRecord(CODE_KW_PYTHON);
    }
    if (l === 'sql' || l === 'sqlite' || l === 'mysql' || l === 'pgsql') {
      return buildKeywordRecord(CODE_KW_SQL);
    }
    return buildKeywordRecord(CODE_KW_JS);
  }

  private mergeCodeTokens(tokens: CodeToken[]): CodeToken[] {
    if (tokens.length <= 1) {
      return tokens;
    }
    const out: CodeToken[] = [];
    let cur = tokens[0];
    for (let ti = 1; ti < tokens.length; ti++) {
      if (tokens[ti].cat === cur.cat) {
        cur = { text: cur.text + tokens[ti].text, cat: cur.cat };
      } else {
        out.push(cur);
        cur = tokens[ti];
      }
    }
    out.push(cur);
    return out;
  }

  private tokenizeCodeLine(line: string, kw: Record<string, boolean>): CodeToken[] {
    const tokens: CodeToken[] = [];
    let i = 0;
    const n = line.length;

    const pushCat = (start: number, end: number, cat: string): void => {
      if (end > start) {
        tokens.push({ text: line.substring(start, end), cat: cat });
      }
    };

    while (i < n) {
      const c = line[i];
      if (c === ' ' || c === '\t') {
        let j = i + 1;
        while (j < n && (line[j] === ' ' || line[j] === '\t')) {
          j++;
        }
        pushCat(i, j, 'ws');
        i = j;
        continue;
      }
      if (i + 1 < n && line[i] === '/' && line[i + 1] === '/') {
        pushCat(i, n, 'comment');
        break;
      }
      if (c === '#') {
        pushCat(i, n, 'comment');
        break;
      }
      if (c === '"') {
        let j = i + 1;
        while (j < n && line[j] !== '"') {
          if (line[j] === '\\' && j + 1 < n) {
            j += 2;
          } else {
            j++;
          }
        }
        if (j < n) {
          j++;
        }
        pushCat(i, j, 'string');
        i = j;
        continue;
      }
      if (c === "'") {
        let j = i + 1;
        while (j < n && line[j] !== "'") {
          if (line[j] === '\\' && j + 1 < n) {
            j += 2;
          } else {
            j++;
          }
        }
        if (j < n) {
          j++;
        }
        pushCat(i, j, 'string');
        i = j;
        continue;
      }
      if (c === '`') {
        let j = i + 1;
        while (j < n && line[j] !== '`') {
          j++;
        }
        if (j < n) {
          j++;
        }
        pushCat(i, j, 'string');
        i = j;
        continue;
      }
      if (c >= '0' && c <= '9') {
        let j = i + 1;
        while (j < n && /[0-9._xXa-fA-FnulL]/.test(line[j])) {
          j++;
        }
        pushCat(i, j, 'number');
        i = j;
        continue;
      }
      if (/[A-Za-z_$]/.test(c)) {
        let j = i + 1;
        while (j < n && /[A-Za-z0-9_$]/.test(line[j])) {
          j++;
        }
        const word = line.substring(i, j);
        const cat = kw[word] === true ? 'keyword' : 'normal';
        pushCat(i, j, cat);
        i = j;
        continue;
      }
      pushCat(i, i + 1, 'normal');
      i++;
    }
    if (tokens.length === 0) {
      tokens.push({ text: '', cat: 'normal' });
    }
    return this.mergeCodeTokens(tokens);
  }

  private codeTokenColor(cat: string): string {
    if (cat === 'keyword') {
      return '#C792EA';
    }
    if (cat === 'string') {
      return '#C3E88D';
    }
    if (cat === 'comment') {
      return '#637777';
    }
    if (cat === 'number') {
      return '#F78C6C';
    }
    if (cat === 'ws') {
      return '#BFC7D5';
    }
    return '#E8EDF4';
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
      Column() {
        ForEach(block.text.split('\n'), (line: string, lineIdx?: number) => {
          Text() {
            ForEach(this.tokenizeCodeLine(line, this.codeKeywordRecord(block.marker)), (tok: CodeToken, tIdx?: number) => {
              Span(tok.text)
                .fontSize(13)
                .fontFamily('monospace')
                .fontColor(this.codeTokenColor(tok.cat))
            }, (tok: CodeToken, tIdx?: number) => {
              const li = lineIdx !== undefined ? lineIdx : 0;
              const ti = tIdx !== undefined ? tIdx : 0;
              return `sp_${li}_${ti}_${tok.text.length}`;
            })
          }
          .width('100%')
          .lineHeight(20)
        }, (line: string, lineIdx?: number) => `ln_${lineIdx !== undefined ? lineIdx : 0}`)
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
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
    } else if (block.kind === 'table_grid') {
      Column() {
        ForEach(block.tableData ? block.tableData : [], (row: string[], rIdx?: number) => {
          Row() {
            ForEach(row, (cell: string, cIdx?: number) => {
              Text(cell.length > 0 ? cell : ' ')
                .layoutWeight(1)
                .fontSize(12)
                .fontColor((rIdx !== undefined && rIdx === 0) ? '#1E3A5F' : '#334155')
                .fontWeight((rIdx !== undefined && rIdx === 0) ? FontWeight.Medium : FontWeight.Normal)
                .lineHeight(18)
                .padding({ left: 10, right: 10, top: 8, bottom: 8 })
                .backgroundColor(
                  (rIdx !== undefined && rIdx === 0) ? '#EFF6FF' :
                    ((rIdx !== undefined && rIdx % 2 === 1) ? '#F8FAFC' : '#FFFFFF')
                )
                .border({
                  width: { right: 1, bottom: 1 },
                  color: '#E2E8F0'
                })
            }, (cell: string, cIdx?: number) => {
              const ri = rIdx !== undefined ? rIdx : 0;
              const ci = cIdx !== undefined ? cIdx : 0;
              return `cell_${ri}_${ci}`;
            })
          }
          .width('100%')
        }, (row: string[], rIdx?: number) => `row_${rIdx !== undefined ? rIdx : 0}`)
      }
      .width('100%')
      .border({ width: 1, color: '#CBD5E1' })
      .borderRadius(8)
      .clip(true)
      .margin({ top: 4, bottom: 4 });
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
        if (block.kind === 'table_grid') {
          const td = block.tableData ? block.tableData : [];
          return `tg_${idx}_${td.length}`;
        }
        return `${block.kind}_${idx}`;
      });
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
