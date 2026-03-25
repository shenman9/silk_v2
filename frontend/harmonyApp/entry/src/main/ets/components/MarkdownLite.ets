/**
 * 轻量 Markdown 渲染组件（Harmony 稳定版）
 * 纯 ArkUI 渲染，避免 WebView 带来的闪退与加载不确定性。
 */
import { Theme } from '../common/Theme';

interface MarkdownBlock {
  kind: string; // heading | bullet | ordered | quote | code | divider | text | empty | task | table
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

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const trimmed = line.trim();
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

    return blocks;
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
    return cleaned;
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
      }, (block: MarkdownBlock, index?: number) => `${block.kind}_${index !== undefined ? index : 0}`);
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
