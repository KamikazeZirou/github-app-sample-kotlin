package com.example.github

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Closeable

class GitHubClient(val accessToken: String) : Closeable {
    val client = HttpClient(CIO).config {
        install(JsonFeature) {
            serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    override fun close() {
        client.close()
    }

    suspend inline fun <reified T> get(
        urlString: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T = client.get(urlString) {
        header("Accept", "application/vnd.github.v3+json")
        header("Authorization", "token $accessToken")
        block()
    }

    suspend inline fun <reified T> post(
        urlString: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T = client.post(urlString) {
        header("Accept", "application/vnd.github.v3+json")
        header("Authorization", "token $accessToken")
        block()
    }
}

@Serializable
data class ListInstallationsResponse(
    @SerialName("total_count")
    val totalCount: Int,
    val installations: List<Installation>
)

@Serializable
data class ListInstalledRepositoriesResponse(
    @SerialName("total_count")
    val totalCount: Int,
    val repositories: List<Repository>,
)