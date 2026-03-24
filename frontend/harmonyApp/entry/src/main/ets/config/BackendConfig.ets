/**
 * 后端地址配置
 * 通过 build-profile.json5 的 buildProfileFields 在编译时注入
 * 用户安装后即固定，无需手动配置
 */
import BuildProfile from 'BuildProfile';

const DEFAULT_BACKEND_URL: string = BuildProfile.backendBaseUrl;

export function getBackendBaseUrl(): string {
  return DEFAULT_BACKEND_URL;
}

export function getWsBaseUrl(): string {
  const httpUrl = getBackendBaseUrl();
  return httpUrl.replace('https://', 'wss://').replace('http://', 'ws://');
}
