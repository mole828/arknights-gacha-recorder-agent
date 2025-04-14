package com.example.protocol

import api.ArkNights
import api.ArkNights.Uid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MessageTemplate {
    @Serializable
    @SerialName("msg")
    data class Msg(val msg: String) : MessageTemplate
    @Serializable
    @SerialName("auth")
    data class Auth(val agentKey: String) : MessageTemplate
    @Serializable
    @SerialName("task")
    data class Task(val hgToken: ArkNights.HgToken, val uid: Uid) : MessageTemplate
    @Serializable
    @SerialName("task_result")
    data class TaskResult(val result: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>, val uid: Uid, val hgToken: ArkNights.HgToken) : MessageTemplate
    @Serializable
    @SerialName("expired")
    data class Expired(val hgToken: ArkNights.HgToken) : MessageTemplate
}