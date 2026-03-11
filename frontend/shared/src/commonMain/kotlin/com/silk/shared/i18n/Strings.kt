package com.silk.shared.i18n

import com.silk.shared.models.Language

/**
 * String resources interface
 * Implementations should provide translations for all strings
 */
interface Strings {
    // Settings page
    val settingsTitle: String
    val languageLabel: String
    val defaultAgentInstructionLabel: String
    val saveButton: String
    val cancelButton: String
    val settingsSaved: String
    val settingsSaveError: String
    
    // Language options
    val languageEnglish: String
    val languageChinese: String
    val languageEnglishNative: String  // Native name: "English"
    val languageChineseNative: String  // Native name: "中文"
    
    // Navigation buttons
    val exitButton: String
    val createButton: String
    val joinButton: String
    val contactsButton: String
    val settingsButton: String
    val logoutButton: String
    val backButton: String
    val closeButton: String
    val inviteButton: String
    val membersButton: String
    val addButton: String
    
    // Group list page
    val loading: String
    val noGroupsMessage: String
    val noGroupsSubmessage: String
    val createGroup: String
    val joinGroup: String
    val selectGroupsToExit: String
    val exitMode: String
    val exitConfirm: String
    val exiting: String
    val newMessage: String
    val groupName: String
    val invitationCode: String
    val fullName: String
    
    // Dialogs
    val createGroupTitle: String
    val joinGroupTitle: String
    val groupMembersTitle: String
    val noMembers: String
    val host: String
    val me: String
    val creating: String
    val joining: String
    
    // Chat page
    val reconnecting: String
    val connected: String
    val connecting: String
    val disconnected: String
    val sendButton: String
    val messageInputPlaceholder: String
    val noMatchingUsers: String
    
    // Chat dialogs
    val memberAdded: String
    val memberNotInContacts: String
    val sendContactRequestQuestion: String
    val contactRequestSent: String
    val sendingRequest: String
    val sendRequest: String
    val addContact: String
    val addMembersToGroup: String
    val noContactsToAdd: String
    val groupMembersTitleWithCount: String  // Format: "Group Members ({count})"
    val inviteToGroup: String
    val selectShareMethod: String
    val copyInvitationCode: String
    val invitationCodeCopied: String  // Format: "Invitation code copied: {code}"
    val copyFullMessage: String
    val fullMessageCopied: String
    val aiAssistant: String
    val currentUser: String
    val contactClickToChat: String
    val clickToAddContact: String
    
    // File dialog
    val sessionFiles: String
    val noFilesYet: String
    val useBottomButtonToUpload: String
    val download: String
    val unknownFile: String
    
    // Contacts page
    val contactsTitle: String  // "Contacts"
    val myContactsWithCount: String  // Format: "My Contacts ({count})"
    
    // Invitation code
    val invitationCodePlaceholder: String  // "Enter 6-digit invitation code"
    
    // Contact dialogs
    val phoneNumberLabel: String  // "Enter phone number"
    val phoneNumberPlaceholder: String  // "Please enter phone number"
    val searchButton: String  // "Search"
    val sendAddRequestButton: String  // "Send add request"
    val contactRequestTitle: String  // "Contact Request"
    val wantsToAddYouAsContact: String  // "Wants to add you as contact"
    val acceptButton: String  // "Accept"
    val rejectButton: String  // "Reject"
    
    // Contacts scene
    val backToGroups: String  // "← Groups"
    val pendingRequestsTitle: String  // "Pending Requests ({count})"
    val addContactButton: String  // "+ Add Contact"
    val noContactsYet: String  // "No contacts yet"
    val addFirstContact: String  // "Add first contact"
    val pendingStatus: String  // "Pending"
}

/**
 * Get the appropriate Strings instance based on language
 */
fun getStrings(language: Language): Strings {
    return when (language) {
        Language.ENGLISH -> Strings_EN
        Language.CHINESE -> Strings_ZH
    }
}
