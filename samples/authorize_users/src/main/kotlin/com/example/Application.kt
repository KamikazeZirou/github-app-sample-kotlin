package com.example

import com.example.github.Account
import com.example.github.GitHubClient
import com.example.github.ListInstallationsResponse
import com.example.github.ListInstalledRepositoriesResponse
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.locations.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import kotlinx.html.*
import org.slf4j.event.Level

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)

val loginProviders = listOf(
    OAuthServerSettings.OAuth2ServerSettings(
        name = "github",
        authorizeUrl = "https://github.com/login/oauth/authorize",
        accessTokenUrl = "https://github.com/login/oauth/access_token",
        clientId = System.getenv("CLIENT_ID") ?: throw IllegalStateException(),
        clientSecret = System.getenv("CLIENT_SECRET") ?: throw IllegalStateException()
    )
).associateBy { it.name }

data class LoginSession(
    val id: Int,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String?,
)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Locations)
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }
    install(Sessions) {
        cookie<LoginSession>("LOGIN_SESSION", storage = SessionStorageMemory())
    }

    authentication {
        oauth("gitHubOAuth") {
            client = HttpClient(CIO)
            providerLookup = { loginProviders[application.locations.resolve<login>(login::class, this).type] }
            urlProvider = { p -> redirectUrl(login(p.name), false) }
        }
    }

    routing {
        get<index> {
            if (call.sessions.get<LoginSession>()?.accessToken.isNullOrEmpty()) {
                call.indexPage()
            } else {
                call.postIssuePage()
            }
        }

        post<logout> {
            call.sessions.clear<LoginSession>()
            call.respondRedirect("/")
        }

        authenticate("gitHubOAuth") {
            location<login> {
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

@Location("/logout")
class logout()

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

private suspend fun ApplicationCall.indexPage() {
    respondHtml {
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

private suspend fun ApplicationCall.postIssuePage() {
    respondHtml {
        head {
            title { +"index page" }
        }
        body {
            h1 {
                +"Try to logout"
            }
            p {
                form(
                    method = FormMethod.post,
                    action = "/logout"
                ) {
                    name = "logout_form"
                    a(href = "javascript:logout_form.submit()") {
                        +"Logout"
                    }
                }
            }
        }
    }
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

private suspend fun ApplicationCall.loggedInSuccessResponse(callback: OAuthAccessTokenResponse) {
    val oauth2 = callback as? OAuthAccessTokenResponse.OAuth2 ?: return
    println(oauth2)

    // installationのリストを取得する
    val client = GitHubClient(oauth2.accessToken)
    val repositories = client.use {
        val user = it.get<Account>("https://api.github.com/user") {}
        println(user)

        sessions.set(
            LoginSession(
                id = user.id,
                accessToken = oauth2.accessToken,
                expiresIn = oauth2.expiresIn,
                refreshToken = oauth2.refreshToken,
            )
        )

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
