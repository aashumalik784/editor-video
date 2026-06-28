package com.example.api

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>
)

@JsonClass(generateAdapter = true)
data class PartResponse(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class ContentResponse(
    val parts: List<PartResponse>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: ContentResponse? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    /**
     * Sends a request to Gemini 3.5 Flash to automatically identify video segments that are active (non-silent).
     * We pass a simulated audio waveform data representation as text.
     */
    suspend fun requestSmartCut(waveformJson: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured. Falling back to local smart trim simulation.")
            return@withContext "MOCK_SILENCE_TRIM"
        }

        val prompt = """
            You are an AI video editor assisting in 'Auto-Cut' or 'Smart Trimming' silent parts.
            Here is a comma-separated list of volume levels (0 to 100) recorded every 1 second of a 30-second video clip:
            $waveformJson

            Identify which segments of the video are 'silent' (defined as consecutive values below 15 for 2 or more seconds).
            Provide a list of timeline cut segments that should be REMOVED.
            Format your response in a very short, clean way, for example:
            "Remove: 5s-8s, 14s-17s, 24s-26s"
            Then provide a brief explanation of why you cut them (e.g. "Removed awkward long silences to improve flow").
            Keep the response under 100 words.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt))))
        )

        try {
            val response = apiService.generateContent(apiKey, request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            responseText ?: "Error: Empty response from AI model"
        } catch (e: Exception) {
            Log.e(TAG, "API error requesting smart cut", e)
            "Error: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    /**
     * General co-pilot/caption query.
     */
    suspend fun askAiAssistant(videoDetails: String, query: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "MOCK_ASSISTANT_RESPONSE"
        }

        val prompt = """
            You are CapCut AI, a professional video-editing assistant.
            The user is working on a video clip:
            $videoDetails

            User's request or question:
            $query

            Give a professional, helpful, concise response (maximum 120 words) detailing how they can improve their video, or suggesting edits.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt))))
        )

        try {
            val response = apiService.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No response from AI."
        } catch (e: Exception) {
            "Error calling assistant: ${e.localizedMessage}"
        }
    }
}
