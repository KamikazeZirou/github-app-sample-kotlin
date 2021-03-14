package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


fun main() {
    val eventInterceptor = EventInterceptor()
    val handler = EventHandler()

    @Suppress("BlockingMethodInNonBlockingContext")
    val server = embeddedServer(Netty, port = 8080) {
        intercept(ApplicationCallPipeline.Setup) {
            eventInterceptor.process(this)
        }

        routing {
            post("/event_handler") {
                handler.handle(call)
            }

            get("/") {
                val format = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                val event = format.decodeFromString<IssueEvent>(
                    """
                        {
                          "action": "opened",
                          "issue": {
                            "id": 1,
                            "url": "https://api.github.com/repos/octocat/Hello-World/issues/1347",
                            "number": 1347
                          },
                          "repository" : {
                            "id": 1296269,
                            "full_name": "octocat/Hello-World",
                            "owner": {
                              "login": "octocat",
                              "id": 1
                            }
                          },
                          "sender": {
                            "login": "octocat",
                            "id": 1
                          }
                        }
                """.trimIndent()
                )
                println(event)
                call.respondText("OK")
            }
        }
    }
    server.start(wait = true)
}

@Suppress("unused") // Referenced in application.conf
fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
}

class EventInterceptor {
    companion object {
        private val webhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET") ?: throw IllegalStateException()
        private val privateKey = System.getenv("GITHUB_PRIVATE_KEY") ?: throw IllegalStateException()
        private val appIdentifier = System.getenv("GITHUB_APP_IDENTIFIER") ?: throw IllegalStateException()
        val payloadKey = AttributeKey<String>("payload")
        val githubKey = AttributeKey<GitHub>("GitHub")
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun process(ctx: PipelineContext<*, ApplicationCall>): Unit = ctx.run {
        if (call.request.path() == "/event_handler") {
            try {
                val payload = call.receiveText()
                verifyWebhookSignature(this, payload)
                val jwt = createJWT()
                val gh = GitHubBuilder().withJwtToken(jwt).build()
                call.attributes.put(payloadKey, payload)
                call.attributes.put(githubKey, gh)
            } catch (e: Exception) {
                call.response.status(HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }

    private fun verifyWebhookSignature(ctx: PipelineContext<*, ApplicationCall>, payload: String): Unit = ctx.run {
        val theirSignatureHeader = call.request.headers["HTTP_X_HUB_SIGNATURE"] ?: "sha1="
        val (method, theirDigest) = theirSignatureHeader.split("=")

        val keySpec = SecretKeySpec(webhookSecret.toByteArray(), method)
        val mac = Mac.getInstance(method)
        mac.init(keySpec)
        val ourDigest = mac.doFinal(payload.toByteArray())
        if (ourDigest.toString() != theirDigest) {
            throw IllegalStateException()
        }
    }

    private fun createJWT(): String {
        val spec = PKCS8EncodedKeySpec(privateKey.toByteArray())
        val rsaFact = KeyFactory.getInstance("RSA")
        val key = rsaFact.generatePrivate(spec) as RSAPrivateKey
        val now = Date()
        return JWT.create()
            .withIssuedAt(now)
            .withExpiresAt(Date(now.time + 10 * 60 * 1000))
            .withIssuer(appIdentifier)
            .sign(Algorithm.RSA256(null, key))
    }
}

class EventHandler {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun handle(call: ApplicationCall) = call.run {
        println("---- received event ${request.headers["HTTP_X_GITHUB_EVENT"]}")

        when (request.headers["HTTP_X_GITHUB_EVENT"]) {
            "issues" -> {
                val client =
                    attributes.getOrNull(EventInterceptor.githubKey) ?: return@run response.status(
                        HttpStatusCode.InternalServerError
                    )
                val payload =
                    attributes.getOrNull(EventInterceptor.payloadKey) ?: return@run response.status(
                        HttpStatusCode.InternalServerError
                    )
                val event = json.decodeFromString<IssueEvent>(payload)
                println("----    action ${event.action}")

                if (event.action == "opened") {
                    handleIssueOpenedEvent(call, client, event)
                }

                return@run response.status(HttpStatusCode.OK)
            }
        }
    }

    private fun handleIssueOpenedEvent(call: ApplicationCall, client: GitHub, event: IssueEvent) = call.run {
        client
            .getRepository(event.repository.fullName)
            .getIssue(event.issue.id)
            .addLabels("needs-response")
    }
}