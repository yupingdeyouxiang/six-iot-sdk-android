package com.six.iot

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.io.IOException

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    private val userInfoUrl: String = "https://iam.shuhenglianchang.com/userinfo"
    private val token:String = "eyJraWQiOiJiYmQ4ZDg0NS0yZTY0LTQxYzQtYTk4NC03Mjg0OWY2OTI1NzIiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsImF1ZCI6InNpeC1pYW0tbWd0IiwibmJmIjoxNzIzNzAxNTI2LCJyb2xlIjpbeyJST0xFX2lhbS1hdWRpdCI6WyJpYW1fYXBwX3JlYWQiLCJpYW1fYXVkaXRfX2FwcEV2ZW50X3JlYWQiLCJpYW1fYXVkaXRfX29wdExvZ19yZWFkIl19LHsiUk9MRV9pYW0tY29uZmlnLXJlYWQiOlsiaWFtX2NvbmZpZ19yZWFkIl19LHsiUk9MRV9pYW0tZW50aXR5LWFkbWluIjpbImlhbV9UYXJnZXRLZXlfQUxMIiwiaWFtX1RhcmdldFNlcnZpY2VfQUxMIiwiaWFtX1RhcmdldFBlcm1fQUxMIiwiaWFtX1RhcmdldFVzZXJfQUxMIiwiaWFtX1RhcmdldFRoaXJkUGFydHlDbGllbnRfQUxMIiwiaWFtX1RhcmdldEFwcF9BTEwiLCJpYW1fSW5PcmdfQUxMIiwiaWFtX1RhcmdldE9yZ19BTEwiLCJpYW1fVGFyZ2V0R3JvdXBfQUxMIiwiaWFtX1RhcmdldERldmljZV9BTEwiLCJpYW1fVGFyZ2V0Q2xpZW50X0FMTCIsImlhbV9UYXJnZXRSb2xlX0FMTCJdfV0sIm9yZyI6eyJsZXZlbCI6MCwib3JnSWQiOiIwYTJhMDA5MC04YmY2LTFjODgtODE4Yi1mNjRjYWRiZDAwMDAifSwic2NvcGUiOlsib3BlbmlkIiwicHJvZmlsZSJdLCJpc3MiOiJodHRwczovL2lhbS5zaHVoZW5nbGlhbmNoYW5nLmNvbSIsImV4cCI6MTcyMzcwMTgyNiwiaWF0IjoxNzIzNzAxNTI2LCJqdGkiOiJkY2ZkZWVhNy1kZTc0LTQ2ZTAtOTA2MS0wMWI2NjEyNGQ5ZDYifQ.DhS8nI-OV36hE_JT5gcNWW1Fsx8sx6LohNO1kWj0Vyfs6DTwzP-osRtsOjSzvaSTThvEqPxRu_4xJfiy6wg4S6E2fHjZqL9qvd4bOWdZlcdfl9LJ7Ar4fECp6xDy2oNVu9pSWnGdJGW7ZfUPszkwO8fH8rFqXOjsMP9SFmjjRS9qExTrrthEiftGGvD5tkQIVmy1facSfVtqDglDFEev4Km1hQqAiD1BhkbCehsV3k7Foviky_tXPSRMY305qpLSssveMQ567eTctedAWYoSheBVjr0W4OXsgfB_fc-VPETyY1OOGtH0GjQ_34M7tAaW0R-PBC8TawyZtnIlxms18g";
    private val objectMapper: ObjectMapper = ObjectMapper()

    @Test
    @Throws(IOException::class)
    fun whenSendPostRequestWithAuthorization_thenCorrect() {
        val request: Request = Request.Builder()
            .url(userInfoUrl)
            .addHeader("Authorization", "Bearer $token")
            .get().build();
        val client = OkHttpClient()
        val call = client.newCall(request)
        val response = call.execute()
        var userinfo = response.body.string()
        println(userinfo)
        var userinfoMap: Map<String, Any> = HashMap();
        userinfoMap = objectMapper.readValue(userinfo, userinfoMap.javaClass)
        if(userinfoMap["user"] != null) {
            val userMap: Map<String, Any> = userinfoMap["user"] as Map<String, Any>
            println(userMap["id"])
        }
        assertEquals(response.code, 200)
    }
}