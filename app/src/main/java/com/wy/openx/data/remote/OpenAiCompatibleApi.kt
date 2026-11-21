package com.wy.openx.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiCompatibleApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse

    @POST("chat/completions")
    suspend fun chatCompletionsStream(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): Response<ResponseBody>
}