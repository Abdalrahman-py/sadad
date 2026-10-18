package com.sadad.app.data.net

import kotlinx.serialization.Serializable

@Serializable
data class Installation(
    val installationId: String,
    val generation: Int,
    val status: String,
    val label: String,
    val staffName: String,
    val permissions: Map<String, Boolean>,
)
