/**
 * ChatPage - 聊天页面
 * 使用 ChatStore + WebSocket 进行实时聊天
 */
import { Message, MessageType } from '../models/Message';
import { UserTodoItem } from '../models/UserTodoItem';
import { Group } from '../models/Group';
import { MessageBubble } from '../components';
import { chatStore as globalChatStore, ConnectionState } from '../stores/ChatStore';
import { appStore as globalAppStore } from '../stores/AppStore';
import { SilkColors } from '../common/Theme';
import { parseForwardedMessageContent, ForwardedMessageParts } from '../common/ForwardedMessageParse';
import { router as appRouter } from '../common/Router';
import { apiClient as globalApiClient } from '../api/ApiClient';
import pasteboard from '@ohos.pasteboard';
import promptAction from '@ohos.promptAction';
import { AlarmService, AlarmResult } from '../common/AlarmService';
import { parseAlarmTimeFromText } from '../common/TodoTimeParser';

@Component
export struct ChatPage {
  @State private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  @State private messageInput: string = '';
  @State private groupName: string = '聊天室';
  @State private messages: Message[] = [];
  @State private showBottomSheet: boolean = false;
  @State private bottomSheetMode: string = 'actions';
  @State private forwardTargets: Group[] = [];
  @State private forwardingContent: string = '';
  @State private isForwarding: boolean = false;
  @State private isLoadingForwardTargets: boolean = false;
  @State private forwardingError: string = '';
  @State private messageStatusMap: Record<string, string> = {};
  @State private selectedActionMessage: Message | null = null;
  @State private isSelectionMode: boolean = false;
  @State private selectedMessageIds: string[] = [];
  @State private isRecalling: boolean = false;
  @State private todoItems: UserTodoItem[] = [];
  @State private todoRefreshing: boolean = false;
  @State private executingTodoIds: Record<string, boolean> = {};

  private groupId: string = 'default_room';
  private userId: string = '';
  private userName: string = '';
  private isInitialized: boolean = false;
  private readonly apiClient = globalApiClient;
  private listScroller: Scroller = new Scroller();
  private didInitialScroll: boolean = false;
  private lastSyncedMessageCount: number = 0;

  aboutToAppear(): void {
    if (this.isInitialized) {
      return;
    }
    this.isInitialized = true;
    setTimeout((): void => {
      this.initChat();
    }, 50);
  }

  aboutToDisappear(): void {
    globalChatStore.onMessagesChanged = null;
    globalChatStore.onConnectionStateChanged = null;
    globalChatStore.onError = null;
    globalChatStore.disconnect();
    this.isInitialized = false;
  }

  private initChat(): void {
    const storedUserId = AppStorage.get<string>('currentUserId');
    const storedUserName = AppStorage.get<string>('currentUserName');
    const storedGroupId = AppStorage.get<string>('currentGroupId');
    const storedGroupName = AppStorage.get<string>('currentGroupName');

    const currentUser = globalAppStore.currentUser;
    const currentGroup = globalAppStore.currentGroup;

    this.userId = storedUserId ? storedUserId : (currentUser ? currentUser.id : '');
    this.userName = storedUserName ? storedUserName : (currentUser ? currentUser.name : '');
    this.groupId = storedGroupId ? storedGroupId : (currentGroup ? currentGroup.id : 'default_room');
    this.groupName = storedGroupName ? storedGroupName : (currentGroup ? currentGroup.name : '聊天室');

    if (this.userId === '') {
      appRouter.goToLogin();
      return;
    }

    AppStorage.setOrCreate<string>('currentUserId', this.userId);
    AppStorage.setOrCreate<string>('currentUserName', this.userName);
    AppStorage.setOrCreate<string>('currentGroupId', this.groupId);
    AppStorage.setOrCreate<string>('currentGroupName', this.groupName);

    this.didInitialScroll = false;
    this.lastSyncedMessageCount = 0;

    globalChatStore.onMessagesChanged = (): void => {
      this.syncMessages();
    };
    globalChatStore.onConnectionStateChanged = (): void => {
      this.connectionState = globalChatStore.connectionState;
      if (this.connectionState === ConnectionState.DISCONNECTED) {
        this.markAllSendingMessagesFailed();
      }
    };
    globalChatStore.onError = (error: string): void => {
      console.error(`聊天连接错误: ${error}`);
      this.showToast(error);
    };

    globalChatStore.clearMessages();
    this.connectionState = ConnectionState.CONNECTING;
    globalChatStore.connect(this.userId, this.userName, this.groupId);

    if (this.isSilkPrivateChat()) {
      setTimeout((): void => {
        void this.quietRefreshTodos();
      }, 1000);
    }
  }

