package com.example.yeya_ver2

import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ClaudeApiClient {
    private const val BASE_URL = "https://api.anthropic.com/"
    private const val API_KEY = "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"

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("x-api-key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ClaudeApiService = retrofit.create(ClaudeApiService::class.java)

    suspend fun sendMessageToClaude(prompt: String, uiElements: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val messages = listOf(Message("user", buildPrompt(prompt, uiElements)))
                val request = ChatCompletionRequest(
                    model = "claude-3-haiku-20240307",
                    messages = messages,
                    max_tokens = 1000,
                    temperature = 0.0,
                    stream = false
                )
                val response = apiService.createChatCompletion(request)
                val textContent = response.content.firstOrNull { it.type == "text" }?.text
                textContent ?: "No response from Claude"
            } catch (e: Exception) {
                Log.e("ClaudeApiClient", "Error communicating with Claude API", e)
                throw e
            }
        }
    }

    private fun buildPrompt(prompt: String, uiElements: String): String {
        return """
            당신의 이름은 YeYa입니다. 당신은 휴대기기를 수월하게 사용하지 못하는 어르신들을 위해 어르신 사용자가 원하는 동작을 대신 수행하고 이를 설명해주는 AI 에이전트입니다. 사용자로부터 음성 명령, 화면의 UI 요소를 정보로 받으며 이를 토대로 음성 명령을 성공적으로 수행해야 합니다. UI 요소에는 id, class, text, contentDescription, isClickable에 해당하는 정보가 담겨 있습니다.

            음성 명령 : $prompt

            UI 정보는 아래와 같습니다.
            $uiElements

            음성 명령을 수행하도록 돕기 위해서는 당신은 반드시 아래 규칙에 의해서 답변해야 합니다.
            1항 답변은 반드시 하나의 object 자료형으로 이루어집니다.
            2항 하나의 object 자료형 외에는 그 어떠한 말을 첨언할 수 없습니다.
            3항 object에서 첫 번째 요소는 description입니다. 여기에서 당신은 음성 명령을 달성하는데 필요한 과정을 수행하겠다고 사용자에게 알릴 수 있습니다. 이 메시지는 TTS를 통해 사용자에게 전달되어 어떤 작업을 수행하는지 알려줍니다. 따라서 어르신과 대화하는 것이므로 간결하면서 이해하기 쉽게 말합니다.
            4항 object의 두 번째 요소는 id입니다. 여기에서 음성 명령을 성공시키기 위해 조작할 UI 요소를 선택할 수 있습니다. Captured UI elements object 자료에서 반드시 딱 하나의 id만을 골라 정수만 입력합니다.
            5항 object의 세 번째 요소는 action입니다. 여기에서 선택한 id를 어떻게 조작할 것인지 전달할 수 있습니다. 사용할 수 있는 기능은 아래와 같습니다.
            1) Click : 해당 UI 요소를 클릭합니다.
            6항 답변하는 object 자료에서는 description, id, action이라는 key가 3개 모두 반드시 포함되어야 하며 각각에 해당하는 value도 반드시 3개로 존재해야만 합니다.
            
            답변의 예시는 아래와 같습니다. 아래 예시는 현재 사용자가 내린 음성 명령과 전혀 무관합니다. 구조만 파악하세요. 답변은 아래와 같이 그 어떠한 첨언 없이 단 하나의 object만 내보내야 합니다.
            예시 JSON 답변
            {
              "description": "송금을 하기 위해서는 은행 계좌 송금 버튼을 클릭해야 합니다. 가장 잔고가 많은 첫 번째 계좌의 송금 버튼을 클릭하겠습니다.",
              "id": 3,
              "action": "Click"
            }

            모든 내용이 이해되었다면 주어진 사용자의 음성 명령을 파악하고 어떤 작업을 수행할지 생각한 다음 Captured UI elements에서 id를 고르시기 바랍니다. 이후 답변은 주어진 규칙에 따라 다른 첨언은 일절 없이 위에 주어진 예시와 같이 반드시 '{'로 시작해서 '}'로 끝나는 단 하나의 object만 답변하십시오. 반드시 action: 다음에 수행할 기능을 포함해서 답변을 완성하십시오. action에 해당하는 value를 빠트리지 마십시오.
        """.trimIndent()
    }
}

interface ClaudeApiService {
    @Headers("Content-Type: application/json")
    @POST("v1/messages")
    suspend fun createChatCompletion(@Body request: ChatCompletionRequest): ChatCompletionResponse
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int,
    val temperature: Double,
    val stream: Boolean = false
)

data class Message(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentItem>,
    val model: String,
    val stop_reason: String?,
    val stop_sequence: String?
)

data class ContentItem(
    val type: String,
    val text: String
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

data class Choice(
    val index: Int,
    val message: Message,
    @SerializedName("finish_reason") val finishReason: String
)