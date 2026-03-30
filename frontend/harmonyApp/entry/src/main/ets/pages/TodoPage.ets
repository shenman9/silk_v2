import promptAction from '@ohos.promptAction';
import { UserTodoItem } from '../models/UserTodoItem';
import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import {
  apiClient as globalApiClient,
  UserTodoPatch,
  UserTodoRefreshStatus,
  UserTodoExtractionDiagnostics
} from '../api/ApiClient';
import { appStore as globalAppStore } from '../stores/AppStore';
import { AlarmService, AlarmResult } from '../common/AlarmService';
import { parseAlarmTimeFromText } from '../common/TodoTimeParser';
import { TodoTaskCardRow } from '../components/TodoTaskCardRow';
import { API_BASE_URL } from '../common/Constants';

@Component
export struct TodoPage {
  @State private todoItems: UserTodoItem[] = [];
  /** 与 todoItems 同步的展示分区（独立 @State，避免 @Builder + ForEach 依赖丢失） */
  @State private shortTermItems: UserTodoItem[] = [];
  @State private longTermItems: UserTodoItem[] = [];
  @State private todoRefreshing: boolean = false;
  @State private executingTodoIds: Record<string, boolean> = {};
  @State private editingTodo: UserTodoItem | null = null;
  @State private editTitle: string = '';
  @State private editActionType: string = 'none';
  @State private editActionDetail: string = '';
  @State private deletingTodoId: string = '';
  @State private todoMenuTarget: UserTodoItem | null = null;
  @State private diagnosticsText: string = '';
  /** 最近一次拉取/同步失败说明（网络或解析错误时可见） */
  @State private loadError: string = '';

  private userId: string = '';
  private readonly apiClient = globalApiClient;
  private refreshRound: number = 0;
  private static readonly BACKGROUND_POLL_TIMEOUT_MS: number = 90000;

  private getCurrentUserId(): string {
    // 与项目页一致：优先当前登录用户，避免读取到旧 AppStorage 导致串账号
    const currentUser = globalAppStore.currentUser;
    if (currentUser && currentUser.id && currentUser.id.length > 0) {
      return currentUser.id;
    }
    const storedUserId = AppStorage.get<string>('currentUserId');
    return storedUserId ? storedUserId : '';
  }

  aboutToAppear(): void {
    this.userId = this.getCurrentUserId();
    if (this.userId.length === 0) {
      appRouter.goToLogin();
      return;
    }
    void this.loadTodos();
  }

  aboutToDisappear(): void {
    // 失活时推进轮次，终止旧轮次的后台轮询回写
    this.refreshRound += 1;
    this.todoRefreshing = false;
  }

  private goBack(): void {
    appRouter.goToGroupList();
  }

