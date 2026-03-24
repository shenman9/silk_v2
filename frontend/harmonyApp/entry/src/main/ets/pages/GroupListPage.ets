/**
 * 群组列表页面
 * 对齐 androidApp/GroupListScreen.kt
 * 功能: 群组列表、未读徽章、创建/加入/退出群组、成员管理、Silk AI 聊天、联系人/设置导航
 */
import { SilkColors } from '../common/SilkColors';
import { Group, GroupResponse, LeaveGroupResponse } from '../model/GroupModel';
import { loadUser, clearUser } from '../store/UserStore';
import { User } from '../model/UserModel';
import { UnreadCountResponse } from '../model/UnreadModel';
import { Contact, ContactResponse, GroupMember, GroupMembersResponse } from '../model/ContactModel';
import {
  getUserGroups, createGroup, joinGroup, getUnreadCounts, markGroupAsRead,
  leaveGroup, deleteGroup, getGroupMembers, getContacts,
  startPrivateChat, startSilkPrivateChat, sendContactRequestById, addMemberToGroup
} from '../service/ApiService';
import { GroupCard } from '../components/GroupCard';

interface ChatParams {
  groupId: string;
  groupName: string;
}

@Component
export struct GroupListPage {
  @Consume('navPathStack') navPathStack: NavPathStack;
  @State groups: Group[] = [];
  @State unreadCounts: Record<string, number> = {};
  @State isLoading: boolean = false;
  @State currentUser: User | null = null;

  // 创建群组对话框
  @State showCreateDialog: boolean = false;
  @State newGroupName: string = '';
  @State createLoading: boolean = false;

  // 加入群组对话框
  @State showJoinDialog: boolean = false;
  @State joinCode: string = '';
  @State joinLoading: boolean = false;

  @State dialogError: string = '';

  // 退出模式
  @State isExitMode: boolean = false;
  @State selectedGroupIds: string[] = [];
  @State exitLoading: boolean = false;

  // 成员对话框
  @State showMembersDialog: boolean = false;
  @State membersDialogGroupId: string = '';
  @State membersDialogGroupName: string = '';
  @State membersDialogHostId: string = '';
  @State members: GroupMember[] = [];
  @State membersLoading: boolean = false;
  @State memberActionMessage: string = '';

  // 添加成员对话框
  @State showAddMemberDialog: boolean = false;
  @State userContacts: Contact[] = [];
  @State addMemberLoading: boolean = false;
  @State addMemberMessage: string = '';

  // 邀请码对话框
  @State showInviteDialog: boolean = false;
  @State inviteGroupName: string = '';
  @State inviteCode: string = '';

  aboutToAppear(): void {
    this.currentUser = loadUser();
    if (this.currentUser) {
      this.refreshData();
    }
  }

  async refreshData(): Promise<void> {
    if (!this.currentUser) return;
    this.isLoading = true;

    const groupsRes: GroupResponse = await getUserGroups(this.currentUser.id);
    const unreadRes: UnreadCountResponse = await getUnreadCounts(this.currentUser.id);

    if (groupsRes.success && groupsRes.groups) {
      this.groups = groupsRes.groups;
    }
    if (unreadRes.success) {
      this.unreadCounts = unreadRes.unreadCounts;
    }
    this.isLoading = false;
  }

  async handleCreateGroup(): Promise<void> {
    if (!this.currentUser || this.newGroupName.trim() === '') return;
    this.createLoading = true;
    this.dialogError = '';

    const res = await createGroup(this.currentUser.id, this.newGroupName.trim());
    this.createLoading = false;

    if (res.success) {
      this.showCreateDialog = false;
      this.newGroupName = '';
      this.refreshData();
    } else {
      this.dialogError = res.message || '创建失败';
    }
  }

  async handleJoinGroup(): Promise<void> {
    if (!this.currentUser || this.joinCode.trim() === '') return;
    this.joinLoading = true;
    this.dialogError = '';

    const res = await joinGroup(this.currentUser.id, this.joinCode.trim());
    this.joinLoading = false;

    if (res.success) {
      this.showJoinDialog = false;
      this.joinCode = '';
      this.refreshData();
    } else {
      this.dialogError = res.message || '加入失败';
    }
  }

