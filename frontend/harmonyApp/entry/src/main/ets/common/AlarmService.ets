/**
 * AlarmService - 系统闹钟/提醒设定服务
 * 方案1: reminderAgentManager（模拟器配额为0）
 * 方案2: calendarManager 写日历事件 + 提醒
 */
import reminderAgentManager from '@ohos.reminderAgentManager';
import calendarManager from '@ohos.calendarManager';
import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import { Permissions } from '@ohos.abilityAccessCtrl';
import { getGlobalContext } from '../entryability/EntryAbility';
import { Logger } from './Logger';

const TAG = 'AlarmService';

export interface AlarmResult {
  success: boolean;
  reminderId: number;
  message: string;
}

export type SystemReminderPresence = 'exists' | 'missing' | 'unknown';

export class AlarmService {
  /**
   * 设定闹钟 — 一次性（兼容旧调用）
   */
  static async setAlarm(hour: number, minute: number): Promise<AlarmResult> {
    return AlarmService.setAlarmWithTitle(hour, minute, 'Silk 闹钟', []);
  }

  /**
   * 待办「执行」：一次性闹钟（短期实例 / 普通长期模板单次执行）
   */
  static async setAlarmForTodo(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const t = title.trim().length > 0 ? title.trim() : 'Silk 待办提醒';
    return AlarmService.setAlarmWithTitle(hour, minute, t, []);
  }

  /**
   * 待办「执行」：工作日重复闹钟（长期工作日模板）
   * daysOfWeek: [1,2,3,4,5] = 周一到周五
   */
  static async setRecurringAlarmForTodo(
    hour: number,
    minute: number,
    title: string,
    daysOfWeek: number[]
  ): Promise<AlarmResult> {
    const t = title.trim().length > 0 ? title.trim() : 'Silk 待办提醒';
    return AlarmService.setAlarmWithTitle(hour, minute, t, daysOfWeek);
  }

  /** 仅写入日历提醒（待办 actionType=calendar） */
  static async setCalendarOnlyForTodo(hour: number, minute: number, title: string): Promise<AlarmResult> {
    const t = title.trim().length > 0 ? title.trim() : 'Silk 日程';
    return AlarmService.tryCalendarEvent(hour, minute, t);
  }

  /**
   * @param repeatDays 空数组 = 一次性；非空 = 按指定星期几重复
   */
  private static async setAlarmWithTitle(
    hour: number,
    minute: number,
    title: string,
    repeatDays: number[]
  ): Promise<AlarmResult> {
    const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;
    const modeLabel = repeatDays.length > 0
      ? `重复(${repeatDays.join(',')})`
      : '一次性';
    Logger.info(TAG, `===== 设定闹钟 ${hour}:${minuteStr} [${modeLabel}] ${title} =====`);

    Logger.info(TAG, '尝试方案1: reminderAgentManager...');
    const result1 = await AlarmService.tryReminderAgent(hour, minute, title, repeatDays);
    if (result1.success) {
      return result1;
    }
    Logger.info(TAG, `方案1 失败: ${result1.message}`);

    Logger.info(TAG, `尝试方案2: calendarManager... (repeatDays=[${repeatDays.join(',')}])`);
    const result2 = await AlarmService.tryCalendarEvent(hour, minute, title, repeatDays);
    if (result2.success) {
      return result2;
    }
    Logger.info(TAG, `方案2 失败: ${result2.message}`);

    return {
      success: false,
      reminderId: -1,
      message: `方案1: ${result1.message} | 方案2: ${result2.message}`
    };
  }

