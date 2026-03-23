import { Message } from './Message';
export type ConnectionState = 'disconnected' | 'connecting' | 'connected';

export interface ConnectionLogger {
  log(message: string): void;
}

export interface ConnectionListener {
  onConnectionStateChanged?(state: string): void;
  onMessageReceived?(message: Message): void;
}