  private syncMessages(): void {
    const source: Message[] = globalChatStore.messages;
    const result: Message[] = [];
    const confirmedOwnIds: string[] = [];
    for (let i = 0; i < source.length; i++) {
      const item = source[i];
      result.push(item);
      if (item.userId === this.userId) {
        confirmedOwnIds.push(item.id);
      }
    }

    const statusMessages = globalChatStore.statusMessages;
    for (let i = 0; i < statusMessages.length; i++) {
      result.push(statusMessages[i]);
    }

    const transient = globalChatStore.transientMessage;
    if (transient) {
      result.push(transient);
    }

    const prevCount = this.lastSyncedMessageCount;
    this.messages = result;
    this.markMessagesAsSent(confirmedOwnIds);
    const newCount = result.length;
    const shouldScrollToEnd = newCount > 0 && (!this.didInitialScroll || newCount > prevCount);
    this.lastSyncedMessageCount = newCount;
    if (newCount > 0 && !this.didInitialScroll) {
      this.didInitialScroll = true;
    }
    if (shouldScrollToEnd) {
      this.scheduleScrollToEnd();
    }
  }

  private scheduleScrollToEnd(): void {
    setTimeout((): void => {
      if (this.messages.length === 0) {
        return;
      }
      const lastIndex = this.messages.length - 1;
      this.listScroller.scrollToIndex(lastIndex, false, ScrollAlign.END);
    }, 80);
  }

  /** 长内容收起后高度变小，把列表锚定到该条，避免视口漂到别条或列表底部 */
  private scrollListToMessageId(messageId: string): void {
    let idx = -1;
    for (let i = 0; i < this.messages.length; i++) {
      if (this.messages[i].id === messageId) {
        idx = i;
        break;
      }
    }
    if (idx < 0) {
      return;
    }
    setTimeout((): void => {
      this.listScroller.scrollToIndex(idx, false, ScrollAlign.START);
    }, 80);
  }

  private handleSend(message: string): void {
    const content = message.trim();
    if (content === '') {
      return;
    }
    const sentMessage = globalChatStore.sendMessage(content);
    if (!sentMessage) {
      return;
    }
    this.setMessageStatus(sentMessage.id, 'sending');
    // Harmony 端目前无独立 ACK 事件，短延时后切换为已发送，断线时会转失败
    setTimeout(() => {
      if (this.getMessageStatus(sentMessage.id) === 'sending') {
        this.setMessageStatus(sentMessage.id, 'sent');
      }
    }, 1200);

  }

  private getMessageStatus(messageId: string): string {
    const value = this.messageStatusMap[messageId];
    return value ? value : 'sent';
  }

