package com.example

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.locations.*
import io.ktor.request.*
import io.ktor.routing.*
import kotlinx.html.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import org.slf4j.event.Level
import java.io.Closeable

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)


/*
val appIdentifier = System.getenv("GITHUB_APP_IDENTIFIER") ?: throw IllegalStateException()
private val privateKey: RSAPrivateKey = fun(): RSAPrivateKey {
    val pemPrivateKey = System.getenv("GITHUB_PRIVATE_KEY")
        ?.replace("\\n", "\n")
        ?: throw IllegalStateException()

    return StringReader(pemPrivateKey).use {
        val pemReader = PemReader(it)
        val pemObject = pemReader.readPemObject()
        val content = pemObject.content
        val spec = PKCS8EncodedKeySpec(content)
        val rsaFact = KeyFactory.getInstance("RSA")
        rsaFact.generatePrivate(spec) as RSAPrivateKey
    }
}()
 */

val loginProviders = listOf(
    OAuthServerSettings.OAuth2ServerSettings(
        name = "github",
        authorizeUrl = "https://github.com/login/oauth/authorize",
        accessTokenUrl = "https://github.com/login/oauth/access_token",
        clientId = System.getenv("CLIENT_ID") ?: throw IllegalStateException(),
        clientSecret = System.getenv("CLIENT_SECRET") ?: throw IllegalStateException()
    )
).associateBy { it.name }

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Locations)
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    authentication {
        oauth("gitHubOAuth") {
            client = HttpClient(Apache)
            providerLookup = { loginProviders[application.locations.resolve<login>(login::class, this).type] }
            urlProvider = { p -> redirectUrl(login(p.name), false) }
        }
    }

    routing {
        get<index> {
            call.respondHtml {
                head {
                    title { +"index page" }
                }
                body {
                    h1 {
                        +"Try to login"
                    }
                    p {
                        a(href = locations.href(login())) {
                            +"Login"
                        }
                    }
                }
            }
        }

        authenticate("gitHubOAuth") {
            location<login>() {
                param("error") {
                    handle {
                        call.loginFailedPage(call.parameters.getAll("error").orEmpty())
                    }
                }

                handle {
                    val principal = call.authentication.principal<OAuthAccessTokenResponse>()
                    if (principal != null) {
                        call.loggedInSuccessResponse(principal)
                    } else {
                        call.loginPage()
                    }
                }
            }
        }
    }
}

@Location("/")
class index()

@Location("/login/{type?}")
class login(val type: String = "")

private fun <T : Any> ApplicationCall.redirectUrl(t: T, secure: Boolean = true): String {
    val hostPort = request.host()!! + request.port().let { port -> if (port == 80) "" else ":$port" }
    val protocol = when {
        secure -> "https"
        else -> "http"
    }
    return "$protocol://$hostPort${application.locations.href(t)}"
}

private suspend fun ApplicationCall.loginPage() {
    respondHtml {
        head {
            title { +"Login with" }
        }
        body {
            h1 {
                +"Login with:"
            }

            for (p in loginProviders) {
                p {
                    a(href = application.locations.href(login(p.key))) {
                        +p.key
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.loginFailedPage(errors: List<String>) {
    respondHtml {
        head {
            title { +"Login with" }
        }
        body {
            h1 {
                +"Login error"
            }

            for (e in errors) {
                p {
                    +e
                }
            }
        }
    }
}

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

private suspend fun ApplicationCall.loggedInSuccessResponse(callback: OAuthAccessTokenResponse) {
    val oauth2 = callback as? OAuthAccessTokenResponse.OAuth2 ?: return
    println(oauth2)

    // installationのリストを取得する
    val client = GitHubClient(oauth2.accessToken)
    val repositories = client.use {
        val user = it.get<Account>("https://api.github.com/user") {}
        println(user)

        // TODO Save (User, AccessToken, RefreshToken)

        // Installationの取得
        val response = it.get<ListInstallationsResponse>("https://api.github.com/user/installations") {}
        println(response)

        val installation = response.installations.first()
        val response2 =
            it.get<ListInstalledRepositoriesResponse>("https://api.github.com/user/installations/${installation.id}/repositories") {}
        print(response2)

        // リポジトリにIssueを登録
        val repo = response2.repositories.first()
        val response3: HttpResponse = it.post("https://api.github.com/repos/${repo.fullName}/issues") {
            body = """{"title":"title","body":"body"}"""
        }
        print(response3)

        response2.repositories
    }

    respondHtml {
        head {
            title { +"Logged in" }
        }
        body {
            h1 {
                +"You are logged in"
            }
            p {
                +"Your token is $callback"
            }

            ul {
                repositories.forEach {
                    li {
                        +it.fullName
                    }
                }
            }
        }

    }
}
