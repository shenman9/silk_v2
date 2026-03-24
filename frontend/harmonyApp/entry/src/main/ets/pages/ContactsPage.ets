/**
 * 联系人页面
 * 对齐 androidApp/ContactsScreen.kt
 * 功能: 联系人列表、待处理请求、搜索添加联系人、发起私聊
 */
import { SilkColors } from '../common/SilkColors';
import { loadUser } from '../store/UserStore';
import { User } from '../model/UserModel';
import { Contact, ContactRequest, ContactResponse, UserSearchResult, SearchedUser }
  from '../model/ContactModel';
import {
  getContacts, searchUserByPhone, sendContactRequest,
  handleContactRequest, startPrivateChat
} from '../service/ApiService';

interface ChatParams {
  groupId: string;
  groupName: string;
}

@Component
export struct ContactsPage {
  @Consume('navPathStack') navPathStack: NavPathStack;
  @State contacts: Contact[] = [];
  @State pendingRequests: ContactRequest[] = [];
  @State isLoading: boolean = false;
  @State currentUser: User | null = null;

  // 添加联系人对话框
  @State showAddDialog: boolean = false;
  @State searchPhone: string = '';
  @State searchLoading: boolean = false;
  @State foundUser: SearchedUser | null = null;
  @State searchMessage: string = '';
  @State sendingRequest: boolean = false;
  @State addDialogMessage: string = '';

  // 请求详情对话框
  @State showRequestDialog: boolean = false;
  @State selectedRequest: ContactRequest | null = null;
  @State handlingRequest: boolean = false;

  aboutToAppear(): void {
    this.currentUser = loadUser();
    if (this.currentUser) {
      this.loadContacts();
    }
  }

  async loadContacts(): Promise<void> {
    if (!this.currentUser) return;
    this.isLoading = true;

    const res: ContactResponse = await getContacts(this.currentUser.id);
    if (res.success) {
      if (res.contacts) {
        this.contacts = res.contacts;
      }
      if (res.pendingRequests) {
        this.pendingRequests = res.pendingRequests;
      }
    }
    this.isLoading = false;
  }

  async handleSearch(): Promise<void> {
    if (this.searchPhone.trim() === '') return;
    this.searchLoading = true;
    this.foundUser = null;
    this.searchMessage = '';
    this.addDialogMessage = '';

    const res: UserSearchResult = await searchUserByPhone(this.searchPhone.trim());
    this.searchLoading = false;

    if (res.found && res.user) {
      this.foundUser = res.user;
    } else {
      this.searchMessage = res.message ?? '未找到用户';
    }
  }

  async handleSendRequest(): Promise<void> {
    if (!this.currentUser || this.searchPhone.trim() === '') return;
    this.sendingRequest = true;
    this.addDialogMessage = '';

    const res: ContactResponse = await sendContactRequest(this.currentUser.id, this.searchPhone.trim());
    this.sendingRequest = false;

    if (res.success) {
      this.addDialogMessage = '联系人请求已发送';
      this.foundUser = null;
      this.searchPhone = '';
    } else {
      this.addDialogMessage = res.message || '发送请求失败';
    }
  }

  async handleAcceptRequest(request: ContactRequest): Promise<void> {
    if (!this.currentUser) return;
    this.handlingRequest = true;

    const res: ContactResponse = await handleContactRequest(request.id, this.currentUser.id, true);
    this.handlingRequest = false;

    if (res.success) {
      this.showRequestDialog = false;
      this.selectedRequest = null;
      this.loadContacts();
    }
  }

  async handleRejectRequest(request: ContactRequest): Promise<void> {
    if (!this.currentUser) return;
    this.handlingRequest = true;

    const res: ContactResponse = await handleContactRequest(request.id, this.currentUser.id, false);
    this.handlingRequest = false;

    if (res.success) {
      this.showRequestDialog = false;
      this.selectedRequest = null;
      this.loadContacts();
    }
  }

  async openPrivateChat(contact: Contact): Promise<void> {
    if (!this.currentUser) return;

    const res = await startPrivateChat(this.currentUser.id, contact.contactId);
    if (res.success && res.group) {
      const params: ChatParams = {
        groupId: res.group.id,
        groupName: contact.contactName,
      };
      this.navPathStack.pushPath({ name: 'chat', param: params });
    }
  }

