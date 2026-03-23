/**
 * Silk 主题配置
 * 温暖的香槟金色调，优雅的丝绸质感
 */
export class SilkTheme {
  // 主色调 - 温暖的香槟金
  static readonly Primary: string = '#C9A86C';
  static readonly PrimaryDark: string = '#A8894D';
  static readonly PrimaryLight: string = '#E0CDA0';
  
  // 次要色调 - 奶油丝绸
  static readonly Secondary: string = '#E8D5B5';
  static readonly SecondaryDark: string = '#D4C4A0';
  
  // 背景色 - 温暖的奶白色
  static readonly Background: string = '#FDF8F0';
  static readonly Surface: string = '#FFFBF5';
  static readonly SurfaceElevated: string = '#FFFFFF';
  static readonly CardBackground: string = '#FFFFFF';
  
  // 文字颜色
  static readonly TextPrimary: string = '#4A4038';
  static readonly TextSecondary: string = '#8A7B6A';
  static readonly TextLight: string = '#B8A890';
  
  // 功能色
  static readonly Success: string = '#7DAE6C';
  static readonly Warning: string = '#E8B86C';
  static readonly Error: string = '#D97B7B';
  static readonly Info: string = '#7BA8C9';
  
  // 边框和分隔线
  static readonly Border: string = '#E8E0D4';
  static readonly Divider: string = '#F0E8DC';
  
  // 消息气泡颜色
  static readonly BubbleSelf: string = '#C9A86C';       // 自己的消息背景
  static readonly BubbleOther: string = '#FFFFFF';      // 他人的消息背景
  static readonly BubbleSystem: string = '#F5F5F5';     // 系统消息背景
  static readonly BubbleAI: string = '#E8F4E8';         // AI 消息背景
  
  // 间距
  static readonly SpacingXS: number = 4;
  static readonly SpacingSM: number = 8;
  static readonly SpacingMD: number = 16;
  static readonly SpacingLG: number = 24;
  static readonly SpacingXL: number = 32;
  
  // 圆角
  static readonly RadiusSM: number = 4;
  static readonly RadiusMD: number = 8;
  static readonly RadiusLG: number = 12;
  static readonly RadiusXL: number = 16;
  static readonly RadiusFull: number = 9999;
  
  // 字体大小
  static readonly FontSizeXS: number = 10;
  static readonly FontSizeSM: number = 12;
  static readonly FontSizeMD: number = 14;
  static readonly FontSizeLG: number = 16;
  static readonly FontSizeXL: number = 18;
  static readonly FontSizeXXL: number = 24;
  static readonly FontSizeDisplay: number = 32;
}

/**
 * 获取消息气泡背景色
 */
export function getBubbleBackground(isSelf: boolean, isAI: boolean, isSystem: boolean): string {
  if (isSystem) return SilkTheme.BubbleSystem;
  if (isAI) return SilkTheme.BubbleAI;
  if (isSelf) return SilkTheme.BubbleSelf;
  return SilkTheme.BubbleOther;
}

/**
 * 获取消息文字颜色
 */
export function getBubbleTextColor(isSelf: boolean): string {
  return isSelf ? '#FFFFFF' : SilkTheme.TextPrimary;
}
