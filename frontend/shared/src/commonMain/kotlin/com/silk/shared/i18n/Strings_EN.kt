package com.silk.shared.i18n

/**
 * English string resources
 */
object Strings_EN : Strings {
    override val settingsTitle = "Settings"
    override val languageLabel = "Language"
    override val defaultAgentInstructionLabel = "Default Agent Instruction"
    override val saveButton = "Save"
    override val cancelButton = "Cancel"
    override val settingsSaved = "Settings saved successfully"
    override val settingsSaveError = "Failed to save settings"
    
    override val languageEnglish = "English"
    override val languageChinese = "Chinese"
    override val languageEnglishNative = "English"
    override val languageChineseNative = "中文"
    
    // Navigation buttons
    override val exitButton = "Exit"
    override val createButton = "Create"
    override val joinButton = "Join"
    override val contactsButton = "Contacts"
    override val settingsButton = "Settings"
    override val logoutButton = "Logout"
    override val backButton = "Back"
    override val closeButton = "Close"
    override val inviteButton = "Invite"
    override val membersButton = "Members"
    override val addButton = "Add"
    
    // Group list page
    override val loading = "Loading..."
    override val noGroupsMessage = "You haven't joined any groups"
    override val noGroupsSubmessage = "Create a new group or join an existing group"
    override val createGroup = "Create Group"
    override val joinGroup = "Join Group"
    override val selectGroupsToExit = "Click to select groups to exit"
    override val exitMode = "Exit"
    override val exitConfirm = "Exit"
    override val exiting = "Exiting..."
    override val newMessage = "New message"
    override val groupName = "Group Name"
    override val invitationCode = "Invitation Code"
    override val fullName = "Full Name"
    
    // Dialogs
    override val createGroupTitle = "Create New Group"
    override val joinGroupTitle = "Join Group"
    override val groupMembersTitle = "Group Members"
    override val noMembers = "No members"
    override val host = "(Host)"
    override val me = "(Me)"
    override val creating = "Creating..."
    override val joining = "Joining..."
    
    // Chat page
    override val reconnecting = "Reconnect"
    override val connected = "Connected"
    override val connecting = "Connecting..."
    override val disconnected = "Disconnected"
    override val sendButton = "Send"
    override val messageInputPlaceholder = "Type message... (@silk to ask AI, Enter to send)"
    override val noMatchingUsers = "No matching users"
    
    // Chat dialogs
    override val memberAdded = "Member added: {name}"  // {name} will be replaced
    override val memberNotInContacts = "{name} is not in your contacts list."  // {name} will be replaced
    override val sendContactRequestQuestion = "Send contact request?"
    override val contactRequestSent = "Contact request sent"
    override val sendingRequest = "Sending..."
    override val sendRequest = "Send Request"
    override val addContact = "Add Contact"
    override val addMembersToGroup = "Add Members to Group"
    override val noContactsToAdd = "No contacts to add\n(All contacts are already in the group)"
    override val groupMembersTitleWithCount = "Group Members ({count})"  // {count} will be replaced
    override val inviteToGroup = "Invite to Group"
    override val selectShareMethod = "Select sharing method:"
    override val copyInvitationCode = "Copy Invitation Code"
    override val invitationCodeCopied = "Invitation code copied: {code}"  // {code} will be replaced
    override val copyFullMessage = "Copy Full Message"
    override val fullMessageCopied = "Full invitation message copied to clipboard"
    override val aiAssistant = "AI Assistant"
    override val currentUser = "Current User"
    override val contactClickToChat = "Contact · Click to chat"
    override val clickToAddContact = "Click to add contact"
    
    // File dialog
    override val sessionFiles = "Session Files"
    override val noFilesYet = "No files yet"
    override val useBottomButtonToUpload = "Use the 📎 button at the bottom to upload files"
    override val download = "Download"
    override val unknownFile = "Unknown file"
    
    // Contacts page
    override val contactsTitle = "Contacts"
    override val myContactsWithCount = "My Contacts ({count})"  // {count} will be replaced
    
    // Invitation code
    override val invitationCodePlaceholder = "Enter 6-digit invitation code"
    
    // Contact dialogs
    override val phoneNumberLabel = "Enter phone number"
    override val phoneNumberPlaceholder = "Please enter phone number"
    override val searchButton = "Search"
    override val sendAddRequestButton = "Send add request"
    override val contactRequestTitle = "Contact Request"
    override val wantsToAddYouAsContact = "Wants to add you as contact"
    override val acceptButton = "Accept"
    override val rejectButton = "Reject"
    
    // Contacts scene
    override val backToGroups = "← Groups"
    override val pendingRequestsTitle = "Pending Requests ({count})"  // {count} will be replaced
    override val addContactButton = "+ Add Contact"
    override val noContactsYet = "No contacts yet"
    override val addFirstContact = "Add first contact"
    override val pendingStatus = "Pending"
    
    // Silk AI Chat
    override val chatWithSilk = "Chat with Silk"
    override val silkChatInputPlaceholder = "Ask Silk anything... (Enter to send)"
}
