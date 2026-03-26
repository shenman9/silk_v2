/**
 * Logger - 文件日志工具
 * 将日志追加写入 App 沙箱目录下的 silk_debug.log
 * 宿主机可通过 hdc file recv 拉取查看
 */
import fs from '@ohos.file.fs';

export class Logger {
  private static filesDir: string = '';
  private static readonly LOG_FILE = 'silk_debug.log';

  /**
   * 初始化，由 EntryAbility 在 onCreate 时调用
   */
  static init(filesDir: string): void {
    Logger.filesDir = filesDir;
    Logger.info('Logger', `日志系统初始化, filesDir=${filesDir}`);
  }

  private static getLogPath(): string {
    return `${Logger.filesDir}/${Logger.LOG_FILE}`;
  }

  private static write(level: string, tag: string, message: string): void {
    const timestamp = new Date().toISOString();
    const line = `[${timestamp}] [${level}] [${tag}] ${message}\n`;

    // 同时输出到 console
    if (level === 'ERROR') {
      console.error(`[${tag}] ${message}`);
    } else {
      console.info(`[${tag}] ${message}`);
    }

    // 写入文件
    if (Logger.filesDir === '') {
      return;
    }
    try {
      const path = Logger.getLogPath();
      const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.APPEND | fs.OpenMode.WRITE_ONLY);
      fs.writeSync(file.fd, line);
      fs.closeSync(file);
    } catch (e) {
      console.error(`Logger write failed: ${JSON.stringify(e)}`);
    }
  }

  static info(tag: string, message: string): void {
    Logger.write('INFO', tag, message);
  }

  static error(tag: string, message: string): void {
    Logger.write('ERROR', tag, message);
  }

  static debug(tag: string, message: string): void {
    Logger.write('DEBUG', tag, message);
  }
}
