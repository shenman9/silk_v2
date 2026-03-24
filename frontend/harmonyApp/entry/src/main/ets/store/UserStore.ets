/**
 * 用户持久化存储
 * 基于 @ohos.data.preferences，等价于 Android SharedPreferences
 */
import { preferences } from '@kit.ArkData';
import { User } from '../model/UserModel';
import { common } from '@kit.AbilityKit';

const PREFERENCES_NAME = 'silk_user_store';
const KEY_USER_ID = 'user_id';
const KEY_LOGIN_NAME = 'user_login_name';
const KEY_FULL_NAME = 'user_full_name';
const KEY_PHONE = 'user_phone_number';

let dataPreferences: preferences.Preferences | null = null;

export function initUserStore(context: common.UIAbilityContext): void {
  try {
    dataPreferences = preferences.getPreferencesSync(context, { name: PREFERENCES_NAME });
  } catch (e) {
    console.error('[UserStore] init failed:', JSON.stringify(e));
  }
}

export async function saveUser(user: User): Promise<void> {
  if (!dataPreferences) {
    return;
  }
  try {
    dataPreferences.putSync(KEY_USER_ID, user.id);
    dataPreferences.putSync(KEY_LOGIN_NAME, user.loginName);
    dataPreferences.putSync(KEY_FULL_NAME, user.fullName);
    dataPreferences.putSync(KEY_PHONE, user.phoneNumber);
    await dataPreferences.flush();
  } catch (e) {
    console.error('[UserStore] saveUser failed:', JSON.stringify(e));
  }
}

export function loadUser(): User | null {
  if (!dataPreferences) {
    return null;
  }
  try {
    const userId = dataPreferences.getSync(KEY_USER_ID, '') as string;
    if (userId === '') {
      return null;
    }
    const result: User = {
      id: userId,
      loginName: dataPreferences.getSync(KEY_LOGIN_NAME, '') as string,
      fullName: dataPreferences.getSync(KEY_FULL_NAME, '') as string,
      phoneNumber: dataPreferences.getSync(KEY_PHONE, '') as string,
    };
    return result;
  } catch (e) {
    console.error('[UserStore] loadUser failed:', JSON.stringify(e));
    return null;
  }
}

export function hasUser(): boolean {
  if (!dataPreferences) {
    return false;
  }
  try {
    const userId = dataPreferences.getSync(KEY_USER_ID, '') as string;
    return userId !== '';
  } catch (e) {
    return false;
  }
}

export async function clearUser(): Promise<void> {
  if (!dataPreferences) {
    return;
  }
  try {
    dataPreferences.deleteSync(KEY_USER_ID);
    dataPreferences.deleteSync(KEY_LOGIN_NAME);
    dataPreferences.deleteSync(KEY_FULL_NAME);
    dataPreferences.deleteSync(KEY_PHONE);
    await dataPreferences.flush();
  } catch (e) {
    console.error('[UserStore] clearUser failed:', JSON.stringify(e));
  }
}
