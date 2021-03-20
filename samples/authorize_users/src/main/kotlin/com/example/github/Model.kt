package com.example.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: Int,
    val login: String,
)

@Serializable
data class Repository(
    @SerialName("full_name")
    val fullName: String,
    @SerialName("owner")
    val owner: Account,
)

@Serializable
data class Installation(
    val id: String,
)