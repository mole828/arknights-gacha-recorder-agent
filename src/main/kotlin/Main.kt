package com.example

import api.ArkNights
import com.example.protocol.MessageTemplate
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpMethod
import io.ktor.util.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.text.get
import kotlin.time.Duration.Companion.seconds

// 环境变量
val loopMode = System.getenv()["LOOP_MODE"] ?.toBoolean() ?: false
val baseUrl = System.getenv()["BASE_URL"] ?: "http://localhost:8080"
val agentKey = System.getenv()["AGENT_KEY"] ?: "123"
val taskDelay = System.getenv()["TASK_DELAY"]?.toInt() ?: 60


val ktorClient = HttpClient(CIO) {
    install(WebSockets)
}
val api = ArkNights.default()
val gachaApi = ArkNights.GachaApi.default()


@Serializable
data class Task (
    val uid: ArkNights.Uid,
    val hgToken: ArkNights.HgToken,
)

@Serializable
data class TaskResult (
    val uid: ArkNights.Uid,
    val hgToken: ArkNights.HgToken,
    val gachas: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>,
    val expired: Boolean? = false,
)

suspend fun runTaskReturn(task: Task): List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl> {
    val hgToken = task.hgToken
    val appToken = api.grantAppToken(task.hgToken)
    val bindingList = api.bindingList(appToken)
    val uid = bindingList.list.first().bindingList.first().uid
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
    return waitInsert
}

suspend fun runTask(task: Task) {
    val waitInsert = try {
        runTaskReturn(task)
    } catch (e: ArkNights.HgTokenExpired) {
        println("HgToken 已过期")
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
        setBody(Json.encodeToString(TaskResult(
            uid = task.uid,
            hgToken = task.hgToken,
            gachas = waitInsert,
        )))
    }
    println(resp1.bodyAsText())
}

suspend fun mainFunc() {
    val resp = ktorClient.get("$baseUrl/agent/task") {
        parameter("agentKey", agentKey)
    }
    val body = resp.bodyAsText()
    val task = Json.decodeFromString<Task>(body)
    println(task)
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
    runBlocking {
        try {
            println("start")
            ktorClient.webSocket(
                method = HttpMethod.Get,
                host = "localhost",
                port = 8080,
                path = "agent/ws",
            ) {
                println("ws begin")
                suspend fun send(msg: MessageTemplate) {
                    send(Frame.Text(Json.encodeToString(msg)))
                }
                send(MessageTemplate.Auth(agentKey))
                for (frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val receivedText = frame.readText()
                    val msg = Json.decodeFromString<MessageTemplate>(receivedText)
                    println(msg)
                    when(msg) {
                        is MessageTemplate.Task -> {
                            val waitInsert = try {
                                runTaskReturn(Task(
                                    hgToken = msg.hgToken,
                                    uid = msg.uid
                                ))
                            } catch (e: ArkNights.HgTokenExpired) {
                                println("HgToken 已过期")
                                send(MessageTemplate.Expired(msg.hgToken))
                                continue
                            }
                            send(MessageTemplate.TaskResult(
                                uid = msg.uid,
                                result = waitInsert,
                                hgToken = msg.hgToken,
                            ))
                        }
                        else -> {}
                    }
                }
            }
            println("end")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}