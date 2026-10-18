package com.sadad.app.data.net

import kotlinx.serialization.Serializable

@Serializable
data class LeasePayload(
    val schemaVersion: Int,
    val projectRef: String,
    val storeId: Long,
    val userId: String,
    val installationId: String,
    val generation: Int,
    val issuedAt: Long,
    val expiresAt: Long,
    val allowed: List<String>,
)

/** [raw] is the unchanged base64url payload; verify its decoded bytes, never re-encoded JSON. */
@Serializable data class Lease(val payload: LeasePayload, val raw: String, val signature: String)
