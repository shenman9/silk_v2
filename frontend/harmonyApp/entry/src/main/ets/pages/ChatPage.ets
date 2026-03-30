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
import { apiClient as globalApiClient, UserTodoPatch, GroupMemberProfile } from '../api/ApiClient';
import pasteboard from '@ohos.pasteboard';
import promptAction from '@ohos.promptAction';
import { AlarmService, AlarmResult } from '../common/AlarmService';
import { parseAlarmTimeFromText } from '../common/TodoTimeParser';

@Component
export struct ChatPage {
  @State private connectionState: ConnectionState = ConnectionState.DISCONNECTED;
  @State private messageInput: string = '';
  @State private groupName: string = '项目会话';
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
  @State private groupInvitationCode: string = '';
  @State private groupMembers: GroupMemberProfile[] = [];
  @State private membersLoading: boolean = false;
  @State private membersError: string = '';
  @State private todoItems: UserTodoItem[] = [];
  @State private todoRefreshing: boolean = false;
  @State private executingTodoIds: Record<string, boolean> = {};
  @State private editingTodo: UserTodoItem | null = null;
  @State private editTitle: string = '';
  @State private editActionType: string = 'none';
  @State private editActionDetail: string = '';
  @State private deletingTodoId: string = '';
  @State private todoMenuTarget: UserTodoItem | null = null;

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
    const storedGroupInviteCode = AppStorage.get<string>('currentGroupInvitationCode');

    const currentUser = globalAppStore.currentUser;
    const currentGroup = globalAppStore.currentGroup;

    this.userId = storedUserId ? storedUserId : (currentUser ? currentUser.id : '');
    this.userName = storedUserName ? storedUserName : (currentUser ? currentUser.name : '');
    this.groupId = storedGroupId ? storedGroupId : (currentGroup ? currentGroup.id : 'default_room');
    this.groupName = storedGroupName ? storedGroupName : (currentGroup ? currentGroup.name : '项目会话');
    this.groupInvitationCode = storedGroupInviteCode ? storedGroupInviteCode : (currentGroup && currentGroup.invitationCode ? currentGroup.invitationCode : '');

    if (this.userId === '') {
      appRouter.goToLogin();
      return;
    }

    AppStorage.setOrCreate<string>('currentUserId', this.userId);
    AppStorage.setOrCreate<string>('currentUserName', this.userName);
    AppStorage.setOrCreate<string>('currentGroupId', this.groupId);
    AppStorage.setOrCreate<string>('currentGroupName', this.groupName);
    AppStorage.setOrCreate<string>('currentGroupInvitationCode', this.groupInvitationCode);

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

  private openInvitationSheet(): void {
    if (!this.groupInvitationCode || this.groupInvitationCode.trim().length === 0) {
      this.showToast('当前群组暂无邀请码');
      return;
    }
    this.bottomSheetMode = 'invite';
    this.showBottomSheet = true;
  }

  private async openMembersSheet(): Promise<void> {
    this.bottomSheetMode = 'members';
    this.showBottomSheet = true;
    this.membersLoading = true;
    this.membersError = '';
    try {
      const response = await this.apiClient.getGroupMembers(this.groupId);
      if (response.success && response.data) {
        this.groupMembers = response.data;
      } else {
        this.groupMembers = [];
        this.membersError = response.message ? response.message : '加载成员失败';
      }
    } catch (_) {
      this.groupMembers = [];
      this.membersError = '加载成员失败';
    } finally {
      this.membersLoading = false;
    }
  }

  private async loadTodosSheet(): Promise<void> {
    this.todoRefreshing = true;
    try {
      let loaded: UserTodoItem[] = [];
      const cached = await this.apiClient.getUserTodos(this.userId);
      if (cached.success && cached.data) {
        this.todoItems = cached.data;
        loaded = cached.data;
      }
      const fresh = await this.apiClient.refreshUserTodos(this.userId);
      if (fresh.success && fresh.data) {
        this.todoItems = fresh.data;
        loaded = fresh.data;
      } else if (!fresh.success) {
        this.showToast(fresh.message ? fresh.message : '同步待办失败');
      }
      if (loaded.length > 0) {
        await this.reconcileExecutedTodosWithSystem(loaded);
      }
    } finally {
      this.todoRefreshing = false;
    }
  }

