package com.example.githubapp

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
import kotlinx.serialization.json.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemReader
import org.kohsuke.github.*
import java.io.StringReader
import java.security.KeyFactory
import java.security.Security
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


fun main(args: Array<String>): Unit = EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    Security.addProvider(
        BouncyCastleProvider()
    )
    install(DoubleReceive)
    install(CallLogging)

    val eventInterceptor = EventInterceptor()
    val handler = EventHandler()

    intercept(ApplicationCallPipeline.Setup) {
        eventInterceptor.process(this)
    }

    routing {
        post("/event_handler") {
            handler.handle(call)
        }
    }
}

class EventInterceptor {
    private val privateKey: RSAPrivateKey

    companion object {
        private val webhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET") ?: throw IllegalStateException()
        private val appIdentifier = System.getenv("GITHUB_APP_IDENTIFIER") ?: throw IllegalStateException()
        val githubClientKey = AttributeKey<GitHub>("GitHub")
    }

    init {
        val pemPrivateKey = System.getenv("GITHUB_PRIVATE_KEY")
            ?.replace("\\n", "\n")
            ?: throw IllegalStateException()

        privateKey = StringReader(pemPrivateKey).use {
            val pemReader = PemReader(it)
            val pemObject = pemReader.readPemObject()
            val content = pemObject.content
            val spec = PKCS8EncodedKeySpec(content)
            val rsaFact = KeyFactory.getInstance("RSA")
            rsaFact.generatePrivate(spec) as RSAPrivateKey
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun process(ctx: PipelineContext<*, ApplicationCall>): Unit = ctx.run {
        if (call.request.path() == "/event_handler") {
            try {
                val payload = call.receiveText()
                verifyWebhookSignature(this, payload)
                val jwt = createJWT()
                installAuthenticate(ctx, payload, jwt)
            } catch (e: Exception) {
                call.response.status(HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }

    private fun verifyWebhookSignature(ctx: PipelineContext<*, ApplicationCall>, payload: String): Unit = ctx.run {
        val theirSignatureHeader = call.request.headers["X-HUB-SIGNATURE-256"] ?: "sha256="
        val (method, theirDigest) = theirSignatureHeader.split("=")
        val algorithmName = if (method == "sha256") {
            "HmacSHA256"
        } else {
            throw UnsupportedOperationException()
        }

        val keySpec = SecretKeySpec(webhookSecret.toByteArray(), method)
        val mac = Mac.getInstance(algorithmName)
        mac.init(keySpec)
        val ourDigest = mac.doFinal(payload.toByteArray())
        if (ourDigest.toHexString() != theirDigest) {
            throw IllegalStateException()
        }
    }

    private fun createJWT(): String {
        val now = Date()
        return JWT.create()
            .withIssuedAt(now)
            .withExpiresAt(Date(now.time + 10 * 60 * 1000))
            .withIssuer(appIdentifier)
            .sign(Algorithm.RSA256(null, privateKey))
    }

    private fun installAuthenticate(ctx: PipelineContext<*, ApplicationCall>, payload: String, jwt: String) = ctx.run {
        val gitHubApp = GitHubBuilder()
            .withJwtToken(jwt)
            .build()

        val root = Json.parseToJsonElement(payload)
        val installationId = root.jsonObject["installation"]
            ?.jsonObject?.get("id")
            ?.jsonPrimitive
            ?.longOrNull
            ?: throw IllegalArgumentException()
        val appInstallation = gitHubApp.app.getInstallationById(installationId)
        val appInstallationToken = appInstallation.createToken().create()
        val gitHubAppInstall = GitHubBuilder()
            .withAppInstallationToken(appInstallationToken.token)
            .build()

        call.attributes.put(githubClientKey, gitHubAppInstall)
    }
}

class EventHandler {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun handle(call: ApplicationCall) = call.run {
        println("---- received event ${request.headers["X-GITHUB-EVENT"]}")

        when (request.headers["X-GITHUB-EVENT"]) {
            "issues" -> {
                val client = attributes.getOrNull(EventInterceptor.githubClientKey) ?: return@run response.status(
                    HttpStatusCode.InternalServerError
                )
                val event = json.decodeFromString<IssueEvent>(call.receiveText())
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
            .getIssue(event.issue.number.toInt())
            .addLabels("needs-response")
    }
}

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
