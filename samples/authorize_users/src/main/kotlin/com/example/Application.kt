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
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.locations.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
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
    val name: String,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String?,
)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(ContentNegotiation) {
        json()
    }

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
        static("assets") {
            files("css")
            files("js")
        }

        get<index> {
            call.sessions.get<LoginSession>()?.also {
                call.postIssuePage(it)
            } ?: run {
                call.indexPage()
            }
        }

        post<logout> {
            call.sessions.clear<LoginSession>()
            call.respondRedirect("/")
        }

        get<repositories> { path ->
            val session = call.sessions.get<LoginSession>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val response = GitHubClient(session.accessToken).use {
                it.get<ListInstalledRepositoriesResponse>("https://api.github.com/user/installations/${path.installationId}/repositories")
            }
            call.respond(response)
        }

        post("/issues") {
            val session = call.sessions.get<LoginSession>() ?: run {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            val params = call.receiveParameters()
            val response: HttpResponse = GitHubClient(session.accessToken).use {
                it.post("https://api.github.com/repos/${params["repo"]}/issues") {
                    body = """{"title":"${params["title"]}","body":"${params["body"]}"}"""
                }
            }

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

@Location("/installations/{installationId}/repositories")
data class repositories(val installationId: String)

@Location("/login/{type?}")
data class login(val type: String = "")

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

private suspend fun ApplicationCall.postIssuePage(loginSession: LoginSession) {
    val installations = GitHubClient(loginSession.accessToken).use {
        it.get<ListInstallationsResponse>("https://api.github.com/user/installations") {}.installations
    }

    respondHtml {
        head {
            title { +"index page" }
            script(type = "text/javascript", src="assets/index.js") {}
        }
        body {
            h1 {
                +"Try to post issue"
            }

            form(
                method = FormMethod.post,
                action = "/issues"
            ) {
                div {
                    label {
                        htmlFor = "installations"
                        +"Installations:"
                    }
                    select {
                        id = "installations"
                        name = "installations"
                        installations.forEach {
                            option {
                                value = it.id
                                +it.account.login
                            }
                        }
                    }
                }

                div {
                    label {
                        htmlFor = "repositories"
                        +"Repositories:"
                    }
                    select {
                        id = "repositories"
                        name = "repo"
                    }
                }

                div {
                    label {
                        htmlFor = "title"
                        +"Title:"
                    }
                    input(type = InputType.text) {
                        id = "title"
                        name = "title"
                    }
                }

                div {
                    label {
                        htmlFor = "body"
                        +"Body:"
                    }
                    textArea {
                        id = "body"
                        name = "body"
                    }
                }

                div {
                    input(type = InputType.submit) {
                        value = "Post"
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
    val oauth2 = callback as? OAuthAccessTokenResponse.OAuth2 ?: run {
        respond(HttpStatusCode.NotImplemented, "Unsupported OAuth Token Response.")
        return
    }

    GitHubClient(oauth2.accessToken).use {
        val user = it.get<Account>("https://api.github.com/user") {}
        sessions.set(
            LoginSession(
                id = user.id,
                name = user.login,
                accessToken = oauth2.accessToken,
                expiresIn = oauth2.expiresIn,
                refreshToken = oauth2.refreshToken,
            )
        )
    }

    respondRedirect("/")
}
