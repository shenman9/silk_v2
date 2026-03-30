/**
 * 跨群同步的待办项（后端从各群聊天记录提取）
 */
export interface UserTodoItem {
  id: string;
  title: string;
  sourceGroupId?: string;
  sourceGroupName?: string;
  /** none | alarm | calendar */
  actionType?: string;
  /** 如 07:00，或带日期的可解析片段 */
  actionDetail?: string;
  createdAt: number;
  updatedAt: number;
  done: boolean;
  /** 首次成功执行的时间戳（毫秒） */
  executedAt?: number;
  /** 最近一次创建的系统提醒/日历事件 ID */
  reminderId?: number;
  /** long_term_template | short_term_instance */
  taskKind?: string;
  /** yearly | monthly | workday | custom */
  repeatRule?: string;
  /** 规则锚点：07:00 / MM-DD / dayOfMonth */
  repeatAnchor?: string;
  activeFrom?: number;
  activeTo?: number;
  /** 实例回指模板 ID */
  templateId?: string;
  /** active | done | cancelled | deferred */
  lifecycleState?: string;
  closedAt?: number;
  lastEvidenceAt?: number;
  explicitIntent?: boolean;
  dateBucket?: string;
  reopenCount?: number;
}
