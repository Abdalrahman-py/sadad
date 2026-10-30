package com.sadad.app.ui

import kotlinx.serialization.Serializable

// The redesign's routes. Each calls one screen function in its own feature folder.
@Serializable data object Login

@Serializable data object PasswordChange

@Serializable data object AwaitingApproval

@Serializable data object Home

/** [filter] is `owes`, `credit`, `late`, `suppliers` or null for everyone. */
@Serializable data class People(val filter: String? = null)

@Serializable data class ContactDetail(val contactId: String)

/** A null [contactId] opens the form empty, to add a customer or a supplier. */
@Serializable data class ContactForm(val contactId: String? = null, val initialName: String = "")

@Serializable data class QuickAdd(val contactId: String? = null, val payment: Boolean = false)

@Serializable data object Activity

@Serializable data object Store

@Serializable data object Notifications

@Serializable data object Trash

@Serializable data object Recovery

/** The app's own update: version, notes, download progress and the install prompt. */
@Serializable data object AppUpdate
