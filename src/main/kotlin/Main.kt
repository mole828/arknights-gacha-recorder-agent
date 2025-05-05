package com.example

import api.ArkNights
import api.ArkNights.Uid
import com.example.protocol.MessageTemplate
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.pingInterval
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import kotlin.math.log
import kotlin.time.Duration.Companion.minutes

// 环境变量
val baseUrl = System.getenv()["BASE_URL"] ?: "http://localhost:8080"
val agentKey = System.getenv()["AGENT_KEY"] ?: "123"
val serverHost = System.getenv()["SERVER_HOST"] ?: "localhost"
val serverPort: Int? = System.getenv()["SERVER_PORT"]?.toInt()
val serverPath = System.getenv()["SERVER_PATH"] ?: "/agent/ws"

val ktorClient = HttpClient(CIO) {
    install(WebSockets)
}
val api = ArkNights.default()
val gachaApi = ArkNights.GachaApi.default()


enum class LoggerLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}
interface Logger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

val logger: Logger = object : Logger {
    private fun basic(msg: String, level: LoggerLevel = LoggerLevel.DEBUG) {
        val now = Clock.System.now()
        val localDateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
        println("$localDateTime $level: $msg")
    }

    override fun debug(message: String) {
        this.basic(message, LoggerLevel.DEBUG)
    }
    override fun info(message: String) {
        this.basic(message, LoggerLevel.INFO)
    }
    override fun warn(message: String) {
        this.basic(message, LoggerLevel.WARN)
    }
    override fun error(message: String) {
        this.basic(message, LoggerLevel.ERROR)
    }
}

@Serializable
data class Task (
    val uid: ArkNights.Uid?,
    val hgToken: ArkNights.HgToken,
)

@Serializable
data class TaskResult (
    val uid: ArkNights.Uid?,
    val hgToken: ArkNights.HgToken,
    val gachas: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>,
    val expired: Boolean? = false,
)

suspend fun runTaskReturn(task: Task, uidCallback: suspend (MessageTemplate.UserInfo) -> Unit={}): TaskResult {
    val hgToken = task.hgToken
    val appToken = api.grantAppToken(task.hgToken)
    val bindingList = api.bindingList(appToken)
    val accountInfo = bindingList.list.first().bindingList.first()
    val uid = accountInfo.uid
    uidCallback(MessageTemplate.UserInfo(accountInfo, hgToken))
    val u8Token = api.u8TokenByUid(appToken, uid)

    val loginCookie = api.login(u8Token)
    val pools = gachaApi.poolList(uid, u8Token, loginCookie)
    val waitInsert = mutableListOf<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>()
    pools.map { pool ->
        var history = gachaApi.history(
            loginCookie = loginCookie,
            u8Token = u8Token,
            uid = uid,
            pool = pool,
            size = 10u,
        )
        waitInsert.addAll(history.list)
        while (history.hasMore) {
            history = gachaApi.history(
                loginCookie = loginCookie,
                u8Token = u8Token,
                uid = uid,
                pool = pool,
                size = 10u,
                gachaTs = history.list.last().gachaTs,
                pos = history.list.last().pos,
            )
            waitInsert.addAll(history.list)
//                delay(5.seconds)
        }
//            delay(10.seconds)
    }
    return TaskResult(
        uid = uid,
        hgToken = task.hgToken,
        gachas = waitInsert,
        expired = false,
    )
}

suspend fun runTask(task: Task) {
    val result = try {
        runTaskReturn(task)
    } catch (e: ArkNights.HgTokenExpired) {
        logger.info("HgToken 已过期")
        ktorClient.post("$baseUrl/agent/task") {
            parameter("agentKey", agentKey)
            setBody(Json.encodeToString(TaskResult(
                uid = task.uid,
                hgToken = task.hgToken,
                gachas = emptyList(),
                expired = true,
            )))
        }
        return
    }

    val resp1 = ktorClient.post("$baseUrl/agent/task") {
        parameter("agentKey", agentKey)
        setBody(Json.encodeToString(result))
    }
    logger.info(resp1.toString())
}

suspend fun mainFunc() {
    val resp = ktorClient.get("$baseUrl/agent/task") {
        parameter("agentKey", agentKey)
    }
    val body = resp.bodyAsText()
    val task = Json.decodeFromString<Task>(body)
    logger.info(task.toString())
    runTask(task)
}

class Main

fun main() {
//    runBlocking {
//        if (loopMode) {
//            while (true) {
//                try {
//                    mainFunc()
//                } catch (e: Throwable) {
//                    e.printStackTrace()
//                }
//                delay(taskDelay.seconds)
//            }
//        } else {
//            mainFunc()
//        }
//    }
    logger.info("main()")
    runBlocking {
        try {
            logger.info("start $serverHost$serverPath")
            ktorClient.webSocket(
                method = HttpMethod.Get,
                host = serverHost,
                port = serverPort,
                path = serverPath,
            ) {
                this.pingInterval = 1.minutes
                logger.info("ws begin")
                suspend fun send(msg: MessageTemplate) {
                    logger.info("send ${msg.let {
                        val s = msg.toString()
                        if(s.length > 100) {
                            s.substring(0, 100)
                        } else s
                    }}")
                    send(Frame.Text(Json.encodeToString(msg)))
                }
                val re = async {
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                        val receivedText = frame.readText()
                        val msg = Json.decodeFromString<MessageTemplate>(receivedText)
                        logger.info(msg.toString())
                        when(msg) {
                            is MessageTemplate.Task -> {
                                val emptyResult = TaskResult(
                                    uid = msg.uid,
                                    hgToken = msg.hgToken,
                                    gachas = emptyList(),
                                )
                                val taskResult = try {
                                    runTaskReturn(Task(
                                        hgToken = msg.hgToken,
                                        uid = msg.uid
                                    ), uidCallback = {
                                        send(it)
                                        send(MessageTemplate.TokenValid(it.info.uid, msg.hgToken))
                                    })
                                } catch (e: ArkNights.HgTokenExpired) {
                                    logger.info("HgToken 已过期")
                                    send(MessageTemplate.Expired(e.hgToken))
                                    emptyResult
                                } catch (e: ArkNights.HgTokenInvalid) {
                                    logger.info("HgToken 无效")
                                    send(MessageTemplate.TokenInvalid(e.hgToken,  e.msg))
                                    emptyResult
                                }
                                val uid = taskResult.uid
                                requireNotNull(uid)
                                send(MessageTemplate.TaskResult(
                                    uid = uid,
                                    result = taskResult.gachas,
                                    hgToken = msg.hgToken,
                                ))
                            }
                            else -> {}
                        }
                    }
                }
                send(MessageTemplate.Auth(agentKey))
                re.await()
            }
            logger.info("end")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}