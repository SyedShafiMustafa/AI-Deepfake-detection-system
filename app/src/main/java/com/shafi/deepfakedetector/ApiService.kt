// ApiService.kt
package com.shafi.deepfakedetector

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// This data class maps to the JSON response from Flask
data class PredictionResponse(
    val label: String,
    val confidence: Float,
    val raw_score: Float,
    val message: String
)

// This defines what our API endpoints look like
interface DeepfakeApiService {
    @Multipart
    @POST("predict")
    suspend fun predictImage(
        @Part image: MultipartBody.Part
    ): Response<PredictionResponse>
}

// This factory creates our Retrofit client with the correct server URL
object RetrofitClient {

    fun create(baseUrl: String): DeepfakeApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(DeepfakeApiService::class.java)
    }
}