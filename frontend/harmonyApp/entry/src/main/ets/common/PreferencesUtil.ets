/**
 * 轻量级键值存储封装。
 * 当前实现以进程内缓存为主，满足编译与运行期会话读写需求。
 */
export class PreferencesUtil {
  private static readonly storage: Map<string, string> = new Map<string, string>();

  static async putString(key: string, value: string): Promise<void> {
    PreferencesUtil.storage.set(key, value);
  }

  static async getString(key: string): Promise<string> {
    const value: string | undefined = PreferencesUtil.storage.get(key);
    return value ? value : '';
  }

  static async remove(key: string): Promise<void> {
    PreferencesUtil.storage.delete(key);
  }
}