  private setMessageStatus(messageId: string, status: string): void {
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }
    next[messageId] = status;
    this.messageStatusMap = next;
  }

  private markAllSendingMessagesFailed(): void {
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }
    let changed = false;
    const nextKeys: string[] = Object.keys(next);
    for (let i = 0; i < nextKeys.length; i++) {
      const id = nextKeys[i];
      if (next[id] === 'sending') {
        next[id] = 'failed';
        changed = true;
      }
    }
    if (changed) {
      this.messageStatusMap = next;
    }
  }

  private retryMessage(message: Message): void {
    const ok = globalChatStore.retryMessage(message);
    if (!ok) {
      this.setMessageStatus(message.id, 'failed');
      return;
    }
    this.setMessageStatus(message.id, 'sending');
    setTimeout(() => {
      if (this.getMessageStatus(message.id) === 'sending') {
        this.setMessageStatus(message.id, 'sent');
      }
    }, 1200);
  }

  private openMessageActions(message: Message): void {
    this.selectedActionMessage = message;
    this.bottomSheetMode = 'actions';
    this.showBottomSheet = true;
  }

  private handleMessageLongPress(message: Message): void {
    if (this.isSelectionMode) {
      this.toggleSelectMessage(message.id);
      return;
    }
    this.openMessageActions(message);
  }

  private canRecallSelectedMessage(): boolean {
    const msg = this.selectedActionMessage;
    if (!msg) {
      return false;
    }
    if (msg.isTransient) {
      return false;
    }
    if (msg.type !== MessageType.TEXT) {
      return false;
    }
    if (msg.userId !== this.userId) {
      return false;
    }
    if (msg.userId === 'silk_ai_agent') {
      return false;
    }
    return true;
  }

  private handleRecallSelected(): void {
    const msg = this.selectedActionMessage;
    if (!msg || !this.canRecallSelectedMessage()) {
      return;
    }
    this.runRecallMessage(msg);
  }

  private async runRecallMessage(msg: Message): Promise<void> {
    this.isRecalling = true;
    try {
      const response = await this.apiClient.recallMessage(this.groupId, msg.id, this.userId);
      if (response.success) {
        this.showToast('已撤回');
        this.showBottomSheet = false;
        this.selectedActionMessage = null;
      } else {
        this.showToast(response.message ? response.message : '撤回失败');
      }
    } catch (error) {
      console.error(`Recall failed: ${error}`);
      this.showToast('撤回失败');
    } finally {
      this.isRecalling = false;
    }
  }

  private startForwardFromActions(message: Message): void {
    this.selectedActionMessage = message;
    this.handleForward(message);
  }

  private markMessagesAsSent(messageIds: string[]): void {
    if (messageIds.length === 0) {
      return;
    }
    const next: Record<string, string> = {};
    const currentKeys: string[] = Object.keys(this.messageStatusMap);
    for (let i = 0; i < currentKeys.length; i++) {
      const id = currentKeys[i];
      next[id] = this.messageStatusMap[id];
    }

    let changed = false;
    for (let i = 0; i < messageIds.length; i++) {
      const id = messageIds[i];
      if (next[id] && next[id] !== 'sent') {
        next[id] = 'sent';
        changed = true;
      }
    }

    if (changed) {
      this.messageStatusMap = next;
    }
  }

  private sanitizeForInput(text: string): string {
    const source = text ? text : '';
    // 去掉可能导致输入框异常的控制字符（保留换行）
    const cleaned = source.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
    const maxLength = 800;
    if (cleaned.length > maxLength) {
      return `${cleaned.substring(0, maxLength)}...`;
    }
    return cleaned;
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({
        message: message,
        duration: 1500
      });
    } catch (error) {
      console.error(`Toast failed: ${error}`);
    }
  }

  private handleCopy(content: string): void {
    const text = this.sanitizeForInput(content);
    try {
      const systemPasteboard = pasteboard.getSystemPasteboard();
      const pasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
      systemPasteboard.setData(pasteData).then(() => {
        this.showToast('已复制到剪贴板');
      }).catch((error: Error) => {
        console.error(`Copy failed: ${error.message}`);
        this.showToast('复制失败');
      });
    } catch (error) {
      console.error(`Copy exception: ${error}`);
      this.showToast('复制失败');
    }
  }

  private handleForward(message: Message): void {
    this.bottomSheetMode = 'forward';
    const content = message.content ? message.content : '';
    const sender = message.userName && message.userName.trim().length > 0 ? message.userName.trim() : '未知用户';
    const payload = this.sanitizeForInput(`📨 转发自【${this.groupName}】:\n👤 ${sender}\n\n${content}`);
    this.forwardingContent = payload;
    this.forwardingError = '';
    this.isLoadingForwardTargets = true;
    this.forwardTargets = [];
    this.showBottomSheet = true;
    this.loadForwardTargets();
  }

  private handleShare(message: Message): void {
    // 与“复制”区分：分享表示走系统分享能力（当前先保留入口与提示）
    this.showToast('系统分享功能开发中');
  }

  private toggleSelectMessage(messageId: string): void {
    const next: string[] = [];
    let exists = false;
    for (let i = 0; i < this.selectedMessageIds.length; i++) {
      const id = this.selectedMessageIds[i];
      if (id === messageId) {
        exists = true;
      } else {
        next.push(id);
      }
    }
    if (!exists) {
      next.push(messageId);
    }
    this.selectedMessageIds = next;
    this.showToast(`已选择 ${this.selectedMessageIds.length} 条`);
  }

  private isMessageSelected(messageId: string): boolean {
    for (let i = 0; i < this.selectedMessageIds.length; i++) {
      if (this.selectedMessageIds[i] === messageId) {
        return true;
      }
    }
    return false;
  }

  private getSelectedMessages(): Message[] {
    const result: Message[] = [];
    for (let i = 0; i < this.messages.length; i++) {
      const message = this.messages[i];
      if (this.isMessageSelected(message.id)) {
        result.push(message);
      }
    }
    return result;
  }

  /** 多条合并为 Markdown：每条以三级标题展示发送者，正文单独一段（与 MarkdownLite 一致）。 */
  private mergeSelectedMessagesMarkdown(selected: Message[]): string {
    let merged = '';
    for (let i = 0; i < selected.length; i++) {
      const item = selected[i];
      const rawName = item.userName ? item.userName : '用户';
      const safeName = rawName.replace(/[\r\n]+/g, ' ').trim();
      const content = item.content ? item.content : '';
      if (merged !== '') {
        merged += '\n\n';
      }
      merged += `### ${safeName}\n\n${content}`;
    }
    return merged;
  }

  private handleBatchCopy(): void {
    const selected = this.getSelectedMessages();
    if (selected.length === 0) {
      this.showToast('请先选择消息');
      return;
    }
    this.handleCopy(this.mergeSelectedMessagesMarkdown(selected));
  }

  private handleBatchForward(): void {
    const selected = this.getSelectedMessages();
    if (selected.length === 0) {
      this.showToast('请先选择消息');
      return;
    }
    const merged = this.mergeSelectedMessagesMarkdown(selected);
    this.bottomSheetMode = 'forward';
    const batchHint =
      selected.length === 1 && selected[0].userName && selected[0].userName.trim().length > 0
        ? selected[0].userName.trim()
        : `共 ${selected.length} 条`;
    this.forwardingContent = this.sanitizeForInput(`📨 批量转发自【${this.groupName}】:\n👤 ${batchHint}\n\n${merged}`);
    this.forwardingError = '';
    this.isLoadingForwardTargets = true;
    this.forwardTargets = [];
    this.showBottomSheet = true;
    this.loadForwardTargets();
  }

  private openMultiSelectEntry(): void {
    this.isSelectionMode = true;
    if (this.selectedActionMessage) {
      this.toggleSelectMessage(this.selectedActionMessage.id);
    } else {
      this.showToast('已进入多选模式');
    }
  }

  private exitMultiSelectMode(): void {
    this.isSelectionMode = false;
    this.selectedMessageIds = [];
  }

  private async loadForwardTargets(): Promise<void> {
    if (this.userId === '') {
      this.isLoadingForwardTargets = false;
      this.showToast('用户未登录');
      return;
    }

    try {
      const response = await this.apiClient.getGroups(this.userId);
      const groups = response.data ? response.data : [];
      const result: Group[] = [];
      for (let i = 0; i < groups.length; i++) {
        const group = groups[i];
        if (group.id !== this.groupId) {
          result.push(group);
        }
      }
      this.forwardTargets = result;
      this.isLoadingForwardTargets = false;
      if (result.length === 0) {
        this.forwardingError = '暂无可转发群组';
      }
    } catch (error) {
      console.error(`Load forward targets failed: ${error}`);
      this.isLoadingForwardTargets = false;
      this.forwardingError = '加载群组失败';
      this.showToast('加载群组失败');
    }
  }

  private async forwardToGroup(group: Group): Promise<void> {
    if (this.isForwarding || this.forwardingContent.trim() === '') {
      return;
    }

    this.isForwarding = true;
    this.forwardingError = '';
    try {
      const response = await this.apiClient.sendMessageToGroup(
        group.id,
        this.userId,
        this.userName,
        this.forwardingContent
      );
      if (response.success) {
        this.showBottomSheet = false;
        this.showToast(`已转发到 ${group.name}`);
      } else {
        this.forwardingError = response.message ? response.message : '转发失败';
      }
    } catch (error) {
      console.error(`Forward failed: ${error}`);
      this.forwardingError = '转发失败';
    } finally {
      this.isForwarding = false;
    }
  }

  private reconnect(): void {
    if (this.userId === '') {
      return;
    }
    globalChatStore.clearMessages();
    this.connectionState = ConnectionState.CONNECTING;
    globalChatStore.connect(this.userId, this.userName, this.groupId);
  }

  private shouldShowMessageTime(index: number): boolean {
    if (index <= 0 || index >= this.messages.length) {
      return true;
    }
    const current = this.messages[index];
    const prev = this.messages[index - 1];
    const gap = current.timestamp - prev.timestamp;
    // 与 Android 聊天列表策略一致：相邻消息时间差超过 5 分钟展示时间分隔
    return gap > 5 * 60 * 1000;
  }

  private formatMessageTimeDivider(timestamp: number): string {
    const current = new Date(timestamp);
    const now = new Date();
    const currentDate = new Date(current.getFullYear(), current.getMonth(), current.getDate());
    const todayDate = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const diffDays = Math.floor((todayDate.getTime() - currentDate.getTime()) / (24 * 60 * 60 * 1000));
    const hours = current.getHours().toString().padStart(2, '0');
    const minutes = current.getMinutes().toString().padStart(2, '0');
    if (diffDays === 0) {
      return `${hours}:${minutes}`;
    }
    if (diffDays === 1) {
      return `昨天 ${hours}:${minutes}`;
    }
    const month = (current.getMonth() + 1).toString().padStart(2, '0');
    const day = current.getDate().toString().padStart(2, '0');
    return `${month}-${day} ${hours}:${minutes}`;
  }

  /** 与 Android ChatScreen 一致：群组名为 [Silk] 开头表示与 Silk 的专属私聊，无需再点 @ */
  private isSilkPrivateChat(): boolean {
    return this.groupName.startsWith('[Silk]');
  }

  private async quietRefreshTodos(): Promise<void> {
    if (this.userId === '') {
      return;
    }
    try {
      await this.apiClient.refreshUserTodos(this.userId);
    } catch (_) {
      // 后台预热，忽略错误
    }
  }

  private openTodoSheet(): void {
    this.bottomSheetMode = 'todos';
    this.showBottomSheet = true;
    void this.loadTodosSheet();
  }

  private async loadTodosSheet(): Promise<void> {
    this.todoRefreshing = true;
    try {
      const cached = await this.apiClient.getUserTodos(this.userId);
      if (cached.success && cached.data) {
        this.todoItems = cached.data;
      }
      const fresh = await this.apiClient.refreshUserTodos(this.userId);
      if (fresh.success && fresh.data) {
        this.todoItems = fresh.data;
      } else if (!fresh.success) {
        this.showToast(fresh.message ? fresh.message : '同步待办失败');
      }
    } finally {
      this.todoRefreshing = false;
    }
  }

  private toggleTodoItem(item: UserTodoItem): void {
    void this.runToggleTodo(item);
  }

  /** 仅闹钟/日程且能从文案解析出时间时可点「执行」 */
  private todoCanExecute(item: UserTodoItem): boolean {
    if (item.done) {
      return false;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at !== 'alarm' && at !== 'calendar') {
      return false;
    }
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    return parseAlarmTimeFromText(blob) !== null;
  }

  /** 标记为闹钟/日程但当前解析不出时间 */
  private todoNeedsTimeHint(item: UserTodoItem): boolean {
    if (item.done) {
      return false;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at !== 'alarm' && at !== 'calendar') {
      return false;
    }
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    return parseAlarmTimeFromText(blob) === null;
  }

  private isTodoExecuting(item: UserTodoItem): boolean {
    return this.executingTodoIds[item.id] === true;
  }

  private async runTodoMainAction(item: UserTodoItem): Promise<void> {
    if (item.done) {
      this.showToast('已完成项可在左侧勾选取消');
      return;
    }
    if (this.isTodoExecuting(item)) {
      this.showToast('正在执行中，请稍候');
      return;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    const parsed = parseAlarmTimeFromText(blob);
    let res: AlarmResult | null = null;
    if (at === 'alarm') {
      if (!parsed) {
        this.showToast('无法解析时间，标题或详情需含如 7点、07:00');
        return;
      }
      res = await this.executeWithLoading(item.id, async (): Promise<AlarmResult> => {
        return await AlarmService.setAlarmForTodo(parsed!.hour, parsed!.minute, item.title);
      });
    } else if (at === 'calendar') {
      if (!parsed) {
        this.showToast('无法解析日程时间');
        return;
      }
      res = await this.executeWithLoading(item.id, async (): Promise<AlarmResult> => {
        return await AlarmService.setCalendarOnlyForTodo(parsed!.hour, parsed!.minute, item.title);
      });
    } else {
      return;
    }
    if (!res) {
      return;
    }
    if (res.success) {
      this.showToast(res.message);
      void this.runToggleTodo(item);
    } else {
      this.showToast(`执行失败: ${res.message}`);
    }
  }

  private async executeWithLoading(todoId: string, action: () => Promise<AlarmResult>): Promise<AlarmResult | null> {
    const nextExecuting: Record<string, boolean> = {};
    const keys: string[] = Object.keys(this.executingTodoIds);
    for (let i = 0; i < keys.length; i++) {
      nextExecuting[keys[i]] = this.executingTodoIds[keys[i]];
    }
    nextExecuting[todoId] = true;
    this.executingTodoIds = nextExecuting;
    try {
      return await action();
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      this.showToast(`执行异常: ${errMsg}`);
      return null;
    } finally {
      const clearExecuting: Record<string, boolean> = {};
      const clearKeys: string[] = Object.keys(this.executingTodoIds);
      for (let i = 0; i < clearKeys.length; i++) {
        if (clearKeys[i] !== todoId) {
          clearExecuting[clearKeys[i]] = this.executingTodoIds[clearKeys[i]];
        }
      }
      this.executingTodoIds = clearExecuting;
    }
  }

  private async runToggleTodo(item: UserTodoItem): Promise<void> {
    const next = !item.done;
    const res = await this.apiClient.updateUserTodoItem(this.userId, item.id, next);
    if (res.success && res.data) {
      this.todoItems = res.data;
    } else {
      this.showToast(res.message ? res.message : '更新失败');
    }
  }

  private getBottomSheetHeight(): string {
    if (this.bottomSheetMode === 'forward') {
      return '55%';
    }
    if (this.bottomSheetMode === 'todos') {
      return '68%';
    }
    return '44%';
  }

  private applySilkQuickMention(): void {
    this.messageInput = '@Silk ';
  }

  private getMessageInputPlaceholder(): string {
    if (this.isSilkPrivateChat()) {
      return '直接输入消息与 Silk 对话...';
    }
    return '输入消息...';
  }

  build() {
    Column() {
      // 顶部导航栏
      Row() {
        Text('<')
          .fontSize(22)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 16 })
          .onClick((): void => {
            appRouter.goToGroupList();
          })

        Text(this.groupName)
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 12 })
          .layoutWeight(1)

        if (this.isSilkPrivateChat()) {
          Text('待办')
            .fontSize(15)
            .fontColor(SilkColors.primary)
            .fontWeight(FontWeight.Medium)
            .margin({ right: 16 })
            .onClick((): void => {
              this.openTodoSheet();
            })
        }
      }
      .width('100%')
      .height(56)
      .backgroundColor(SilkColors.surface)

      // 连接状态条
      if (this.connectionState !== ConnectionState.CONNECTED) {
        Row() {
          LoadingProgress()
            .width(16)
            .height(16)
            .color(SilkColors.primary)

          Text(this.connectionState === ConnectionState.CONNECTING ? '连接中...' : '连接断开')
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .margin({ left: 8 })

          if (this.connectionState === ConnectionState.DISCONNECTED) {
            Button('重连')
              .height(24)
              .fontSize(12)
              .backgroundColor(SilkColors.primary)
              .fontColor(SilkColors.onPrimary)
              .margin({ left: 12 })
              .onClick((): void => this.reconnect())
          }
        }
        .width('100%')
        .height(32)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FDF3E0')
      }

      // 消息区域
      if (this.isSelectionMode) {
        Row() {
          Text(`多选模式（已选 ${this.selectedMessageIds.length} 条）`)
            .fontSize(12)
            .fontColor(SilkColors.textPrimary)
          Blank()
          Text('退出')
            .fontSize(12)
            .fontColor(SilkColors.primary)
            .onClick(() => {
              this.exitMultiSelectMode();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        .backgroundColor('#F3EFE6')
      }

      if (this.messages.length === 0) {
        Column() {
          Text('暂无消息，开始聊天吧')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else {
        List({ space: 4, scroller: this.listScroller }) {
          ForEach(this.messages, (message: Message, index?: number) => {
            ListItem() {
              Column() {
                if (this.shouldShowMessageTime(index !== undefined ? index : 0)) {
                  Row() {
                    Text(this.formatMessageTimeDivider(message.timestamp))
                      .fontSize(11)
                      .fontColor(SilkColors.textLight)
                      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
                      .backgroundColor('#14000000')
                      .borderRadius(8)
                  }
                  .width('100%')
                  .justifyContent(FlexAlign.Center)
                  .margin({ top: 4, bottom: 2 })
                }

                Row() {
                  if (this.isSelectionMode) {
                    Text(this.isMessageSelected(message.id) ? '☑' : '☐')
                      .fontSize(18)
                      .fontColor(this.isMessageSelected(message.id) ? SilkColors.primary : SilkColors.textLight)
                      .margin({ left: 8, right: 4 })
                      .onClick(() => {
                        this.toggleSelectMessage(message.id);
                      })
                  }
                  MessageBubble({
                    message: message,
                    isOwn: message.userId === this.userId,
                    currentUserId: this.userId,
                    actionsEnabled: !this.isSelectionMode,
                    messageStatus: this.getMessageStatus(message.id),
                    onCopy: (content: string): void => this.handleCopy(content),
                    onForward: (msg: Message): void => this.handleForward(msg),
                    onRetry: (msg: Message): void => this.retryMessage(msg),
                    onOpenActions: (msg: Message): void => this.openMessageActions(msg),
                    onLongContentCollapsed: (): void => this.scrollListToMessageId(message.id)
                  })
                }
                .width('100%')
                .onClick(() => {
                  if (this.isSelectionMode) {
                    this.toggleSelectMessage(message.id);
                  }
                })
                .gesture(
                  LongPressGesture({ repeat: false })
                    .onAction(() => {
                      this.handleMessageLongPress(message);
                    })
                )
              }
              .width('100%')
            }
          }, (message: Message) => `${message.id}_${message.timestamp}`)
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ top: 8, bottom: 8 })
      }

      // 输入区域（普通群显示 @Silk 快捷填入；[Silk] 专属私聊与多选模式不显示）
      Column() {
        if (!this.isSilkPrivateChat() && !this.isSelectionMode) {
          Row() {
            Text('@Silk')
              .fontSize(14)
              .fontColor(SilkColors.primary)
              .fontWeight(FontWeight.Medium)
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .backgroundColor('#26C9A86C')
              .borderRadius(8)
              .onClick((): void => {
                this.applySilkQuickMention();
              })
          }
          .width('100%')
          .justifyContent(FlexAlign.Start)
          .margin({ bottom: 6 })
        }

        Row() {
          TextInput({ placeholder: this.getMessageInputPlaceholder(), text: $$this.messageInput })
            .layoutWeight(1)
            .height(44)
            .backgroundColor(SilkColors.surface)
            .borderRadius(22)
            .padding({ left: 16, right: 16 })
            .fontColor(SilkColors.textPrimary)
            .placeholderColor(SilkColors.textLight)
            .fontSize(16)

          Button('发送')
            .width(60)
            .height(44)
            .backgroundColor(this.messageInput.trim() ? SilkColors.primary : SilkColors.border)
            .fontColor(SilkColors.onPrimary)
            .borderRadius(22)
            .margin({ left: 8 })
            .enabled(this.messageInput.trim().length > 0)
            .onClick((): void => {
              this.handleSend(this.messageInput);
              this.messageInput = '';
            })
        }
        .width('100%')
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .backgroundColor(SilkColors.background)

      if (this.isSelectionMode) {
        Row() {
          Button(`复制(${this.selectedMessageIds.length})`)
            .height(36)
            .fontSize(13)
            .backgroundColor(SilkColors.surfaceVariant)
            .fontColor(SilkColors.textPrimary)
            .enabled(this.selectedMessageIds.length > 0)
            .onClick(() => {
              this.handleBatchCopy();
            })

          Button(`转发(${this.selectedMessageIds.length})`)
            .height(36)
            .fontSize(13)
            .backgroundColor(SilkColors.primary)
            .fontColor(SilkColors.onPrimary)
            .margin({ left: 8 })
            .enabled(this.selectedMessageIds.length > 0)
            .onClick(() => {
              this.handleBatchForward();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 10 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(SilkColors.background)
    .bindSheet($$this.showBottomSheet, this.buildBottomSheet(), {
      height: this.getBottomSheetHeight(),
      backgroundColor: SilkColors.surface
    })
  }

  @Builder
  private buildBottomSheet() {
    if (this.bottomSheetMode === 'forward') {
      this.buildForwardSheet();
    } else if (this.bottomSheetMode === 'todos') {
      this.buildTodoSheet();
    } else {
      this.buildMessageActionsSheet();
    }
  }

  @Builder
  private buildTodoSheet() {
    Column() {
      Row() {
        Text('我的待办')
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)

        if (this.todoRefreshing) {
          LoadingProgress()
            .width(20)
            .height(20)
            .color(SilkColors.primary)
            .margin({ right: 8 })
        }

        Text('关闭')
          .fontSize(14)
          .fontColor(SilkColors.primary)
          .onClick((): void => {
            this.showBottomSheet = false;
          })
      }
      .width('100%')
      .margin({ bottom: 12 })

      Text('一事一条待办。可运行项右侧显示「▶ 运行」，点击自动调用系统能力并标记完成。')
        .fontSize(12)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 12 })

      if (this.todoItems.length === 0) {
        Text(this.todoRefreshing ? '正在从各群同步…' : '暂无待办')
          .fontSize(14)
          .fontColor(SilkColors.textSecondary)
      } else {
        List({ space: 8 }) {
          ForEach(this.todoItems, (item: UserTodoItem) => {
            ListItem() {
              Row() {
                Text(item.done ? '☑' : '☐')
                  .fontSize(18)
                  .fontColor(item.done ? SilkColors.textLight : SilkColors.primary)
                  .margin({ right: 10 })
                  .padding({ top: 4 })
                  .onClick((): void => {
                    this.toggleTodoItem(item);
                  })

                Column() {
                  Text(item.title)
                    .fontSize(15)
                    .fontColor(item.done ? SilkColors.textLight : SilkColors.textPrimary)
                    .maxLines(4)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })

                  if (this.todoNeedsTimeHint(item)) {
                    Text('缺时间信息，标题需含如 7点、07:00')
                      .fontSize(11)
                      .fontColor('#D48806')
                      .margin({ top: 4 })
                  }

                  if (item.sourceGroupName && item.sourceGroupName.length > 0) {
                    Text(`来自：${item.sourceGroupName}`)
                      .fontSize(11)
                      .fontColor(SilkColors.textLight)
                      .margin({ top: 4 })
                  }
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)

                if (this.todoCanExecute(item)) {
                  if (this.isTodoExecuting(item)) {
                    LoadingProgress()
                      .width(32)
                      .height(32)
                      .color(SilkColors.onPrimary)
                  } else {
                    Button('▶ 运行')
                      .height(32)
                      .fontSize(13)
                      .padding({ left: 12, right: 12 })
                      .backgroundColor(SilkColors.primary)
                      .fontColor(SilkColors.onPrimary)
                      .borderRadius(8)
                      .onClick((): void => {
                        void this.runTodoMainAction(item);
                      })
                  }
                }
              }
              .width('100%')
              .padding(12)
              .backgroundColor(SilkColors.background)
              .borderRadius(10)
            }
          }, (item: UserTodoItem) => item.id)
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%')
    .padding(16)
    .height('100%')
  }

  private getForwardingPreviewParts(): ForwardedMessageParts | null {
    return parseForwardedMessageContent(this.forwardingContent);
  }

  @Builder
  private buildForwardingPreviewCard() {
    Column() {
      Row() {
        Text('📨')
          .fontSize(14)
          .margin({ right: 6 })
        Text(this.getForwardingPreviewParts()!.isBatch ? '批量转发' : '转发')
          .fontSize(12)
          .fontColor('#7A6B5A')
        Text(' · 来自')
          .fontSize(12)
          .fontColor(SilkColors.textLight)
          .margin({ left: 4, right: 4 })
        Text(this.getForwardingPreviewParts()!.sourceName.length > 0 ?
          this.getForwardingPreviewParts()!.sourceName : '未命名会话')
          .fontSize(12)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.primary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
      }
      .width('100%')

      if (this.getForwardingPreviewParts()!.senderName.length > 0) {
        Row() {
          Text('👤')
            .fontSize(15)
            .margin({ right: 8 })
          Text(this.getForwardingPreviewParts()!.senderName)
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(SilkColors.textPrimary)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 8, bottom: 8 })
        .margin({ top: 8 })
        .backgroundColor('#FFFFFF')
        .borderRadius(8)
        .border({
          width: 1,
          color: '#E8E0D4'
        })
      }

      Row()
        .width('100%')
        .height(1)
        .backgroundColor('#E8E0D4')
        .margin({ top: 8, bottom: 8 })

      Text(this.getForwardingPreviewParts()!.body)
        .fontSize(13)
        .fontColor(SilkColors.textPrimary)
        .maxLines(5)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding(12)
    .backgroundColor(SilkColors.background)
    .borderRadius(8)
    .border({
      width: { left: 3 },
      color: SilkColors.primary
    })
    .margin({ bottom: 12 })
  }

  @Builder
  private buildForwardSheet() {
    Column() {
      Text('选择转发目标群组')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 12 })

      if (this.forwardingContent) {
        if (this.getForwardingPreviewParts() !== null) {
          this.buildForwardingPreviewCard();
        } else {
          Text(this.forwardingContent)
            .fontSize(12)
            .fontColor(SilkColors.textSecondary)
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .padding(10)
            .backgroundColor(SilkColors.background)
            .borderRadius(8)
            .margin({ bottom: 12 })
        }
      }

      if (this.forwardTargets.length === 0) {
        if (this.isLoadingForwardTargets) {
          Row() {
            LoadingProgress()
              .width(16)
              .height(16)
              .color(SilkColors.primary)
            Text('加载可转发群组...')
              .fontSize(14)
              .fontColor(SilkColors.textSecondary)
              .margin({ left: 8 })
          }
          .margin({ top: 16 })
        } else {
          Text('暂无可转发的群组')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
            .margin({ top: 16 })
        }
      } else {
        List({ space: 8 }) {
          ForEach(this.forwardTargets, (group: Group) => {
            ListItem() {
              Row() {
                Text(group.name)
                  .fontSize(15)
                  .fontColor(SilkColors.textPrimary)
                  .layoutWeight(1)

                if (this.isForwarding) {
                  LoadingProgress()
                    .width(16)
                    .height(16)
                    .color(SilkColors.primary)
                } else {
                  Text('发送')
                    .fontSize(13)
                    .fontColor(SilkColors.primary)
                }
              }
              .width('100%')
              .padding(12)
              .backgroundColor(SilkColors.background)
              .borderRadius(8)
              .onClick(() => {
                this.forwardToGroup(group);
              })
            }
          }, (group: Group) => group.id)
        }
        .layoutWeight(1)
        .width('100%')
      }

      if (this.forwardingError) {
        Text(this.forwardingError)
          .fontSize(12)
          .fontColor(SilkColors.error)
          .margin({ top: 8, bottom: 8 })
      }

      Button('取消')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textSecondary)
        .margin({ top: 8 })
        .onClick(() => {
          this.showBottomSheet = false;
        })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  private buildMessageActionsSheet() {
    Column() {
      Text('消息操作')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 12 })

      if (this.selectedActionMessage) {
        Text(this.selectedActionMessage.content ? this.selectedActionMessage.content : '')
          .fontSize(12)
          .fontColor(SilkColors.textSecondary)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding(10)
          .backgroundColor(SilkColors.background)
          .borderRadius(8)
          .margin({ bottom: 12 })
      }

      Button('复制')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.handleCopy(this.selectedActionMessage.content ? this.selectedActionMessage.content : '');
          }
          this.showBottomSheet = false;
        })

      Button('转发')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.startForwardFromActions(this.selectedActionMessage);
            return;
          }
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      Button('分享')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          if (this.selectedActionMessage) {
            this.handleShare(this.selectedActionMessage);
          }
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      Button('多选')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textPrimary)
        .margin({ top: 8 })
        .onClick(() => {
          this.openMultiSelectEntry();
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })

      if (this.canRecallSelectedMessage()) {
        Button(this.isRecalling ? '撤回中...' : '撤回')
          .width('100%')
          .height(42)
          .backgroundColor(SilkColors.surfaceVariant)
          .fontColor(SilkColors.error)
          .margin({ top: 8 })
          .enabled(!this.isRecalling)
          .onClick(() => {
            this.handleRecallSelected();
          })
      }

      Button('取消')
        .width('100%')
        .height(42)
        .backgroundColor(SilkColors.surfaceVariant)
        .fontColor(SilkColors.textSecondary)
        .margin({ top: 8 })
        .onClick(() => {
          this.showBottomSheet = false;
          this.selectedActionMessage = null;
        })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