  private setTodoItemsData(items: UserTodoItem[]): void {
    this.todoItems = items;
    const long: UserTodoItem[] = [];
    const short: UserTodoItem[] = [];
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (this.isLongTermTemplate(item)) {
        long.push(item);
      } else {
        short.push(item);
      }
    }
    this.longTermItems = long;
    this.shortTermItems = short;
  }

  private async hardRefreshTodoList(): Promise<void> {
    if (this.userId.length === 0) {
      return;
    }
    const latest = await this.apiClient.getUserTodos(this.userId);
    if (latest.success && latest.data) {
      this.setTodoItemsData(latest.data);
    }
  }

  private async loadTodos(): Promise<void> {
    if (this.userId.length === 0) {
      return;
    }
    const round = ++this.refreshRound;
    this.todoRefreshing = true;
    this.loadError = '';
    try {
      // 先读已有待办；若为空则做一次同步刷新（与 Chat 页侧边待办一致），避免仅异步任务时首屏一直空白
      let loaded: UserTodoItem[] = [];
      const cached = await this.apiClient.getUserTodos(this.userId);
      if (round !== this.refreshRound) {
        return;
      }
      if (!cached.success) {
        this.loadError = cached.message ? cached.message : '加载待办失败';
      } else if (cached.data) {
        this.setTodoItemsData(cached.data);
        loaded = cached.data;
      }
      if (loaded.length === 0 && this.userId.length > 0) {
        const sync = await this.apiClient.refreshUserTodos(this.userId);
        if (round !== this.refreshRound) {
          return;
        }
        if (sync.success && sync.data) {
          this.setTodoItemsData(sync.data);
          loaded = sync.data;
        } else {
          const extra = sync.message ? sync.message : '从各群同步待办失败';
          this.loadError = this.loadError.length > 0 ? `${this.loadError}；${extra}` : extra;
        }
      }
      if (loaded.length > 0 && round === this.refreshRound) {
        void this.reconcileExecutedTodosWithSystem(loaded);
      }
    } finally {
      if (round === this.refreshRound) {
        this.todoRefreshing = false;
      }
    }
    this.startBackgroundRefresh(round);
    void this.loadDiagnostics();
  }

  private startBackgroundRefresh(round: number): void {
    void this.doStartBackgroundRefresh(round);
  }

  private async doStartBackgroundRefresh(round: number): Promise<void> {
    const started = await this.apiClient.startUserTodosRefreshAsync(this.userId);
    if (round !== this.refreshRound) {
      return;
    }
    if (!started.success) {
      // 不打断当前浏览，仅提示并保留已展示结果
      this.showToast(started.message ? started.message : '启动待办刷新失败');
      this.todoRefreshing = false;
      return;
    }
    // 后台静默同步，不持续占用前台“刷新中”状态
    this.scheduleRefreshStatusPoll(round, 1200, Date.now());
  }

  private scheduleRefreshStatusPoll(round: number, delayMs: number, startedAt: number): void {
    setTimeout((): void => {
      void this.pollRefreshStatus(round, startedAt);
    }, delayMs);
  }

  private async pollRefreshStatus(round: number, startedAt: number): Promise<void> {
    if (round !== this.refreshRound) {
      return;
    }
    if (Date.now() - startedAt > TodoPage.BACKGROUND_POLL_TIMEOUT_MS) {
      // 超时后停止前台轮询，避免页面长时间处于同步态；后台任务继续跑
      if (round === this.refreshRound) {
        this.todoRefreshing = false;
      }
      return;
    }
    const res = await this.apiClient.getUserTodosRefreshAsyncStatus(this.userId);
    if (!res.success || !res.data) {
      if (round === this.refreshRound) {
        this.todoRefreshing = false;
      }
      return;
    }

    const status: UserTodoRefreshStatus = res.data;
    if (status.running) {
      this.scheduleRefreshStatusPoll(round, 1200, startedAt);
      return;
    }

    if (status.lastError && status.lastError.length > 0) {
      this.showToast(`同步失败: ${status.lastError}`);
    }

    const latest = await this.apiClient.getUserTodos(this.userId);
    if (round !== this.refreshRound) {
      return;
    }
    if (latest.success && latest.data) {
      this.setTodoItemsData(latest.data);
      if (latest.data.length > 0) {
        await this.reconcileExecutedTodosWithSystem(latest.data);
      }
    }
    this.todoRefreshing = false;
    void this.loadDiagnostics();
  }

  private async loadDiagnostics(): Promise<void> {
    if (this.userId.length === 0) {
      return;
    }
    const res = await this.apiClient.getUserTodosDiagnostics(this.userId);
    if (!res.success || !res.data) {
      this.diagnosticsText = '';
      return;
    }
    const d: UserTodoExtractionDiagnostics = res.data;
    const hits = d.matchedRecurringLines && d.matchedRecurringLines.length > 0
      ? `，命中原句: ${d.matchedRecurringLines.join(' | ')}`
      : '';
    this.diagnosticsText =
      `诊断(uid=${this.userId}): 来源=${d.source}，群=${d.totalGroups}，` +
        `LLM=${d.llmDraftCount}，兜底周期=${d.forcedRecurringCount}，最终=${d.finalDraftCount}` +
        `${hits} | 列表${this.todoItems.length}条·长期${this.longTermItems.length}·短期${this.shortTermItems.length}`;
    void this.recoverTodoListFromDiagnostics(d);
  }

  private async recoverTodoListFromDiagnostics(d: UserTodoExtractionDiagnostics): Promise<void> {
    const hitRecurring = d.forcedRecurringCount > 0;
    const hadDrafts = d.finalDraftCount > 0 || d.llmDraftCount > 0;
    const longMissing = this.longTermItems.length === 0;
    const listEmpty = this.todoItems.length === 0;
    if (!((hitRecurring && longMissing) || (hadDrafts && listEmpty) || (hitRecurring && listEmpty))) {
      return;
    }
    await this.hardRefreshTodoList();
    setTimeout((): void => {
      void this.hardRefreshTodoList();
    }, 700);
    setTimeout((): void => {
      void this.hardRefreshTodoList();
    }, 2200);
  }

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
      this.setTodoItemsData(latest);
    }
  }

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
    this.setTodoItemsData(next);
  }

  private toggleTodoItem(item: UserTodoItem): void {
    void this.runToggleTodo(item);
  }

  private isLongTermTemplate(item: UserTodoItem): boolean {
    const k = (item.taskKind ? item.taskKind : '').toLowerCase();
    if (k === 'long_term_template') {
      return true;
    }
    const rr = (item.repeatRule ? item.repeatRule : '').toLowerCase();
    if (rr === 'workday' || rr === 'yearly' || rr === 'monthly') {
      return true;
    }
    const title = item.title ? item.title : '';
    if ((/工作日|上班日|每个工作日/).test(title) &&
      (/起床|闹钟|提醒|吃药|七点|7点|7:|07:/).test(title)) {
      return true;
    }
    return false;
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
    console.info(`[TodoPage] isTodayLegalWorkday: checking ${dateText}`);
    const res = await this.apiClient.isCalendarWorkday(dateText);
    console.info(`[TodoPage] isTodayLegalWorkday: API response success=${res.success}, data=${res.data}, msg=${res.message}`);
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

  private todoNeedsTimeHint(item: UserTodoItem): boolean {
    if (item.done) {
      return false;
    }
    const at = (item.actionType ? item.actionType : 'none').toLowerCase();
    if (at !== 'alarm' && at !== 'calendar') {
      if (at !== 'none') {
        return false;
      }
      const text = `${item.title} ${item.actionDetail ? item.actionDetail : ''}`;
      if (!/点|[:：]|\d\s*点|闹|提醒|起床/.test(text)) {
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
    const isWorkday = this.isWorkdayTemplate(item);
    const isRerun = item.executedAt !== undefined && item.executedAt > 0;
    await this.withTodoRowLocked(item.id, async (): Promise<void> => {
      if (isRerun && item.reminderId !== undefined && item.reminderId > 0) {
        await AlarmService.cancelReminder(item.reminderId);
        await AlarmService.removeCalendarEvent(item.reminderId);
      }
      let res: AlarmResult;
      if (at === 'calendar') {
        res = await AlarmService.setCalendarOnlyForTodo(parsed.hour, parsed.minute, item.title);
      } else if (isLongTerm && isWorkday) {
        res = await AlarmService.setRecurringAlarmForTodo(
          parsed.hour, parsed.minute, item.title, [1, 2, 3, 4, 5]
        );
      } else {
        res = await AlarmService.setAlarmForTodo(parsed.hour, parsed.minute, item.title);
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
        this.setTodoItemsData(updateRes.data);
      } else {
        this.showToast(updateRes.message ? updateRes.message : '提醒已创建，同步待办失败');
        this.applyTodoRunSuccessLocal(item, firstExec, res.reminderId);
      }
    });
  }

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
    this.setTodoItemsData(next);
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
        this.setTodoItemsData(res.data);
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
    this.setTodoItemsData(next);
  }

  private async runToggleTodo(item: UserTodoItem): Promise<void> {
    const now = Date.now();
    let patch: UserTodoPatch;
    if (this.isLongTermTemplate(item)) {
      const curState = item.lifecycleState ? item.lifecycleState.toLowerCase() : 'active';
      const nextState = curState === 'cancelled' ? 'active' : 'cancelled';
      patch = {
        done: nextState !== 'active',
        lifecycleState: nextState,
        closedAt: nextState === 'active' ? 0 : now
      };
    } else {
      const nextDone = !item.done;
      patch = {
        done: nextDone,
        lifecycleState: nextDone ? 'done' : 'active',
        closedAt: nextDone ? now : 0
      };
    }
    const res = await this.apiClient.updateUserTodoItem(this.userId, item.id, patch);
    if (res.success && res.data) {
      this.setTodoItemsData(res.data);
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
      this.setTodoItemsData(res.data);
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
    const title = this.editTitle.trim();
    if (title.length === 0) {
      this.showToast('标题不能为空');
      return;
    }
    const patch: UserTodoPatch = {
      title: title,
      actionType: this.editActionType === 'none' ? '' : this.editActionType,
      actionDetail: this.editActionType === 'none' ? '' : this.editActionDetail.trim()
    };
    const res = await this.apiClient.updateUserTodoItem(this.userId, this.editingTodo.id, patch);
    if (res.success && res.data) {
      this.setTodoItemsData(res.data);
      this.editingTodo = null;
      this.showToast('已保存');
    } else {
      this.showToast(res.message ? res.message : '保存失败');
    }
  }

  private showTodoActionMenu(item: UserTodoItem): void {
    this.todoMenuTarget = item;
  }

  private closeModals(): void {
    this.todoMenuTarget = null;
    this.editingTodo = null;
    this.deletingTodoId = '';
  }

  private showToast(message: string): void {
    try {
      promptAction.showToast({
        message: message,
        duration: 1200
      });
    } catch (_) {
      // ignore
    }
  }

  build() {
    Stack() {
      Column() {
        Row() {
        Text('<')
          .fontSize(22)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 16 })
          .onClick((): void => {
            this.goBack();
          })

        Text('待办中心')
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(SilkColors.textPrimary)
          .margin({ left: 12 })
          .layoutWeight(1)

        if (this.todoRefreshing) {
          LoadingProgress()
            .width(18)
            .height(18)
            .color(SilkColors.primary)
            .margin({ right: 12 })
        }

        Text('刷新')
          .fontSize(14)
          .fontColor(SilkColors.primary)
          .fontWeight(FontWeight.Medium)
          .margin({ right: 16 })
          .onClick((): void => {
            void this.loadTodos();
          })
      }
        .width('100%')
        .height(56)
        .backgroundColor(SilkColors.surface)

        Scroll() {
          Column() {
            Text('短期实例用于执行与闭环；长期模板用于按规则生成实例。执行后会锁定，若系统侧删除提醒会自动回补为可重设。')
              .fontSize(12)
              .fontColor(SilkColors.textSecondary)
              .width('100%')
              .margin({ top: 12, bottom: 12 })

            if (this.loadError.length > 0) {
              Text(this.loadError)
                .fontSize(12)
                .fontColor('#CF1322')
                .width('100%')
                .margin({ bottom: 8 })
            }

            Text(`连接: ${API_BASE_URL} · 用户: ${this.userId.length > 10 ? `${this.userId.substring(0, 10)}…` : this.userId}`)
              .fontSize(10)
              .fontColor(SilkColors.textLight)
              .width('100%')
              .margin({ bottom: 8 })

            // 分区直接写在 build() 内，避免 @Builder 包裹 ForEach 时部分机型不渲染子项
            Column() {
              Row() {
                Text('短期待办')
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textPrimary)
                Text(`（${this.shortTermItems.length}）`)
                  .fontSize(12)
                  .fontColor(SilkColors.textLight)
                  .margin({ left: 6 })
              }
              .width('100%')
              .margin({ bottom: 4 })

              Text('来自群消息的当前任务实例')
                .fontSize(12)
                .fontColor(SilkColors.textSecondary)
                .width('100%')
                .margin({ bottom: 8 })

              if (this.shortTermItems.length === 0) {
                Text(this.todoRefreshing ? '正在同步中…' : '暂无内容')
                  .fontSize(13)
                  .fontColor(SilkColors.textLight)
                  .width('100%')
                  .padding({ top: 10, bottom: 10 })
              } else {
                Column({ space: 8 }) {
                  ForEach(this.shortTermItems, (item: UserTodoItem) => {
                    TodoTaskCardRow({
                      item: item,
                      executing: this.isTodoExecuting(item),
                      isLongTerm: this.isLongTermTemplate(item),
                      canExecute: this.todoCanExecute(item),
                      canUndoExecute: this.todoCanUndoExecute(item),
                      undoing: this.isTodoExecuting(item),
                      needsTimeHint: this.todoNeedsTimeHint(item),
                      onToggle: (): void => {
                        this.toggleTodoItem(item);
                      },
                      onRun: (): void => {
                        void this.runTodoMainAction(item);
                      },
                      onUndo: (): void => {
                        void this.runUndoTodoExecution(item);
                      },
                      onMenu: (): void => {
                        this.showTodoActionMenu(item);
                      }
                    })
                  }, (item: UserTodoItem) => `${item.id}_${item.updatedAt}`)
                }
                .width('100%')
              }
            }
            .width('100%')
            .padding(12)
            .backgroundColor(SilkColors.surface)
            .borderRadius(12)
            .margin({ bottom: 12 })

            Column() {
              Row() {
                Text('长期待办模板')
                  .fontSize(16)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textPrimary)
                Text(`（${this.longTermItems.length}）`)
                  .fontSize(12)
                  .fontColor(SilkColors.textLight)
                  .margin({ left: 6 })
              }
              .width('100%')
              .margin({ bottom: 4 })

              Text('纪念日/工作日习惯等重复规则')
                .fontSize(12)
                .fontColor(SilkColors.textSecondary)
                .width('100%')
                .margin({ bottom: 8 })

              if (this.longTermItems.length === 0) {
                Text(this.todoRefreshing ? '正在同步中…' : '暂无内容')
                  .fontSize(13)
                  .fontColor(SilkColors.textLight)
                  .width('100%')
                  .padding({ top: 10, bottom: 10 })
              } else {
                Column({ space: 8 }) {
                  ForEach(this.longTermItems, (item: UserTodoItem) => {
                    TodoTaskCardRow({
                      item: item,
                      executing: this.isTodoExecuting(item),
                      isLongTerm: this.isLongTermTemplate(item),
                      canExecute: this.todoCanExecute(item),
                      canUndoExecute: this.todoCanUndoExecute(item),
                      undoing: this.isTodoExecuting(item),
                      needsTimeHint: this.todoNeedsTimeHint(item),
                      onToggle: (): void => {
                        this.toggleTodoItem(item);
                      },
                      onRun: (): void => {
                        void this.runTodoMainAction(item);
                      },
                      onUndo: (): void => {
                        void this.runUndoTodoExecution(item);
                      },
                      onMenu: (): void => {
                        this.showTodoActionMenu(item);
                      }
                    })
                  }, (item: UserTodoItem) => `${item.id}_${item.updatedAt}`)
                }
                .width('100%')
              }
            }
            .width('100%')
            .padding(12)
            .backgroundColor(SilkColors.surface)
            .borderRadius(12)
            .margin({ bottom: 12 })

            if (this.diagnosticsText.length > 0) {
              Text(this.diagnosticsText)
                .fontSize(11)
                .fontColor(SilkColors.textLight)
                .width('100%')
                .margin({ top: 4, bottom: 12 })
            }
          }
          .width('100%')
          .padding({ left: 12, right: 12, bottom: 12 })
        }
        .layoutWeight(1)
      }
      .width('100%')
      .height('100%')
      .backgroundColor(SilkColors.background)

      if (this.todoMenuTarget !== null || this.editingTodo !== null || this.deletingTodoId.length > 0) {
        this.buildOverlay()
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private buildOverlay() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#80000000')
        .onClick((): void => {
          this.closeModals();
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
}