  async handleExitSelectedGroups(): Promise<void> {
    if (!this.currentUser || this.selectedGroupIds.length === 0) return;
    this.exitLoading = true;

    let exitCount = 0;
    for (let i = 0; i < this.selectedGroupIds.length; i++) {
      const gId = this.selectedGroupIds[i];
      const res: LeaveGroupResponse = await leaveGroup(gId, this.currentUser.id);
      if (res.success) {
        exitCount++;
      }
    }

    this.exitLoading = false;
    this.isExitMode = false;
    this.selectedGroupIds = [];
    this.refreshData();
  }

  toggleGroupSelection(groupId: string): void {
    const idx = this.selectedGroupIds.indexOf(groupId);
    if (idx >= 0) {
      const newArr: string[] = [];
      for (let i = 0; i < this.selectedGroupIds.length; i++) {
        if (this.selectedGroupIds[i] !== groupId) {
          newArr.push(this.selectedGroupIds[i]);
        }
      }
      this.selectedGroupIds = newArr;
    } else {
      this.selectedGroupIds = [...this.selectedGroupIds, groupId];
    }
  }

  async handleLogout(): Promise<void> {
    await clearUser();
    this.navPathStack.clear();
    this.navPathStack.pushPath({ name: 'login' });
  }

  enterChat(group: Group): void {
    if (this.isExitMode) {
      this.toggleGroupSelection(group.id);
      return;
    }
    if (this.currentUser) {
      markGroupAsRead(this.currentUser.id, group.id);
    }
    const params: ChatParams = {
      groupId: group.id,
      groupName: group.name,
    };
    this.navPathStack.pushPath({
      name: 'chat',
      param: params,
    });
  }

  async openSilkChat(): Promise<void> {
    if (!this.currentUser) return;
    const res = await startSilkPrivateChat(this.currentUser.id);
    if (res.success && res.group) {
      const params: ChatParams = {
        groupId: res.group.id,
        groupName: 'Silk AI',
      };
      this.navPathStack.pushPath({ name: 'chat', param: params });
    }
  }

  async loadMembers(groupId: string): Promise<void> {
    this.membersLoading = true;
    this.memberActionMessage = '';
    const res: GroupMembersResponse = await getGroupMembers(groupId);
    if (res.success) {
      this.members = res.members;
    }
    this.membersLoading = false;
  }

  showMembersForGroup(group: Group): void {
    this.membersDialogGroupId = group.id;
    this.membersDialogGroupName = group.name;
    this.membersDialogHostId = group.hostId;
    this.showMembersDialog = true;
    this.loadMembers(group.id);
  }

  async loadContactsForAdd(): Promise<void> {
    if (!this.currentUser) return;
    const res: ContactResponse = await getContacts(this.currentUser.id);
    if (res.success && res.contacts) {
      // 过滤掉已在群组中的联系人
      const memberIds: string[] = this.members.map((m: GroupMember) => m.id);
      const available: Contact[] = [];
      for (let i = 0; i < res.contacts.length; i++) {
        const c = res.contacts[i];
        if (memberIds.indexOf(c.contactId) < 0) {
          available.push(c);
        }
      }
      this.userContacts = available;
    }
  }

  async handleAddMember(contact: Contact): Promise<void> {
    this.addMemberLoading = true;
    this.addMemberMessage = '';

    const res = await addMemberToGroup(this.membersDialogGroupId, contact.contactId);
    this.addMemberLoading = false;

    if (res.success) {
      this.addMemberMessage = '已添加 ' + contact.contactName;
      this.loadMembers(this.membersDialogGroupId);
      this.loadContactsForAdd();
    } else {
      this.addMemberMessage = res.message || '添加失败';
    }
  }

