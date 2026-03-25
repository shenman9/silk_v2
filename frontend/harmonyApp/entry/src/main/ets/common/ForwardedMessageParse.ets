/**
 * 解析聊天里「转发」消息的固定前缀格式（与 Android ChatScreen 一致）
 */

export interface ForwardedMessageParts {
  sourceName: string;
  /** 单条转发时可为原发送者昵称；旧版无此行则为空 */
  senderName: string;
  body: string;
  isBatch: boolean;
}

/** 批量转发正文中按「### 昵称」拆出的一条（与 ChatPage.mergeSelectedMessagesMarkdown 一致） */
export interface BatchForwardItem {
  senderName: string;
  content: string;
}

/**
 * 将批量转发 body 解析为若干条；无法识别 ### 分段时返回空数组（走整段 Markdown 兜底）。
 */
export function parseBatchForwardMarkdownBody(body: string): BatchForwardItem[] {
  const items: BatchForwardItem[] = [];
  const text = body ? body.replace(/\r/g, '').trim() : '';
  if (text.length === 0) {
    return items;
  }
  const segments = text.split(/\n\n(?=###\s+)/);
  for (let i = 0; i < segments.length; i++) {
    const seg = segments[i].trim();
    const m = seg.match(/^###\s+([^\n]+)\n\n([\s\S]*)$/);
    if (m) {
      const name = m[1] ? m[1].trim() : '';
      const inner = m[2] !== undefined && m[2] !== null ? m[2] : '';
      const one: BatchForwardItem = { senderName: name, content: inner };
      items.push(one);
    }
  }
  return items;
}

interface ForwardedSenderStrip {
  senderName: string;
  body: string;
}

function stripOptionalSenderLine(rest: string): ForwardedSenderStrip {
  let senderName = '';
  let body = rest;
  const senderM = body.match(/^👤\s*([^\n]+)\s*\n+/);
  if (senderM) {
    senderName = senderM[1] ? senderM[1].trim() : '';
    body = body.substring(senderM[0].length);
  }
  body = body.replace(/^\s+/, '');
  const out: ForwardedSenderStrip = { senderName: senderName, body: body };
  return out;
}

export function parseForwardedMessageContent(raw: string): ForwardedMessageParts | null {
  const text = raw ? raw : '';
  const singleHead = text.match(/^📨 转发自【([^】]*)】:\s*\n/);
  if (singleHead) {
    const sourceName = singleHead[1] ? singleHead[1].trim() : '';
    let rest = text.substring(singleHead[0].length);
    const stripped = stripOptionalSenderLine(rest);
    return {
      sourceName: sourceName,
      senderName: stripped.senderName,
      body: stripped.body,
      isBatch: false
    };
  }
  const batchHead = text.match(/^📨 批量转发自【([^】]*)】:\s*\n/);
  if (batchHead) {
    const sourceName = batchHead[1] ? batchHead[1].trim() : '';
    let rest = text.substring(batchHead[0].length);
    const stripped = stripOptionalSenderLine(rest);
    return {
      sourceName: sourceName,
      senderName: stripped.senderName,
      body: stripped.body,
      isBatch: true
    };
  }
  return null;
}