  /**
   * 打开待办面板时，校验“已执行”项在系统侧是否仍存在；
   * 若用户已在系统日历/闹钟里删除，则回补为待执行（done=false，保留 executedAt，清空 reminderId）。
   */
  private async reconcileExecutedTodosWithSystem(items: UserTodoItem[]): Promise<void> {
    const candidates: UserTodoItem[] = [];
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (!this.isLongTermTemplate(it) &&
        it.done &&
        it.executedAt !== undefined &&
        it.executedAt > 0 &&
        it.reminderId !== undefined &&
        it.reminderId > 0) {
        candidates.push(it);
      }
    }
    if (candidates.length === 0) {
      return;
    }

    const checks = await Promise.all(candidates.map(async (it: UserTodoItem): Promise<string> => {
      const rid = it.reminderId as number;
      const blob = `${it.actionDetail ? it.actionDetail : ''} ${it.title}`;
      const parsed = parseAlarmTimeFromText(blob);
      const hour = parsed ? parsed.hour : undefined;
      const minute = parsed ? parsed.minute : undefined;
      const at = it.actionType ? it.actionType : 'none';
      return await AlarmService.existsInSystem(rid, at, it.title, hour, minute);
    }));

    const toRecover: UserTodoItem[] = [];
    for (let i = 0; i < candidates.length; i++) {
      if (checks[i] === 'missing') {
        toRecover.push(candidates[i]);
      }
    }
    if (toRecover.length === 0) {
      return;
    }

