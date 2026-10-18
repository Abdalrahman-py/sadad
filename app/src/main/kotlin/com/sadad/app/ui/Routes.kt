package com.sadad.app.ui

import kotlinx.serialization.Serializable

// Every Phase 3 screen, as a type-safe route. Feature tickets replace only their own placeholder.
@Serializable data object Welcome

@Serializable data object Login

@Serializable data object PasswordChange

@Serializable data object AwaitingApproval

@Serializable data object Home

@Serializable data object Contacts

@Serializable data class ContactDetail(val contactId: String)

@Serializable data class ContactForm(val contactId: String? = null)

@Serializable data class DebtForm(val contactId: String)

@Serializable data class PaymentForm(val contactId: String, val debtId: String? = null)

@Serializable data object History

@Serializable data object Reports

@Serializable data object Notifications

@Serializable data object Settings

@Serializable data object Trash

@Serializable data object Recovery