  /**
   * 方案1: reminderAgentManager
   * repeatDays 为空时使用 REMINDER_TYPE_TIMER（一次性倒计时），
   * 非空时使用 REMINDER_TYPE_ALARM + daysOfWeek（按星期重复）。
   */
  private static async tryReminderAgent(
    hour: number,
    minute: number,
    title: string,
    repeatDays: number[]
  ): Promise<AlarmResult> {
    try {
      const grant = await AlarmService.requestPermissions(
        ['ohos.permission.PUBLISH_AGENT_REMINDER' as Permissions]
      );
      if (!grant) {
        return { success: false, reminderId: -1, message: '代理提醒权限被拒绝' };
      }
      const alarmTitle = title.length > 60 ? title.substring(0, 57) + '…' : title;
      const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;

      if (repeatDays.length > 0) {
        const daysText = `周${AlarmService.daysLabel(repeatDays)}`;
        const alarm: reminderAgentManager.ReminderRequestAlarm = {
          reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM,
          hour: hour,
          minute: minute,
          daysOfWeek: repeatDays,
          title: alarmTitle,
          content: `每个工作日重复 · ${daysText}`
        };
        const id = await reminderAgentManager.publishReminder(alarm);
        Logger.info(TAG, `reminderAgent 重复闹钟成功, id=${id}, days=[${repeatDays.join(',')}], daysText=${daysText}`);
        return { success: true, reminderId: id, message: `已设定重复闹钟 ${hour}:${minuteStr}（${daysText}，共${repeatDays.length}天）` };
      }

      const now = new Date();
      const target = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, minute, 0);
      if (target.getTime() <= now.getTime()) {
        target.setDate(target.getDate() + 1);
      }
      const seconds = Math.max(Math.floor((target.getTime() - now.getTime()) / 1000), 1);
      const timer: reminderAgentManager.ReminderRequestTimer = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_TIMER,
        triggerTimeInSeconds: seconds,
        title: alarmTitle
      };
      const id = await reminderAgentManager.publishReminder(timer);
      Logger.info(TAG, `reminderAgent 一次性提醒成功, id=${id}, ${seconds}s后`);
      return { success: true, reminderId: id, message: `已设定一次性闹钟 ${hour}:${minuteStr}` };
    } catch (e) {
      const err = e as Record<string, Object>;
      return { success: false, reminderId: -1, message: `${err.code} ${err.message}` };
    }
  }

  private static daysLabel(days: number[]): string {
    const names: string[] = ['一', '二', '三', '四', '五', '六', '日'];
    const labels: string[] = [];
    for (let i = 0; i < days.length; i++) {
      const d = days[i];
      if (d >= 1 && d <= 7) {
        labels.push(names[d - 1]);
      }
    }
    return labels.join('、');
  }

  /**
   * 方案2: calendarManager 创建日历事件 + 提醒
   * repeatDays 非空时为每个指定星期几各创建一个 WEEKLY 循环事件，覆盖全部工作日。
   */
  private static async tryCalendarEvent(
    hour: number, minute: number, title: string, repeatDays: number[] = []
  ): Promise<AlarmResult> {
    const context = getGlobalContext();
    if (!context) {
      return { success: false, reminderId: -1, message: 'context 为空' };
    }

    const minuteStr = minute < 10 ? `0${minute}` : `${minute}`;

    Logger.info(TAG, '申请日历读写权限...');
    const granted = await AlarmService.requestPermissions(
      ['ohos.permission.READ_CALENDAR' as Permissions, 'ohos.permission.WRITE_CALENDAR' as Permissions]
    );
    if (!granted) {
      return { success: false, reminderId: -1, message: '日历权限被拒绝' };
    }
    Logger.info(TAG, '日历权限已获取');

    try {
      const mgr = calendarManager.getCalendarManager(context);
      Logger.info(TAG, 'CalendarManager 获取成功');

      let calendar: calendarManager.Calendar;
      try {
        calendar = await mgr.getCalendar();
        Logger.info(TAG, `获取默认日历成功, account=${JSON.stringify(calendar.getAccount())}`);
      } catch (e) {
        Logger.info(TAG, '默认日历不存在，创建新日历...');
        const account: calendarManager.CalendarAccount = {
          name: 'Silk',
          type: calendarManager.CalendarType.LOCAL,
          displayName: 'Silk 提醒'
        };
        calendar = await mgr.createCalendar(account);
        Logger.info(TAG, '新日历创建成功');
      }

      const eventTitle = title.length > 80 ? title.substring(0, 77) + '…' : title;

      if (repeatDays.length > 0) {
        const eventIds: number[] = [];
        for (let d = 0; d < repeatDays.length; d++) {
          const dayOfWeek = repeatDays[d];
          const startTime = AlarmService.nextWeekdayOccurrence(dayOfWeek, hour, minute);
          const endTime = startTime + 30 * 60 * 1000;
          const dayLabel = AlarmService.daysLabel([dayOfWeek]);
          const evt: calendarManager.Event = {
            type: calendarManager.EventType.IMPORTANT,
            title: eventTitle,
            startTime: startTime,
            endTime: endTime,
            reminderTime: [0],
            recurrenceRule: { recurrenceFrequency: calendarManager.RecurrenceFrequency.WEEKLY },
            description: `Silk 工作日待办 · 周${dayLabel} ${hour}:${minuteStr}`
          };
          try {
            const eid = await calendar.addEvent(evt);
            eventIds.push(eid);
            Logger.info(TAG, `日历事件创建成功: 周${dayLabel}, eventId=${eid}, start=${startTime}`);
          } catch (evtErr) {
            const eObj = evtErr as Record<string, Object>;
            Logger.error(TAG, `创建周${dayLabel}日历事件失败: ${eObj.code} ${eObj.message}`);
          }
        }
        if (eventIds.length > 0) {
          const daysText = `周${AlarmService.daysLabel(repeatDays)}`;
          return {
            success: true,
            reminderId: eventIds[0],
            message: `闹钟已设定: ${hour}:${minuteStr} (日历重复提醒，${daysText}，共${eventIds.length}个事件)`
          };
        }
        return { success: false, reminderId: -1, message: '所有工作日日历事件均创建失败' };
      }

      const now = new Date();
      const startTime = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, minute, 0).getTime();
      const endTime = startTime + 30 * 60 * 1000;
      const event: calendarManager.Event = {
        type: calendarManager.EventType.IMPORTANT,
        title: eventTitle,
        startTime: startTime,
        endTime: endTime,
        reminderTime: [0],
        description: `Silk 待办 · ${hour}:${minuteStr}`
      };

      Logger.info(TAG, `创建日历事件: ${JSON.stringify(event)}`);
      const eventId = await calendar.addEvent(event);
      Logger.info(TAG, `日历事件创建成功, eventId=${eventId}`);

      return {
        success: true,
        reminderId: eventId,
        message: `闹钟已设定: ${hour}:${minuteStr} (日历提醒)`
      };
    } catch (e) {
      const err = e as Record<string, Object>;
      const errMsg = `${err.code} ${err.message}`;
      Logger.error(TAG, `日历事件创建失败: ${errMsg}`);
      return { success: false, reminderId: -1, message: errMsg };
    }
  }

  /**
   * 计算指定星期几的下一次出现时间戳。
   * dayOfWeek: 1=周一, ..., 7=周日 (与 reminderAgent 一致)
   */
  private static nextWeekdayOccurrence(dayOfWeek: number, hour: number, minute: number): number {
    const now = new Date();
    const jsDayTarget = dayOfWeek === 7 ? 0 : dayOfWeek;
    const jsDayNow = now.getDay();
    let daysAhead = jsDayTarget - jsDayNow;
    if (daysAhead < 0) {
      daysAhead += 7;
    }
    const target = new Date(
      now.getFullYear(), now.getMonth(), now.getDate() + daysAhead, hour, minute, 0
    );
    if (daysAhead === 0 && target.getTime() <= now.getTime()) {
      target.setDate(target.getDate() + 7);
    }
    return target.getTime();
  }

  /**
   * 申请运行时权限
   */
  private static async requestPermissions(permissions: Permissions[]): Promise<boolean> {
    const context = getGlobalContext();
    if (!context) {
      return false;
    }
    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const result = await atManager.requestPermissionsFromUser(context, permissions);
      Logger.info(TAG, `权限申请结果: ${JSON.stringify(result.authResults)}`);
      // authResults: 0 = granted, -1 = denied
      for (let i = 0; i < result.authResults.length; i++) {
        if (result.authResults[i] !== 0) {
          Logger.error(TAG, `权限 ${permissions[i]} 被拒绝`);
          return false;
        }
      }
      return true;
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `权限申请异常: ${err.code} ${err.message}`);
      return false;
    }
  }

  /** 取消已发布的提醒（reminderAgentManager） */
  static async cancelReminder(reminderId: number): Promise<boolean> {
    try {
      await reminderAgentManager.cancelReminder(reminderId);
      Logger.info(TAG, `已取消提醒 reminderId=${reminderId}`);
      return true;
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `取消提醒失败: ${err.code} ${err.message}`);
      return false;
    }
  }

  /**
   * 按标题前缀删除所有匹配的日历事件（用于撤回工作日多事件闹钟）。
   */
  static async removeCalendarEventsByTitle(titlePrefix: string): Promise<number> {
    const context = getGlobalContext();
    if (!context) {
      return 0;
    }
    try {
      const granted = await AlarmService.requestPermissions(
        ['ohos.permission.READ_CALENDAR' as Permissions, 'ohos.permission.WRITE_CALENDAR' as Permissions]
      );
      if (!granted) {
        return 0;
      }
      const mgr = calendarManager.getCalendarManager(context);
      const cal = await mgr.getCalendar();
      const events = await cal.getEvents();
      let removed = 0;
      const prefix = titlePrefix.length > 77 ? titlePrefix.substring(0, 77) : titlePrefix;
      for (let i = 0; i < events.length; i++) {
        const evt = events[i];
        if (evt.title && evt.title.startsWith(prefix) && evt.id !== undefined) {
          try {
            await cal.deleteEvent(evt.id);
            removed++;
          } catch (delErr) {
            // individual delete failure is non-fatal
          }
        }
      }
      Logger.info(TAG, `removeCalendarEventsByTitle: 删除了 ${removed} 个标题匹配的日历事件 (prefix=${prefix.substring(0, 20)}…)`);
      return removed;
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `removeCalendarEventsByTitle 失败: ${err.code} ${err.message}`);
      return 0;
    }
  }

  /** 删除日历事件 */
  static async removeCalendarEvent(eventId: number): Promise<boolean> {
    const context = getGlobalContext();
    if (!context) {
      Logger.error(TAG, 'removeCalendarEvent: context 为空');
      return false;
    }
    try {
      const granted = await AlarmService.requestPermissions(
        ['ohos.permission.READ_CALENDAR' as Permissions, 'ohos.permission.WRITE_CALENDAR' as Permissions]
      );
      if (!granted) {
        Logger.error(TAG, 'removeCalendarEvent: 日历权限被拒绝');
        return false;
      }
      const mgr = calendarManager.getCalendarManager(context);
      const calendar = await mgr.getCalendar();
      await calendar.deleteEvent(eventId);
      Logger.info(TAG, `已删除日历事件 eventId=${eventId}`);
      return true;
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `删除日历事件失败: ${err.code} ${err.message}`);
      return false;
    }
  }

  /** 查询 alarm 提醒是否仍存在（仅查询，不触发权限申请弹窗） */
  private static async hasAlarmReminder(
    expectedHour: number | null,
    expectedMinute: number | null,
    expectedTitle: string
  ): Promise<SystemReminderPresence> {
    if (expectedHour === null || expectedMinute === null) {
      return 'unknown';
    }
    try {
      const reminders = await reminderAgentManager.getValidReminders();
      for (let i = 0; i < reminders.length; i++) {
        const rec = reminders[i];
        if (rec.reminderType !== reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM) {
          continue;
        }
        const alarm = rec as reminderAgentManager.ReminderRequestAlarm;
        if (alarm.hour !== expectedHour || alarm.minute !== expectedMinute) {
          continue;
        }
        const expected = expectedTitle.trim();
        const got = alarm.title ? alarm.title.trim() : '';
        if (expected.length === 0 || got.length === 0 || got === expected) {
          return 'exists';
        }
      }
      return 'missing';
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `查询 alarm 提醒失败: ${err.code} ${err.message}`);
      return 'unknown';
    }
  }

  /** 查询日历事件是否仍存在（仅查询，不触发权限申请弹窗） */
  private static async hasCalendarEvent(eventId: number): Promise<SystemReminderPresence> {
    const context = getGlobalContext();
    if (!context) {
      return 'unknown';
    }
    try {
      const mgr = calendarManager.getCalendarManager(context);
      const calendar = await mgr.getCalendar();
      const events = await calendar.getEvents();
      for (let i = 0; i < events.length; i++) {
        const event = events[i];
        if (event.id === eventId) {
          return 'exists';
        }
      }
      return 'missing';
    } catch (e) {
      const err = e as Record<string, Object>;
      Logger.error(TAG, `查询日历事件失败: ${err.code} ${err.message}`);
      return 'unknown';
    }
  }

  /**
   * 判断待办在系统侧是否仍存在。
   * - calendar: 通过 eventId 校验
   * - alarm/none: 通过标题+时分在有效提醒中匹配
   */
  static async existsInSystem(
    reminderId: number,
    actionType: string,
    title: string,
    hour?: number,
    minute?: number
  ): Promise<SystemReminderPresence> {
    const at = actionType.trim().toLowerCase();
    if (at === 'calendar') {
      if (!(reminderId > 0)) {
        return 'missing';
      }
      return await AlarmService.hasCalendarEvent(reminderId);
    }
    const h = hour !== undefined ? hour : null;
    const m = minute !== undefined ? minute : null;
    return await AlarmService.hasAlarmReminder(h, m, title);
  }
}
