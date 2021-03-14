package com.example.githubapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IssueEvent(
    val action: String,
    val repository: Repository,
    val issue: Issue,
)

@Serializable
data class Repository(
    @SerialName("full_name")
    val fullName: String,
)

@Serializable
data class Issue(
    val id: Int,
    val number: String,
)