    let latest: UserTodoItem[] | null = null;
    for (let i = 0; i < toRecover.length; i++) {
      const patch: UserTodoPatch = {
        done: false,
        clearReminderId: true
      };
      const res = await this.apiClient.updateUserTodoItem(this.userId, toRecover[i].id, patch);
      if (res.success && res.data) {
        latest = res.data;
      } else {
        this.applyTodoRecoveredLocal(toRecover[i].id);
      }
    }
    if (latest) {
      this.todoItems = latest;
    }
  }

  /** 回补失败时本地兜底，确保该项恢复为可重设状态 */
  private applyTodoRecoveredLocal(itemId: string): void {
    const next: UserTodoItem[] = [];
    for (let i = 0; i < this.todoItems.length; i++) {
      const it = this.todoItems[i];
      if (it.id === itemId) {
        const merged: UserTodoItem = {
          id: it.id,
          title: it.title,
          createdAt: it.createdAt,
          updatedAt: Date.now(),
          done: false
        };
        if (it.sourceGroupId !== undefined) {
          merged.sourceGroupId = it.sourceGroupId;
        }
        if (it.sourceGroupName !== undefined) {
          merged.sourceGroupName = it.sourceGroupName;
        }
        if (it.actionType !== undefined) {
          merged.actionType = it.actionType;
        }
        if (it.actionDetail !== undefined) {
          merged.actionDetail = it.actionDetail;
        }
        if (it.executedAt !== undefined) {
          merged.executedAt = it.executedAt;
        }
        next.push(merged);
      } else {
        next.push(it);
      }
    }
    this.todoItems = next;
  }

  private toggleTodoItem(item: UserTodoItem): void {
    void this.runToggleTodo(item);
  }

  private isLongTermTemplate(item: UserTodoItem): boolean {
    return (item.taskKind ? item.taskKind : '').toLowerCase() === 'long_term_template';
  }

  private isWorkdayTemplate(item: UserTodoItem): boolean {
    const rr = (item.repeatRule ? item.repeatRule : '').toLowerCase();
    if (rr === 'workday') {
      return true;
    }
    const title = item.title ? item.title : '';
    return (/工作日|上班日|每个工作日/).test(title);
  }

  private formatDateYYYYMMDD(d: Date): string {
    const y = d.getFullYear();
    const m = `${d.getMonth() + 1}`.padStart(2, '0');
    const day = `${d.getDate()}`.padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  private async isTodayLegalWorkday(): Promise<boolean> {
    const dateText = this.formatDateYYYYMMDD(new Date());
    console.info(`[ChatPage] isTodayLegalWorkday: checking ${dateText}`);
    const res = await this.apiClient.isCalendarWorkday(dateText);
    console.info(`[ChatPage] isTodayLegalWorkday: API response success=${res.success}, data=${res.data}, msg=${res.message}`);
    if (res.success && res.data !== undefined) {
      if (!res.data) {
        this.showToast(`${dateText} 是法定休息日，工作日模板不会创建闹钟`);
      }
      return res.data === true;
    }
    this.showToast(`工作日校验失败（${res.message ? res.message : '未知原因'}），已按周规则兜底`);
    const day = new Date().getDay();
    return day >= 1 && day <= 5;
  }

  /**
   * 能从标题/详情解析出时间且类型为闹钟、日程或未分类（none）时可「执行」。
   * done=true 时不显示按钮：避免执行成功后仍显示「运行」导致重复创建系统提醒；需取消勾选后再「↻ 重设」。
   */
  private todoCanExecute(item: UserTodoItem): boolean {
    if (item.done) {
      return false;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at !== 'alarm' && at !== 'calendar' && at !== 'none') {
      return false;
    }
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    return parseAlarmTimeFromText(blob) !== null;
  }

  /** 标记为闹钟/日程（或看起来像定时提醒的 none）但当前解析不出时间 */
  private todoNeedsTimeHint(item: UserTodoItem): boolean {
    if (item.done) {
      return false;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at !== 'alarm' && at !== 'calendar') {
      if (at !== 'none') {
        return false;
      }
      const t = `${item.title} ${item.actionDetail ? item.actionDetail : ''}`;
      if (!/点|[:：]|\d\s*点|闹|提醒|起床/.test(t)) {
        return false;
      }
    }
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    return parseAlarmTimeFromText(blob) === null;
  }

  private isTodoExecuting(item: UserTodoItem): boolean {
    return this.executingTodoIds[item.id] === true;
  }

  private todoCanUndoExecute(item: UserTodoItem): boolean {
    return item.executedAt !== undefined &&
      item.executedAt > 0 &&
      item.reminderId !== undefined &&
      item.reminderId > 0;
  }

  private async runTodoMainAction(item: UserTodoItem): Promise<void> {
    if (this.isTodoExecuting(item)) {
      this.showToast('正在执行中，请稍候');
      return;
    }
    if (item.done) {
      this.showToast(this.isLongTermTemplate(item) ? '该模板已暂停。请先启用模板后再执行。' :
        '该项已完成。请先取消左侧勾选，再点「重设」调整提醒。');
      return;
    }
    let at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at === 'none' || at.length === 0) {
      at = 'alarm';
    }
    const blob = `${item.actionDetail ? item.actionDetail : ''} ${item.title}`;
    const parsed = parseAlarmTimeFromText(blob);
    if (!parsed) {
      this.showToast('无法解析时间，标题或详情需含如 7点、07:00');
      return;
    }
    const isLongTerm = this.isLongTermTemplate(item);
    const isWorkdayTpl = this.isWorkdayTemplate(item);
    const isRerun = item.executedAt !== undefined && item.executedAt > 0;
    await this.withTodoRowLocked(item.id, async (): Promise<void> => {
      if (isRerun && item.reminderId !== undefined && item.reminderId > 0) {
        await AlarmService.cancelReminder(item.reminderId);
        await AlarmService.removeCalendarEvent(item.reminderId);
      }
      let res: AlarmResult;
      if (at === 'calendar') {
        res = await AlarmService.setCalendarOnlyForTodo(parsed!.hour, parsed!.minute, item.title);
      } else if (isLongTerm && isWorkdayTpl) {
        res = await AlarmService.setRecurringAlarmForTodo(
          parsed!.hour, parsed!.minute, item.title, [1, 2, 3, 4, 5]
        );
      } else {
        res = await AlarmService.setAlarmForTodo(parsed!.hour, parsed!.minute, item.title);
      }
      if (!res.success) {
        this.showToast(`执行失败: ${res.message}`);
        return;
      }
      this.showToast(isRerun ? '已重新设定' : res.message);
      const now = Date.now();
      const firstExec = item.executedAt !== undefined && item.executedAt > 0 ? item.executedAt : now;
      const runPatch: UserTodoPatch = isLongTerm ? {
        done: false,
        executedAt: firstExec,
        reminderId: res.reminderId,
        lifecycleState: 'active',
        closedAt: 0
      } : {
        done: true,
        executedAt: firstExec,
        reminderId: res.reminderId,
        lifecycleState: 'done',
        closedAt: now
      };
      const updateRes = await this.apiClient.updateUserTodoItem(this.userId, item.id, runPatch);
      if (updateRes.success && updateRes.data) {
        this.todoItems = updateRes.data;
      } else {
        this.showToast(updateRes.message ? updateRes.message : '提醒已创建，同步待办失败');
        this.applyTodoRunSuccessLocal(item, firstExec, res.reminderId);
      }
    });
  }

  /** 整行锁定：系统能力 + 写回后端全程禁止重复点击 */
  private async withTodoRowLocked(todoId: string, action: () => Promise<void>): Promise<void> {
    const nextExecuting: Record<string, boolean> = {};
    const keys: string[] = Object.keys(this.executingTodoIds);
    for (let i = 0; i < keys.length; i++) {
      nextExecuting[keys[i]] = this.executingTodoIds[keys[i]];
    }
    nextExecuting[todoId] = true;
    this.executingTodoIds = nextExecuting;
    try {
      await action();
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '未知错误';
      this.showToast(`执行异常: ${errMsg}`);
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

  /** PATCH 失败时仍更新本地状态，避免 UI 停留在可再次「运行」 */
  private applyTodoRunSuccessLocal(item: UserTodoItem, executedAt: number, reminderId: number): void {
    const next: UserTodoItem[] = [];
    for (let i = 0; i < this.todoItems.length; i++) {
      const it = this.todoItems[i];
      if (it.id === item.id) {
        const isLongTerm = this.isLongTermTemplate(it);
        const merged: UserTodoItem = {
          id: it.id,
          title: it.title,
          createdAt: it.createdAt,
          updatedAt: Date.now(),
          done: isLongTerm ? false : true,
          executedAt: executedAt,
          reminderId: reminderId,
          lifecycleState: isLongTerm ? 'active' : 'done',
          closedAt: isLongTerm ? 0 : Date.now()
        };
        if (it.sourceGroupId !== undefined) {
          merged.sourceGroupId = it.sourceGroupId;
        }
        if (it.sourceGroupName !== undefined) {
          merged.sourceGroupName = it.sourceGroupName;
        }
        if (it.actionType !== undefined) {
          merged.actionType = it.actionType;
        }
        if (it.actionDetail !== undefined) {
          merged.actionDetail = it.actionDetail;
        }
        next.push(merged);
      } else {
        next.push(it);
      }
    }
    this.todoItems = next;
  }

  private async runUndoTodoExecution(item: UserTodoItem): Promise<void> {
    if (!this.todoCanUndoExecute(item)) {
      this.showToast('当前项没有可撤回的执行记录');
      return;
    }
    await this.withTodoRowLocked(item.id, async (): Promise<void> => {
      const rid = item.reminderId ? item.reminderId : 0;
      if (rid > 0) {
        await AlarmService.cancelReminder(rid);
        await AlarmService.removeCalendarEvent(rid);
      }
      if (item.title && item.title.length > 0) {
        await AlarmService.removeCalendarEventsByTitle(item.title);
      }
      const undoPatch: UserTodoPatch = {
        done: false,
        executedAt: 0,
        clearReminderId: true,
        lifecycleState: 'active',
        closedAt: 0
      };
      const res = await this.apiClient.updateUserTodoItem(this.userId, item.id, undoPatch);
      if (res.success && res.data) {
        this.todoItems = res.data;
        this.showToast('已撤回执行');
      } else {
        this.applyTodoUndoSuccessLocal(item);
        this.showToast(res.message ? res.message : '已撤回系统提醒，本地状态已回滚');
      }
    });
  }

  private applyTodoUndoSuccessLocal(item: UserTodoItem): void {
    const next: UserTodoItem[] = [];
    for (let i = 0; i < this.todoItems.length; i++) {
      const it = this.todoItems[i];
      if (it.id === item.id) {
        const merged: UserTodoItem = {
          id: it.id,
          title: it.title,
          createdAt: it.createdAt,
          updatedAt: Date.now(),
          done: false,
          executedAt: 0,
          lifecycleState: 'active',
          closedAt: 0
        };
        if (it.sourceGroupId !== undefined) {
          merged.sourceGroupId = it.sourceGroupId;
        }
        if (it.sourceGroupName !== undefined) {
          merged.sourceGroupName = it.sourceGroupName;
        }
        if (it.actionType !== undefined) {
          merged.actionType = it.actionType;
        }
        if (it.actionDetail !== undefined) {
          merged.actionDetail = it.actionDetail;
        }
        next.push(merged);
      } else {
        next.push(it);
      }
    }
    this.todoItems = next;
  }

  private async runToggleTodo(item: UserTodoItem): Promise<void> {
    const now = Date.now();
    let togglePatch: UserTodoPatch;
    if (this.isLongTermTemplate(item)) {
      const curState = item.lifecycleState ? item.lifecycleState.toLowerCase() : 'active';
      const nextState = curState === 'cancelled' ? 'active' : 'cancelled';
      togglePatch = {
        done: nextState !== 'active',
        lifecycleState: nextState,
        closedAt: nextState === 'active' ? 0 : now
      };
    } else {
      const next = !item.done;
      togglePatch = {
        done: next,
        lifecycleState: next ? 'done' : 'active',
        closedAt: next ? now : 0
      };
    }
    const res = await this.apiClient.updateUserTodoItem(this.userId, item.id, togglePatch);
    if (res.success && res.data) {
      this.todoItems = res.data;
    } else {
      this.showToast(res.message ? res.message : '更新失败');
    }
  }

  private confirmDeleteTodo(itemId: string): void {
    this.deletingTodoId = itemId;
  }

  private async doDeleteTodo(): Promise<void> {
    if (this.deletingTodoId.length === 0) {
      return;
    }
    const res = await this.apiClient.deleteUserTodoItem(this.userId, this.deletingTodoId);
    if (res.success && res.data) {
      this.todoItems = res.data;
      this.showToast('已删除');
    } else {
      this.showToast(res.message ? res.message : '删除失败');
    }
    this.deletingTodoId = '';
  }

  private openEditTodo(item: UserTodoItem): void {
    this.todoMenuTarget = null;
    this.editingTodo = item;
    this.editTitle = item.title;
    this.editActionType = item.actionType ? item.actionType : 'none';
    this.editActionDetail = item.actionDetail ? item.actionDetail : '';
  }

  private async saveEditTodo(): Promise<void> {
    if (!this.editingTodo) {
      return;
    }
    const t = this.editTitle.trim();
    if (t.length === 0) {
      this.showToast('标题不能为空');
      return;
    }
    const editPatch: UserTodoPatch = {
      title: t,
      actionType: this.editActionType === 'none' ? '' : this.editActionType,
      actionDetail: this.editActionType === 'none' ? '' : this.editActionDetail.trim()
    };
    const res = await this.apiClient.updateUserTodoItem(this.userId, this.editingTodo.id, editPatch);
    if (res.success && res.data) {
      this.todoItems = res.data;
      this.editingTodo = null;
      this.showToast('已保存');
    } else {
      this.showToast(res.message ? res.message : '保存失败');
    }
  }

  private showTodoActionMenu(item: UserTodoItem): void {
    this.todoMenuTarget = item;
  }

  private closeTodoSheetModals(): void {
    this.todoMenuTarget = null;
    this.editingTodo = null;
    this.deletingTodoId = '';
  }

  private getBottomSheetHeight(): string {
    if (this.bottomSheetMode === 'forward') {
      return '55%';
    }
    if (this.bottomSheetMode === 'members') {
      return '62%';
    }
    if (this.bottomSheetMode === 'invite') {
      return '34%';
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
    return '在当前项目中输入需求...';
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

        if (!this.isSilkPrivateChat()) {
          Text('邀请码')
            .fontSize(14)
            .fontColor(SilkColors.primary)
            .fontWeight(FontWeight.Medium)
            .margin({ right: 12 })
            .onClick((): void => {
              this.openInvitationSheet();
            })

          Text('成员')
            .fontSize(14)
            .fontColor(SilkColors.primary)
            .fontWeight(FontWeight.Medium)
            .margin({ right: 16 })
            .onClick((): void => {
              void this.openMembersSheet();
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
    } else if (this.bottomSheetMode === 'members') {
      this.buildMembersSheet();
    } else if (this.bottomSheetMode === 'invite') {
      this.buildInvitationSheet();
    } else if (this.bottomSheetMode === 'todos') {
      this.buildTodoSheet();
    } else {
      this.buildMessageActionsSheet();
    }
  }

  @Builder
  private buildInvitationSheet() {
    Column() {
      Text('群组邀请码')
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .fontColor(SilkColors.textPrimary)
        .margin({ bottom: 14 })

      Text(this.groupInvitationCode && this.groupInvitationCode.length > 0 ? this.groupInvitationCode : '暂无邀请码')
        .fontSize(28)
        .letterSpacing(2)
        .fontWeight(FontWeight.Bold)
        .fontColor(SilkColors.primary)
        .padding({ top: 14, bottom: 14, left: 16, right: 16 })
        .backgroundColor(SilkColors.background)
        .borderRadius(10)
        .margin({ bottom: 12 })

      Text('把邀请码发给好友，对方可在“加入群组”中输入')
        .fontSize(13)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 16 })

      Row() {
        Button('复制邀请码')
          .layoutWeight(1)
          .height(40)
          .backgroundColor(SilkColors.primary)
          .fontColor(SilkColors.onPrimary)
          .enabled(this.groupInvitationCode.trim().length > 0)
          .opacity(this.groupInvitationCode.trim().length > 0 ? 1 : 0.6)
          .onClick((): void => {
            this.handleCopy(this.groupInvitationCode);
          })

        Button('关闭')
          .layoutWeight(1)
          .height(40)
          .margin({ left: 10 })
          .backgroundColor(SilkColors.surfaceVariant)
          .fontColor(SilkColors.textSecondary)
          .onClick((): void => {
            this.showBottomSheet = false;
          })
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  private buildMembersSheet() {
    Column() {
      Row() {
        Text(`群组成员 (${this.groupMembers.length})`)
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.textPrimary)
          .layoutWeight(1)

        Text('关闭')
          .fontSize(14)
          .fontColor(SilkColors.primary)
          .onClick((): void => {
            this.showBottomSheet = false;
          })
      }
      .width('100%')
      .margin({ bottom: 12 })

      if (this.membersLoading) {
        Row() {
          LoadingProgress()
            .width(18)
            .height(18)
            .color(SilkColors.primary)
          Text('加载成员中...')
            .fontSize(14)
            .fontColor(SilkColors.textSecondary)
            .margin({ left: 8 })
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .margin({ top: 18 })
      } else if (this.membersError.length > 0) {
        Text(this.membersError)
          .fontSize(14)
          .fontColor(SilkColors.error)
      } else if (this.groupMembers.length === 0) {
        Text('暂无成员数据')
          .fontSize(14)
          .fontColor(SilkColors.textSecondary)
      } else {
        List({ space: 8 }) {
          ForEach(this.groupMembers, (member: GroupMemberProfile) => {
            ListItem() {
              Row() {
                Text(member.fullName && member.fullName.length > 0 ? member.fullName : member.id)
                  .fontSize(15)
                  .fontColor(SilkColors.textPrimary)
                  .layoutWeight(1)

                if (member.phone && member.phone.length > 0) {
                  Text(member.phone)
                    .fontSize(12)
                    .fontColor(SilkColors.textLight)
                }
              }
              .width('100%')
              .padding(12)
              .backgroundColor(SilkColors.background)
              .borderRadius(8)
            }
          }, (member: GroupMemberProfile) => member.id)
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  private buildTodoSheet() {
    Stack() {
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
              this.closeTodoSheetModals();
              this.showBottomSheet = false;
            })
        }
        .width('100%')
        .margin({ bottom: 12 })

        Text('待办分为长期模板与短期实例。短期任务执行后会锁定为已执行；若系统闹钟/日历被手动删除，重新打开待办会自动恢复可重设。长期模板可暂停/启用，并按规则生成短期实例。')
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

                    if (item.executedAt !== undefined && item.executedAt > 0) {
                      Text('已执行系统提醒')
                        .fontSize(11)
                        .fontColor(SilkColors.textLight)
                        .margin({ top: 4 })
                    } else if (item.done && (item.executedAt === undefined || item.executedAt <= 0)) {
                      Text(this.isLongTermTemplate(item) ? '长期模板（已暂停）' : '已勾选完成（未创建系统提醒）')
                        .fontSize(11)
                        .fontColor(SilkColors.textLight)
                        .margin({ top: 4 })
                    }

                    if (this.isLongTermTemplate(item)) {
                      Text(`长期模板 · ${item.repeatRule ? item.repeatRule : 'custom'}${item.repeatAnchor ? ` · ${item.repeatAnchor}` : ''}`)
                        .fontSize(11)
                        .fontColor('#8C8C8C')
                        .margin({ top: 4 })
                    } else {
                      Text('短期实例')
                        .fontSize(11)
                        .fontColor('#8C8C8C')
                        .margin({ top: 4 })
                    }

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

                  Column({ space: 6 }) {
                    if (this.todoCanExecute(item)) {
                      if (this.isTodoExecuting(item)) {
                        LoadingProgress()
                          .width(32)
                          .height(32)
                          .color(SilkColors.onPrimary)
                      } else {
                        Button(item.executedAt !== undefined && item.executedAt > 0 ? '↻ 重设' : '▶ 运行')
                          .height(32)
                          .fontSize(13)
                          .padding({ left: 12, right: 12 })
                          .backgroundColor(item.executedAt !== undefined && item.executedAt > 0 ? '#1890FF' :
                            SilkColors.primary)
                          .fontColor(SilkColors.onPrimary)
                          .borderRadius(8)
                          .onClick((): void => {
                            void this.runTodoMainAction(item);
                          })
                      }
                    } else if (item.done && item.executedAt !== undefined && item.executedAt > 0) {
                      Button('已执行')
                        .height(32)
                        .fontSize(13)
                        .padding({ left: 12, right: 12 })
                        .backgroundColor('#D9D9D9')
                        .fontColor('#666666')
                        .borderRadius(8)
                    }

                    if (this.todoCanUndoExecute(item)) {
                      if (this.isTodoExecuting(item)) {
                        LoadingProgress()
                          .width(24)
                          .height(24)
                          .color(SilkColors.primary)
                      } else {
                        Button('撤回执行')
                          .height(28)
                          .fontSize(12)
                          .padding({ left: 10, right: 10 })
                          .backgroundColor('#FFF2E8')
                          .fontColor('#D46B08')
                          .borderRadius(8)
                          .onClick((): void => {
                            void this.runUndoTodoExecution(item);
                          })
                      }
                    }
                  }

                  Text('⋯')
                    .fontSize(20)
                    .fontColor(SilkColors.textLight)
                    .margin({ left: 8 })
                    .padding({ left: 4, right: 4 })
                    .onClick((): void => {
                      this.showTodoActionMenu(item);
                    })
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

      if (this.todoMenuTarget !== null || this.editingTodo !== null || this.deletingTodoId.length > 0) {
        this.buildTodoSheetOverlay()
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private buildTodoSheetOverlay() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#80000000')
        .onClick((): void => {
          this.closeTodoSheetModals();
        })

      if (this.todoMenuTarget !== null) {
        Column() {
          Text('待办操作')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .margin({ bottom: 8 })
          Text(this.todoMenuTarget!.title)
            .fontSize(13)
            .fontColor(SilkColors.textSecondary)
            .maxLines(2)
            .margin({ bottom: 16 })
          if (this.isLongTermTemplate(this.todoMenuTarget!)) {
            Button(this.todoMenuTarget!.lifecycleState === 'cancelled' ? '启用模板' : '暂停模板')
              .width('100%')
              .margin({ bottom: 8 })
              .onClick((): void => {
                void this.runToggleTodo(this.todoMenuTarget!);
                this.todoMenuTarget = null;
              })
          }
          Button('编辑')
            .width('100%')
            .margin({ bottom: 8 })
            .onClick((): void => {
              this.openEditTodo(this.todoMenuTarget!);
            })
          Button('删除')
            .width('100%')
            .fontColor('#FF4D4F')
            .margin({ bottom: 8 })
            .onClick((): void => {
              const id = this.todoMenuTarget!.id;
              this.todoMenuTarget = null;
              this.confirmDeleteTodo(id);
            })
          Button('取消')
            .width('100%')
            .backgroundColor('#F5F5F5')
            .fontColor(SilkColors.textSecondary)
            .onClick((): void => {
              this.todoMenuTarget = null;
            })
        }
        .width('78%')
        .padding(20)
        .backgroundColor(Color.White)
        .borderRadius(12)
      } else if (this.deletingTodoId.length > 0) {
        this.buildDeleteConfirmDialog()
      } else if (this.editingTodo !== null) {
        this.buildEditTodoDialog()
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private buildEditTodoDialog() {
    Column() {
      Text('编辑待办')
        .fontSize(17)
        .fontWeight(FontWeight.Bold)
        .margin({ bottom: 16 })

      TextInput({ text: this.editTitle, placeholder: '待办标题' })
        .fontSize(15)
        .width('100%')
        .margin({ bottom: 12 })
        .onChange((val: string): void => {
          this.editTitle = val;
        })

      Text('类型')
        .fontSize(14)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 6 })
      Row({ space: 6 }) {
        Button('普通')
          .height(32)
          .fontSize(12)
          .backgroundColor(this.editActionType === 'none' ? SilkColors.primary : '#EEE')
          .fontColor(this.editActionType === 'none' ? SilkColors.onPrimary : SilkColors.textPrimary)
          .onClick((): void => {
            this.editActionType = 'none';
          })
        Button('闹钟')
          .height(32)
          .fontSize(12)
          .backgroundColor(this.editActionType === 'alarm' ? SilkColors.primary : '#EEE')
          .fontColor(this.editActionType === 'alarm' ? SilkColors.onPrimary : SilkColors.textPrimary)
          .onClick((): void => {
            this.editActionType = 'alarm';
          })
        Button('日程')
          .height(32)
          .fontSize(12)
          .backgroundColor(this.editActionType === 'calendar' ? SilkColors.primary : '#EEE')
          .fontColor(this.editActionType === 'calendar' ? SilkColors.onPrimary : SilkColors.textPrimary)
          .onClick((): void => {
            this.editActionType = 'calendar';
          })
      }
      .margin({ bottom: 12 })

      if (this.editActionType !== 'none') {
        TextInput({ text: this.editActionDetail, placeholder: '时间，如 07:00 或 明天 15:00' })
          .fontSize(14)
          .width('100%')
          .margin({ bottom: 16 })
          .onChange((val: string): void => {
            this.editActionDetail = val;
          })
      }

      Row({ space: 12 }) {
        Button('取消')
          .fontSize(14)
          .backgroundColor('#F5F5F5')
          .fontColor(SilkColors.textSecondary)
          .layoutWeight(1)
          .onClick((): void => {
            this.editingTodo = null;
          })
        Button('保存')
          .fontSize(14)
          .backgroundColor(SilkColors.primary)
          .fontColor(SilkColors.onPrimary)
          .layoutWeight(1)
          .onClick((): void => {
            void this.saveEditTodo();
          })
      }
      .width('100%')
    }
    .width('88%')
    .padding(20)
    .backgroundColor('#FFFFFF')
    .borderRadius(16)
  }

  @Builder
  private buildDeleteConfirmDialog() {
    Column() {
      Text('确认删除')
        .fontSize(17)
        .fontWeight(FontWeight.Bold)
        .margin({ bottom: 12 })
      Text('删除后不可恢复，确定要删除这条待办吗？')
        .fontSize(14)
        .fontColor(SilkColors.textSecondary)
        .margin({ bottom: 20 })
      Row({ space: 12 }) {
        Button('取消')
          .fontSize(14)
          .backgroundColor('#F5F5F5')
          .fontColor(SilkColors.textSecondary)
          .layoutWeight(1)
          .onClick((): void => {
            this.deletingTodoId = '';
          })
        Button('删除')
          .fontSize(14)
          .backgroundColor('#FF4D4F')
          .fontColor('#FFFFFF')
          .layoutWeight(1)
          .onClick((): void => {
            void this.doDeleteTodo();
          })
      }
      .width('100%')
    }
    .width('88%')
    .padding(20)
    .backgroundColor('#FFFFFF')
    .borderRadius(16)
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
