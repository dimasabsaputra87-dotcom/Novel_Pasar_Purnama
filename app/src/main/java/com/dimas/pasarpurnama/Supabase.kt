package com.dimas.pasarpurnama

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SupabaseException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * Minimal Supabase REST client (Auth, PostgREST, Storage) on HttpURLConnection + org.json, so the
 * app needs no extra libraries. The publishable key is safe to ship: access is enforced by the
 * row-level security policies in supabase/setup.sql.
 */
object Supabase {
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_KEY

    fun call(
        method: String,
        path: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", apiKey)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val bytes = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (status >= 400) throw parseError(status, bytes)
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    fun callJson(method: String, path: String, token: String? = null, body: JSONObject? = null): String =
        call(method, path, token, body?.toString()?.toByteArray()).toString(Charsets.UTF_8)

    private fun parseError(status: Int, bytes: ByteArray): SupabaseException {
        val o = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        fun field(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o?.optString(k)?.takeIf { it.isNotEmpty() } }
        return SupabaseException(status, field("error_code", "code"), field("msg", "message", "error_description", "error") ?: "HTTP $status")
    }

    /** User-facing (Indonesian) text for an auth / network failure. */
    fun friendlyError(t: Throwable): String = when {
        t is IOException -> "Tidak bisa terhubung ke server. Periksa koneksi internet."
        t !is SupabaseException -> t.message ?: "Terjadi kesalahan"
        t.code == "invalid_credentials" || t.message == "Invalid login credentials" -> "Email atau password salah."
        t.code == "email_not_confirmed" -> "Email belum dikonfirmasi. Buka link konfirmasi di email kamu dulu."
        t.code == "user_already_exists" -> "Email ini sudah terdaftar. Silakan masuk."
        t.code == "weak_password" -> "Password terlalu lemah (minimal 6 karakter)."
        t.code == "email_address_invalid" || t.code == "validation_failed" -> "Format email tidak valid."
        t.code == "over_email_send_rate_limit" || t.status == 429 -> "Terlalu banyak percobaan. Coba lagi beberapa menit lagi."
        t.code == "signup_disabled" -> "Pendaftaran akun baru sedang ditutup."
        else -> t.message ?: "Terjadi kesalahan"
    }
}
