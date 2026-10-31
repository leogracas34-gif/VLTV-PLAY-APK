package com.vltv.play

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Selo de classificação indicativa (Brasil) ao lado do ano, como na Netflix: L, 10, 12, 14, 16, 18.
 * A classificação vem do TMDB (certificação "BR"); se não houver, nenhum selo aparece.
 */
object ClassificacaoIndicativa {

    private const val TAG_SELO = "vltv_selo_classificacao"
    private const val PREFS = "vltv_classificacao_cache"

    /** tipo: "movie" ou "tv" (mesmo valor usado na API do TMDB). */
    fun aplicar(activity: AppCompatActivity, tmdbId: Int, tipo: String) {
        if (tmdbId <= 0 || activity.isFinishing || activity.isDestroyed) return
        val ancora: View = activity.findViewById<View>(R.id.tvYear)
            ?: activity.findViewById<View>(R.id.tvReleaseDate) ?: return
        val linha = ancora.parent as? LinearLayout ?: return
        if (linha.findViewWithTag<View>(TAG_SELO) != null) return

        activity.lifecycleScope.launch {
            val cert = withContext(Dispatchers.IO) { buscar(activity, tmdbId, tipo) }
            if (cert.isNullOrBlank() || activity.isFinishing || activity.isDestroyed) return@launch
            if (linha.findViewWithTag<View>(TAG_SELO) != null) return@launch
            val selo = criarSelo(activity, cert)
            val dp = activity.resources.displayMetrics.density
            selo.layoutParams = LinearLayout.LayoutParams(
                (22 * dp).toInt(), (22 * dp).toInt()
            ).apply { marginEnd = (10 * dp).toInt() }
            linha.addView(selo, linha.indexOfChild(ancora) + 1)
        }
    }

    private fun corDe(cert: String): Int = when (cert) {
        "L" -> Color.parseColor("#2E9B49")
        "10" -> Color.parseColor("#1A7FC4")
        "12" -> Color.parseColor("#E8B500")
        "14" -> Color.parseColor("#E87A1E")
        "16" -> Color.parseColor("#D4202A")
        "18" -> Color.parseColor("#111111")
        else -> Color.parseColor("#555555")
    }

    private fun criarSelo(ctx: Context, cert: String): View {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            tag = TAG_SELO
            text = cert
            gravity = Gravity.CENTER
            setTextColor(if (cert == "12") Color.BLACK else Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = if (cert.length > 1) 11f else 12f
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(corDe(cert))
                cornerRadius = 3f * dp
                if (cert == "18") setStroke((1 * dp).toInt().coerceAtLeast(1), Color.parseColor("#66FFFFFF"))
            }
        }
    }

    private fun buscar(ctx: Context, tmdbId: Int, tipo: String): String? {
        val chave = "${tipo}_$tmdbId"
        val prefs = try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) } catch (e: Exception) { null }
        prefs?.getString(chave, null)?.let { return it.ifBlank { null } }
        return try {
            val url = if (tipo == "tv")
                "https://api.themoviedb.org/3/tv/$tmdbId/content_ratings?api_key=${TmdbConfig.API_KEY}"
            else
                "https://api.themoviedb.org/3/movie/$tmdbId/release_dates?api_key=${TmdbConfig.API_KEY}"
            SharedHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return null
                var cert: String? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("iso_3166_1") != "BR") continue
                    cert = if (tipo == "tv") {
                        o.optString("rating")
                    } else {
                        val dts = o.optJSONArray("release_dates")
                        var achado = ""
                        if (dts != null) for (j in 0 until dts.length()) {
                            val c = dts.getJSONObject(j).optString("certification")
                            if (c.isNotBlank()) { achado = c; break }
                        }
                        achado
                    }
                }
                val limpo = normalizar(cert)
                prefs?.edit()?.putString(chave, limpo ?: "")?.apply()
                limpo
            }
        } catch (e: Exception) { null }
    }

    private fun normalizar(bruto: String?): String? {
        val t = bruto?.trim()?.uppercase().orEmpty()
        if (t.isEmpty()) return null
        if (t == "L" || t == "LIVRE") return "L"
        val n = Regex("\\d+").find(t)?.value ?: return null
        return if (n in listOf("10", "12", "14", "16", "18")) n else null
    }
}