  build() {
    NavDestination() {
      Stack() {
        Column() {
          // 顶栏
          Row() {
            Button('< 返回')
              .fontSize(14)
              .fontColor(SilkColors.primary)
              .backgroundColor(Color.Transparent)
              .onClick(() => { this.navPathStack.pop(); })

            Text('联系人')
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor(SilkColors.textPrimary)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)

            Button('+ 添加')
              .fontSize(14)
              .fontColor(SilkColors.primary)
              .backgroundColor(Color.Transparent)
              .borderWidth(1)
              .borderColor(SilkColors.primary)
              .borderRadius(16)
              .height(32)
              .onClick(() => {
                this.searchPhone = '';
                this.foundUser = null;
                this.searchMessage = '';
                this.addDialogMessage = '';
                this.showAddDialog = true;
              })
          }
          .width('100%')
          .padding({ left: 8, right: 12, top: 10, bottom: 10 })
          .backgroundColor(SilkColors.surface)

          Divider().color(SilkColors.divider)

          if (this.isLoading) {
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
          } else {
            List({ space: 0 }) {
              // 待处理请求区域
              if (this.pendingRequests.length > 0) {
                ListItem() {
                  Column() {
                    Text('待处理请求 (' + this.pendingRequests.length.toString() + ')')
                      .fontSize(14)
                      .fontWeight(FontWeight.Bold)
                      .fontColor(SilkColors.textSecondary)
                      .padding({ left: 16, top: 12, bottom: 8 })

                    ForEach(this.pendingRequests, (request: ContactRequest) => {
                      Row() {
                        // 头像
                        Text(request.fromUserName.charAt(0).toUpperCase())
                          .fontSize(18)
                          .fontWeight(FontWeight.Bold)
                          .fontColor(SilkColors.textOnPrimary)
                          .textAlign(TextAlign.Center)
                          .width(44)
                          .height(44)
                          .borderRadius(22)
                          .backgroundColor(SilkColors.textSecondary)
                          .margin({ right: 12 })

                        Column() {
                          Text(request.fromUserName)
                            .fontSize(15)
                            .fontWeight(FontWeight.Medium)
                            .fontColor(SilkColors.textPrimary)

                          Text(request.fromUserPhone)
                            .fontSize(13)
                            .fontColor(SilkColors.textSecondary)
                            .margin({ top: 2 })
                        }
                        .layoutWeight(1)
                        .alignItems(HorizontalAlign.Start)

                        Text('待处理')
                          .fontSize(12)
                          .fontColor(SilkColors.primary)
                          .borderWidth(1)
                          .borderColor(SilkColors.primary)
                          .borderRadius(12)
                          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      }
                      .width('100%')
                      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                      .backgroundColor(SilkColors.surface)
                      .onClick(() => {
                        this.selectedRequest = request;
                        this.showRequestDialog = true;
                      })
                    }, (request: ContactRequest) => request.id)

                    Divider().color(SilkColors.divider).margin({ top: 8 })
                  }
                }
              }

              // 联系人列表标题
              ListItem() {
                Text('我的联系人 (' + this.contacts.length.toString() + ')')
                  .fontSize(14)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textSecondary)
                  .padding({ left: 16, top: 12, bottom: 8 })
              }

              if (this.contacts.length === 0) {
                ListItem() {
                  Column() {
                    Text('还没有联系人')
                      .fontSize(16)
                      .fontColor(SilkColors.textSecondary)
                      .margin({ top: 40 })
                    Button('添加第一个联系人')
                      .fontSize(14)
                      .fontColor(SilkColors.textOnPrimary)
                      .backgroundColor(SilkColors.primary)
                      .borderRadius(20)
                      .margin({ top: 16 })
                      .onClick(() => {
                        this.searchPhone = '';
                        this.foundUser = null;
                        this.searchMessage = '';
                        this.addDialogMessage = '';
                        this.showAddDialog = true;
                      })
                  }
                  .width('100%')
                  .alignItems(HorizontalAlign.Center)
                }
              } else {
                ForEach(this.contacts, (contact: Contact) => {
                  ListItem() {
                    Row() {
                      // 头像
                      Text(contact.contactName.charAt(0).toUpperCase())
                        .fontSize(18)
                        .fontWeight(FontWeight.Bold)
                        .fontColor(SilkColors.textOnPrimary)
                        .textAlign(TextAlign.Center)
                        .width(44)
                        .height(44)
                        .borderRadius(22)
                        .backgroundColor(SilkColors.primary)
                        .margin({ right: 12 })

                      Column() {
                        Text(contact.contactName)
                          .fontSize(15)
                          .fontWeight(FontWeight.Medium)
                          .fontColor(SilkColors.textPrimary)

                        Text(contact.contactPhone)
                          .fontSize(13)
                          .fontColor(SilkColors.textSecondary)
                          .margin({ top: 2 })
                      }
                      .layoutWeight(1)
                      .alignItems(HorizontalAlign.Start)

                      // 聊天图标
                      Text('💬')
                        .fontSize(22)
                        .onClick(() => { this.openPrivateChat(contact); })
                    }
                    .width('100%')
                    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                    .backgroundColor(SilkColors.surface)
                    .borderRadius(8)
                    .onClick(() => { this.openPrivateChat(contact); })
                  }
                  .margin({ left: 12, right: 12, top: 4 })
                }, (contact: Contact) => contact.contactId)
              }
            }
            .width('100%')
            .layoutWeight(1)
            .backgroundColor(SilkColors.background)
          }
        }
        .width('100%')
        .height('100%')
        .backgroundColor(SilkColors.background)

        // 添加联系人对话框
        if (this.showAddDialog) {
          Column() {
            Column() {
              Text('添加联系人')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 16 })

              Row() {
                TextInput({ placeholder: '请输入电话号码', text: this.searchPhone })
                  .onChange((value: string) => { this.searchPhone = value; })
                  .layoutWeight(1)
                  .height(44)
                  .borderRadius(8)

                Button(this.searchLoading ? '...' : '搜索')
                  .fontSize(14)
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.primary)
                  .borderRadius(8)
                  .height(44)
                  .width(64)
                  .margin({ left: 8 })
                  .enabled(!this.searchLoading)
                  .onClick(() => { this.handleSearch(); })
              }
              .width('100%')
              .margin({ bottom: 12 })

              // 搜索结果
              if (this.foundUser !== null) {
                Row() {
                  Text((this.foundUser as SearchedUser).fullName.charAt(0).toUpperCase())
                    .fontSize(16)
                    .fontWeight(FontWeight.Bold)
                    .fontColor(SilkColors.textOnPrimary)
                    .textAlign(TextAlign.Center)
                    .width(40)
                    .height(40)
                    .borderRadius(20)
                    .backgroundColor(SilkColors.success)
                    .margin({ right: 12 })

                  Column() {
                    Text((this.foundUser as SearchedUser).fullName)
                      .fontSize(15)
                      .fontColor(SilkColors.textPrimary)
                    Text((this.foundUser as SearchedUser).phoneNumber)
                      .fontSize(13)
                      .fontColor(SilkColors.textSecondary)
                  }
                  .layoutWeight(1)
                  .alignItems(HorizontalAlign.Start)
                }
                .width('100%')
                .padding(12)
                .backgroundColor(SilkColors.surfaceVariant)
                .borderRadius(8)
                .margin({ bottom: 12 })

                Button(this.sendingRequest ? '发送中...' : '发送添加请求')
                  .fontSize(14)
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.primary)
                  .borderRadius(8)
                  .width('100%')
                  .height(40)
                  .enabled(!this.sendingRequest)
                  .margin({ bottom: 12 })
                  .onClick(() => { this.handleSendRequest(); })
              }

