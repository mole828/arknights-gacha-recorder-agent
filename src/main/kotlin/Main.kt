package com.example

import api.ArkNights
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.util.*
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


val ktorClient = HttpClient(CIO)
val api = ArkNights.default()
val gachaApi = ArkNights.GachaApi.default()


@Serializable
data class Task (
    val id: String,
    val hgToken: ArkNights.HgToken,
)

@Serializable
data class TaskResult (
    val uid: ArkNights.Uid,
    val hgToken: ArkNights.HgToken,
    val gachas: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>
)

suspend fun runTask(task: Task) {
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

    val resp1 = ktorClient.post("$baseUrl/agent/task") {
        parameter("agentKey", agentKey)
        setBody(Json.encodeToString(TaskResult(
            uid = uid,
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
    runBlocking {
        if (loopMode) {
            while (true) {
                try {
                    mainFunc()
                    delay(20.seconds)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        } else {
            mainFunc()
        }
    }
}