  async handleMemberClick(member: GroupMember): Promise<void> {
    if (!this.currentUser) return;
    // 不处理点击自己或 Silk AI
    if (member.id === this.currentUser.id) return;
    if (member.fullName === 'Silk') return;

    const res = await startPrivateChat(this.currentUser.id, member.id);
    if (res.success && res.group) {
      this.showMembersDialog = false;
      const params: ChatParams = {
        groupId: res.group.id,
        groupName: member.fullName,
      };
      this.navPathStack.pushPath({ name: 'chat', param: params });
    } else {
      // 不是联系人，发送好友请求
      const reqRes = await sendContactRequestById(this.currentUser.id, member.id);
      if (reqRes.success) {
        this.memberActionMessage = '已发送联系人请求给 ' + member.fullName;
      } else {
        this.memberActionMessage = reqRes.message || '操作失败';
      }
    }
  }

  showInviteForGroup(group: Group): void {
    this.inviteGroupName = group.name;
    this.inviteCode = group.invitationCode;
    this.showInviteDialog = true;
  }

  build() {
    NavDestination() {
      Stack() {
        Column() {
          // 顶栏
          Row() {
            Text('SILK')
              .fontSize(24)
              .fontWeight(FontWeight.Bold)
              .fontColor(SilkColors.primary)

            Blank()

            if (this.isExitMode) {
              Button(this.exitLoading ? '退出中...' : '确认退出(' + this.selectedGroupIds.length.toString() + ')')
                .fontSize(13)
                .fontColor(SilkColors.textOnPrimary)
                .backgroundColor(SilkColors.error)
                .borderRadius(16)
                .height(32)
                .enabled(!this.exitLoading && this.selectedGroupIds.length > 0)
                .onClick(() => { this.handleExitSelectedGroups(); })

              Button('取消')
                .fontSize(13)
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .height(32)
                .margin({ left: 4 })
                .onClick(() => {
                  this.isExitMode = false;
                  this.selectedGroupIds = [];
                })
            } else {
              // 退出群组按钮
              Button('退出')
                .fontSize(13)
                .fontColor(SilkColors.error)
                .backgroundColor(Color.Transparent)
                .height(32)
                .onClick(() => {
                  this.isExitMode = true;
                  this.selectedGroupIds = [];
                })

              // 创建按钮
              Button('创建')
                .fontSize(13)
                .fontColor(SilkColors.primary)
                .backgroundColor(Color.Transparent)
                .borderWidth(1)
                .borderColor(SilkColors.primary)
                .borderRadius(16)
                .height(32)
                .margin({ left: 4 })
                .onClick(() => {
                  this.dialogError = '';
                  this.showCreateDialog = true;
                })

              // 加入按钮
              Button('加入')
                .fontSize(13)
                .fontColor(SilkColors.primary)
                .backgroundColor(Color.Transparent)
                .borderWidth(1)
                .borderColor(SilkColors.primary)
                .borderRadius(16)
                .height(32)
                .margin({ left: 4 })
                .onClick(() => {
                  this.dialogError = '';
                  this.showJoinDialog = true;
                })

              // Silk AI 聊天按钮
              Button('AI')
                .fontSize(13)
                .fontColor('#7BA8C9')
                .backgroundColor(Color.Transparent)
                .borderWidth(1)
                .borderColor('#7BA8C9')
                .borderRadius(16)
                .height(32)
                .margin({ left: 4 })
                .onClick(() => { this.openSilkChat(); })
            }
          }
          .width('100%')
          .padding({ left: 16, right: 8, top: 12, bottom: 8 })
          .backgroundColor(SilkColors.surface)

          // 二级操作栏 (非退出模式显示)
          if (!this.isExitMode) {
            Row() {
              Blank()

              Button('联系人')
                .fontSize(13)
                .fontColor(SilkColors.primary)
                .backgroundColor(Color.Transparent)
                .height(30)
                .onClick(() => { this.navPathStack.pushPath({ name: 'contacts' }); })

              Button('设置')
                .fontSize(13)
                .fontColor(SilkColors.primary)
                .backgroundColor(Color.Transparent)
                .height(30)
                .margin({ left: 4 })
                .onClick(() => { this.navPathStack.pushPath({ name: 'settings' }); })

              Button('登出')
                .fontSize(13)
                .fontColor(SilkColors.error)
                .backgroundColor(Color.Transparent)
                .height(30)
                .margin({ left: 4 })
                .onClick(() => { this.handleLogout(); })
            }
            .width('100%')
            .padding({ left: 16, right: 8, bottom: 4 })
            .backgroundColor(SilkColors.surface)
          }

          if (this.isExitMode) {
            Row() {
              Text('点击选择要退出的群组')
                .fontSize(13)
                .fontColor(SilkColors.textSecondary)
                .fontStyle(FontStyle.Italic)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 6, bottom: 6 })
            .backgroundColor(SilkColors.surfaceVariant)
          }

          Divider().color(SilkColors.divider)

          // 群组列表
          if (this.isLoading && this.groups.length === 0) {
            Column() {
              LoadingProgress().width(40).height(40).color(SilkColors.primary)
              Text('加载中...')
                .fontSize(14)
                .fontColor(SilkColors.textSecondary)
                .margin({ top: 12 })
            }
            .width('100%')
            .layoutWeight(1)
            .justifyContent(FlexAlign.Center)
          } else if (this.groups.length === 0) {
            Column() {
              Text('还没有加入任何群组')
                .fontSize(16)
                .fontColor(SilkColors.textSecondary)
              Text('创建一个新群组或加入现有群组')
                .fontSize(14)
                .fontColor(SilkColors.textSecondary)
                .margin({ top: 8 })
            }
            .width('100%')
            .layoutWeight(1)
            .justifyContent(FlexAlign.Center)
          } else {
            List({ space: 8 }) {
              ForEach(this.groups, (group: Group) => {
                ListItem() {
                  Row() {
                    if (this.isExitMode) {
                      Checkbox()
                        .select(this.selectedGroupIds.indexOf(group.id) >= 0)
                        .onChange((checked: boolean) => {
                          this.toggleGroupSelection(group.id);
                        })
                        .width(24)
                        .height(24)
                        .margin({ right: 8 })
                    }

                    Column() {
                      GroupCard({
                        groupName: group.name,
                        groupId: group.id,
                        hostName: group.hostName ?? '',
                        invitationCode: group.invitationCode,
                        unreadCount: this.unreadCounts[group.id] ?? 0,
                        onTap: () => { this.enterChat(group); },
                      })
                    }
                    .layoutWeight(1)

                    if (!this.isExitMode) {
                      Column() {
                        // 成员按钮
                        Text('👥')
                          .fontSize(18)
                          .width(36)
                          .height(36)
                          .textAlign(TextAlign.Center)
                          .borderRadius(18)
                          .backgroundColor(SilkColors.surfaceVariant)
                          .onClick(() => { this.showMembersForGroup(group); })

                        // 邀请按钮
                        Text('📨')
                          .fontSize(18)
                          .width(36)
                          .height(36)
                          .textAlign(TextAlign.Center)
                          .borderRadius(18)
                          .backgroundColor(SilkColors.surfaceVariant)
                          .margin({ top: 4 })
                          .onClick(() => { this.showInviteForGroup(group); })
                      }
                      .margin({ left: 4 })
                    }
                  }
                  .width('100%')
                  .alignItems(VerticalAlign.Center)
                }
              }, (group: Group) => group.id)
            }
            .width('100%')
            .layoutWeight(1)
            .padding({ left: 12, right: 12, top: 8 })
          }
        }
        .width('100%')
        .height('100%')
        .backgroundColor(SilkColors.background)

        // 创建群组对话框
        if (this.showCreateDialog) {
          Column() {
            Column() {
              Text('创建新群组')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 16 })

              TextInput({ placeholder: '群组名称', text: this.newGroupName })
                .onChange((value: string) => { this.newGroupName = value; })
                .width('100%')
                .height(44)
                .borderRadius(8)
                .margin({ bottom: 12 })

              if (this.dialogError !== '') {
                Text(this.dialogError)
                  .fontSize(13)
                  .fontColor(SilkColors.error)
                  .margin({ bottom: 8 })
              }

              Row() {
                Button('取消')
                  .fontColor(SilkColors.textSecondary)
                  .backgroundColor(Color.Transparent)
                  .onClick(() => { this.showCreateDialog = false; this.newGroupName = ''; })

                Button(this.createLoading ? '创建中...' : '创建')
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.primary)
                  .borderRadius(8)
                  .enabled(!this.createLoading)
                  .onClick(() => { this.handleCreateGroup(); })
              }
              .width('100%')
              .justifyContent(FlexAlign.End)
            }
            .width('80%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }

        // 加入群组对话框
        if (this.showJoinDialog) {
          Column() {
            Column() {
              Text('加入群组')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 16 })

              TextInput({ placeholder: '输入6位邀请码', text: this.joinCode })
                .onChange((value: string) => { this.joinCode = value; })
                .width('100%')
                .height(44)
                .borderRadius(8)
                .margin({ bottom: 12 })

              if (this.dialogError !== '') {
                Text(this.dialogError)
                  .fontSize(13)
                  .fontColor(SilkColors.error)
                  .margin({ bottom: 8 })
              }

              Row() {
                Button('取消')
                  .fontColor(SilkColors.textSecondary)
                  .backgroundColor(Color.Transparent)
                  .onClick(() => { this.showJoinDialog = false; this.joinCode = ''; })

                Button(this.joinLoading ? '加入中...' : '加入')
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.primary)
                  .borderRadius(8)
                  .enabled(!this.joinLoading)
                  .onClick(() => { this.handleJoinGroup(); })
              }
              .width('100%')
              .justifyContent(FlexAlign.End)
            }
            .width('80%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }

        // 成员列表对话框
        if (this.showMembersDialog) {
          Column() {
            Column() {
              Row() {
                Text('群组成员')
                  .fontSize(18)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textPrimary)

                Blank()

                // 添加成员按钮
                Button('+ 添加')
                  .fontSize(13)
                  .fontColor(SilkColors.primary)
                  .backgroundColor(Color.Transparent)
                  .borderWidth(1)
                  .borderColor(SilkColors.primary)
                  .borderRadius(14)
                  .height(28)
                  .onClick(() => {
                    this.addMemberMessage = '';
                    this.showAddMemberDialog = true;
                    this.loadContactsForAdd();
                  })
              }
              .width('100%')
              .margin({ bottom: 12 })

              if (this.membersLoading) {
                LoadingProgress().width(32).height(32).color(SilkColors.primary)
              } else if (this.members.length === 0) {
                Text('暂无成员')
                  .fontSize(14)
                  .fontColor(SilkColors.textSecondary)
                  .margin({ top: 16, bottom: 16 })
              } else {
                List({ space: 0 }) {
                  ForEach(this.members, (member: GroupMember) => {
                    ListItem() {
                      Row() {
                        // 头像
                        Text(member.fullName === 'Silk' ? '🤖' : member.fullName.charAt(0).toUpperCase())
                          .fontSize(member.fullName === 'Silk' ? 20 : 16)
                          .fontWeight(FontWeight.Bold)
                          .fontColor(member.fullName === 'Silk' ? Color.Black : SilkColors.textOnPrimary)
                          .textAlign(TextAlign.Center)
                          .width(36)
                          .height(36)
                          .borderRadius(18)
                          .backgroundColor(member.id === this.membersDialogHostId ?
                            SilkColors.primary : SilkColors.surfaceVariant)
                          .margin({ right: 10 })

                        Column() {
                          Row() {
                            Text(member.fullName)
                              .fontSize(15)
                              .fontColor(SilkColors.textPrimary)

                            if (member.id === this.membersDialogHostId) {
                              Text(' 👑')
                                .fontSize(14)
                            }
                            if (this.currentUser !== null && member.id === this.currentUser.id) {
                              Text(' (我)')
                                .fontSize(13)
                                .fontColor(SilkColors.textSecondary)
                            }
                          }

                          if (member.phone !== undefined && member.phone !== '') {
                            Text(member.phone as string)
                              .fontSize(12)
                              .fontColor(SilkColors.textSecondary)
                          }
                        }
                        .layoutWeight(1)
                        .alignItems(HorizontalAlign.Start)
                      }
                      .width('100%')
                      .padding({ top: 8, bottom: 8 })
                      .onClick(() => { this.handleMemberClick(member); })
                    }
                  }, (member: GroupMember) => member.id)
                }
                .height(this.members.length > 6 ? 280 : undefined)
              }

              if (this.memberActionMessage !== '') {
                Text(this.memberActionMessage)
                  .fontSize(13)
                  .fontColor(SilkColors.success)
                  .margin({ top: 8 })
              }

              Button('关闭')
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .margin({ top: 12 })
                .onClick(() => {
                  this.showMembersDialog = false;
                  this.members = [];
                  this.memberActionMessage = '';
                })
            }
            .width('85%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }

        // 添加成员对话框
        if (this.showAddMemberDialog) {
          Column() {
            Column() {
              Text('添加成员到群组')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 12 })

              if (this.userContacts.length === 0) {
                Text('没有可添加的联系人')
                  .fontSize(14)
                  .fontColor(SilkColors.textSecondary)
                  .margin({ top: 16, bottom: 16 })
              } else {
                List({ space: 0 }) {
                  ForEach(this.userContacts, (contact: Contact) => {
                    ListItem() {
                      Row() {
                        Text(contact.contactName.charAt(0).toUpperCase())
                          .fontSize(14)
                          .fontWeight(FontWeight.Bold)
                          .fontColor(SilkColors.textOnPrimary)
                          .textAlign(TextAlign.Center)
                          .width(32)
                          .height(32)
                          .borderRadius(16)
                          .backgroundColor(SilkColors.primary)
                          .margin({ right: 10 })

                        Text(contact.contactName)
                          .fontSize(15)
                          .fontColor(SilkColors.textPrimary)
                          .layoutWeight(1)

                        Button('添加')
                          .fontSize(12)
                          .fontColor(SilkColors.textOnPrimary)
                          .backgroundColor(SilkColors.primary)
                          .borderRadius(14)
                          .height(28)
                          .enabled(!this.addMemberLoading)
                          .onClick(() => { this.handleAddMember(contact); })
                      }
                      .width('100%')
                      .padding({ top: 6, bottom: 6 })
                    }
                  }, (contact: Contact) => contact.contactId)
                }
                .height(this.userContacts.length > 5 ? 220 : undefined)
              }

              if (this.addMemberMessage !== '') {
                Text(this.addMemberMessage)
                  .fontSize(13)
                  .fontColor(SilkColors.success)
                  .margin({ top: 8 })
              }

              Button('关闭')
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .margin({ top: 12 })
                .onClick(() => { this.showAddMemberDialog = false; })
            }
            .width('85%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }

        // 邀请码对话框
        if (this.showInviteDialog) {
          Column() {
            Column() {
              Text('邀请入群')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 16 })

              Text(this.inviteGroupName)
                .fontSize(16)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 8 })

              Row() {
                Text('邀请码: ')
                  .fontSize(15)
                  .fontColor(SilkColors.textSecondary)
                Text(this.inviteCode)
                  .fontSize(20)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.primary)
              }
              .margin({ bottom: 16 })

              Text('将此邀请码分享给好友，即可加入群组')
                .fontSize(13)
                .fontColor(SilkColors.textSecondary)
                .margin({ bottom: 16 })

              Button('关闭')
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .onClick(() => { this.showInviteDialog = false; })
            }
            .width('80%')
            .padding(24)
            .backgroundColor(SilkColors.surface)
            .borderRadius(16)
            .alignItems(HorizontalAlign.Center)
          }
          .width('100%')
          .height('100%')
          .backgroundColor('#80000000')
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
        }
      }
    }
    .hideTitleBar(true)
  }
}