              if (this.searchMessage !== '') {
                Text(this.searchMessage)
                  .fontSize(13)
                  .fontColor(SilkColors.textSecondary)
                  .margin({ bottom: 8 })
              }

              if (this.addDialogMessage !== '') {
                Text(this.addDialogMessage)
                  .fontSize(13)
                  .fontColor(SilkColors.success)
                  .margin({ bottom: 8 })
              }

              Row() {
                Blank()
                Button('关闭')
                  .fontColor(SilkColors.textSecondary)
                  .backgroundColor(Color.Transparent)
                  .onClick(() => { this.showAddDialog = false; })
              }
              .width('100%')
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

        // 请求详情对话框
        if (this.showRequestDialog && this.selectedRequest !== null) {
          Column() {
            Column() {
              Text('联系人请求')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(SilkColors.textPrimary)
                .margin({ bottom: 16 })

              // 请求者信息
              Row() {
                Text((this.selectedRequest as ContactRequest).fromUserName.charAt(0).toUpperCase())
                  .fontSize(20)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(SilkColors.textOnPrimary)
                  .textAlign(TextAlign.Center)
                  .width(48)
                  .height(48)
                  .borderRadius(24)
                  .backgroundColor(SilkColors.primary)
                  .margin({ right: 12 })

                Column() {
                  Text((this.selectedRequest as ContactRequest).fromUserName)
                    .fontSize(16)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(SilkColors.textPrimary)
                  Text((this.selectedRequest as ContactRequest).fromUserPhone)
                    .fontSize(14)
                    .fontColor(SilkColors.textSecondary)
                    .margin({ top: 2 })
                  Text('希望添加您为联系人')
                    .fontSize(13)
                    .fontColor(SilkColors.textSecondary)
                    .margin({ top: 4 })
                }
                .alignItems(HorizontalAlign.Start)
              }
              .width('100%')
              .margin({ bottom: 20 })

              Row() {
                Button('拒绝')
                  .fontSize(14)
                  .fontColor(SilkColors.error)
                  .backgroundColor(Color.Transparent)
                  .borderWidth(1)
                  .borderColor(SilkColors.error)
                  .borderRadius(8)
                  .layoutWeight(1)
                  .height(40)
                  .enabled(!this.handlingRequest)
                  .onClick(() => {
                    if (this.selectedRequest !== null) {
                      this.handleRejectRequest(this.selectedRequest as ContactRequest);
                    }
                  })

                Button(this.handlingRequest ? '处理中...' : '接受')
                  .fontSize(14)
                  .fontColor(SilkColors.textOnPrimary)
                  .backgroundColor(SilkColors.success)
                  .borderRadius(8)
                  .layoutWeight(1)
                  .height(40)
                  .margin({ left: 12 })
                  .enabled(!this.handlingRequest)
                  .onClick(() => {
                    if (this.selectedRequest !== null) {
                      this.handleAcceptRequest(this.selectedRequest as ContactRequest);
                    }
                  })
              }
              .width('100%')

              Button('取消')
                .fontColor(SilkColors.textSecondary)
                .backgroundColor(Color.Transparent)
                .margin({ top: 12 })
                .onClick(() => {
                  this.showRequestDialog = false;
                  this.selectedRequest = null;
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
      }
    }
    .hideTitleBar(true)
  }
}
