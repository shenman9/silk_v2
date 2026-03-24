/**
 * HTTP 客户端封装
 * 基于 @ohos.net.http，提供类型化的 GET/POST/PUT/DELETE 方法
 */
import { http } from '@kit.NetworkKit';
import { getBackendBaseUrl } from '../config/BackendConfig';

const HTTP_TIMEOUT = 10000; // 10 秒超时

export interface HttpResult {
  success: boolean;
  data: string;
  code: number;
}

async function request(method: http.RequestMethod, path: string, body?: string): Promise<HttpResult> {
  const httpRequest = http.createHttp();
  const url = `${getBackendBaseUrl()}${path}`;

  try {
    const response = await httpRequest.request(url, {
      method: method,
      header: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      extraData: body ?? '',
      connectTimeout: HTTP_TIMEOUT,
      readTimeout: HTTP_TIMEOUT,
    });

    const result: HttpResult = {
      success: response.responseCode >= 200 && response.responseCode < 300,
      data: response.result as string,
      code: response.responseCode,
    };
    return result;
  } catch (err) {
    console.error(`[HttpClient] ${method} ${path} failed:`, JSON.stringify(err));
    const errorResult: HttpResult = {
      success: false,
      data: JSON.stringify({ success: false, message: `网络请求失败: ${JSON.stringify(err)}` }),
      code: -1,
    };
    return errorResult;
  } finally {
    httpRequest.destroy();
  }
}

export async function httpGet(path: string): Promise<HttpResult> {
  return request(http.RequestMethod.GET, path);
}

export async function httpPost(path: string, body: string): Promise<HttpResult> {
  return request(http.RequestMethod.POST, path, body);
}

export async function httpPut(path: string, body: string): Promise<HttpResult> {
  return request(http.RequestMethod.PUT, path, body);
}

export async function httpDelete(path: string, body?: string): Promise<HttpResult> {
  return request(http.RequestMethod.DELETE, path, body);
}
