package com.vltv.play

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * DetalhesLgHelper — o que a tela de detalhes da LG tem e o APK não tinha:
 *
 * 1) FUNDO QUE TROCA DEVAGAR: no lugar do trailer em WebView (YouTube, que às vezes vinha "sujo", com
 *    "trailer legendado" escrito), o fundo passa por imagens do TMDB do próprio título, a cada 9 s, com
 *    transição suave. Duas camadas de imagem se alternam (uma entra enquanto a outra sai) e cada uma carrega
 *    a SUA imagem, sem reaproveitar drawable de outra (isso evita o erro "recycled bitmap").
 *
 * 2) AVALIAÇÃO: "Não é para mim" / "Gostei" / "Amei!" com a frase de explicação embaixo, guardada no aparelho.
 *
 * ✅ DECISÃO DE ARQUITETURA: fica num arquivo próprio e é ligado por poucas linhas nas duas telas de detalhes
 * (filme e série). Nenhum layout XML precisou mudar: a segunda camada e a avaliação são criadas por código.
 */
object DetalhesLgHelper {

    private const val INTERVALO_FUNDO_MS = 9000L
    private const val MAX_FUNDOS = 8
    private const val PREFS_AVAL = "vltv_avaliacoes"
    private const val TAG_AVAL = "vltv_avaliacao_bloco"

    // ── 1) Fundo que troca ─────────────────────────────────────────────────

    /**
     * tipo: "movie" ou "tv" (como no TMDB). Pode ser chamada de novo para o mesmo título
     * (a segunda chamada é ignorada).
     */
    fun iniciarFundo(activity: AppCompatActivity, imgBase: ImageView, tmdbId: Int, tipo: String) {
        if (tmdbId <= 0 || activity.isFinishing || activity.isDestroyed) return
        val chave = "$tipo/$tmdbId"
        if (imgBase.getTag(R.id.imgBackground) == chave) return
        imgBase.setTag(R.id.imgBackground, chave)

        val pai = imgBase.parent as? FrameLayout ?: return
        val alvo = if (imgBase.alpha > 0f) imgBase.alpha else 1f

        // Segunda camada logo acima da primeira (e abaixo do degradê que vem depois).
        val camadaB = ImageView(activity).apply {
            scaleType = imgBase.scaleType ?: ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            contentDescription = null
        }
        val indice = pai.indexOfChild(imgBase)
        pai.addView(camadaB, indice + 1, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        activity.lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) { buscarFundos(tmdbId, tipo) }
            if (urls.isEmpty()) return@launch

            // A primeira imagem entra por cima do que já está (capa ou nada).
            var atual: ImageView = imgBase
            var proxima: ImageView = camadaB
            var i = 0
            var primeira = true
            while (isActive && !activity.isFinishing && !activity.isDestroyed) {
                // Só troca com a tela visível (economiza dados e evita trabalho em segundo plano).
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    val url = urls[i % urls.size]
                    i++
                    val pronta = carregarEm(activity, proxima, url)
                    if (pronta && !activity.isFinishing && !activity.isDestroyed) {
                        proxima.animate().cancel()
                        atual.animate().cancel()
                        proxima.animate().alpha(alvo).setDuration(if (primeira) 700 else 1100).start()
                        atual.animate().alpha(0f).setDuration(if (primeira) 700 else 1100).start()
                        val t = atual; atual = proxima; proxima = t
                        primeira = false
                        if (urls.size < 2) return@launch   // só uma imagem: não precisa girar
                    }
                }
                delay(INTERVALO_FUNDO_MS)
            }
        }
    }

    // Carrega a imagem na camada escondida e só devolve quando ela estiver pronta (ou falhar/estourar o tempo).
    private suspend fun carregarEm(activity: AppCompatActivity, view: ImageView, url: String): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(6000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    Glide.with(activity)
                        .load(url)
                        .centerCrop()
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .dontAnimate()
                        .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                            override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                                target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                isFirstResource: Boolean): Boolean {
                                if (cont.isActive) cont.resumeWith(Result.success(false)); return false
                            }
                            override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any,
                                target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                                dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                if (cont.isActive) cont.resumeWith(Result.success(true)); return false
                            }
                        })
                        .into(view)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }
        } ?: false

    // Imagens de fundo do título no TMDB: as SEM texto/idioma primeiro (arte limpa), depois as mais bem votadas.
    private fun buscarFundos(tmdbId: Int, tipo: String): List<String> {
        return try {
            val url = "https://api.themoviedb.org/3/$tipo/$tmdbId/images?api_key=${TmdbConfig.API_KEY}" +
                    "&include_image_language=null,pt,en"
            SharedHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("backdrops") ?: return emptyList()
                val lista = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("file_path").isNotBlank() }
                    .sortedWith(
                        compareBy<JSONObject> { if (it.isNull("iso_639_1") || it.optString("iso_639_1").isBlank()) 0 else 1 }
                            .thenByDescending { it.optDouble("vote_average", 0.0) }
                    )
                    .take(MAX_FUNDOS)
                lista.map { VpsConfig.tmdbImage(it.getString("file_path"), "w1280") }
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── 2) Avaliação ───────────────────────────────────────────────────────

    /** tipo: "filme" ou "serie". Entra logo abaixo da sinopse (tvPlot). */
    fun adicionarAvaliacao(activity: AppCompatActivity, ancora: View, tipo: String, id: Int) {
        val pai = ancora.parent as? LinearLayout ?: return
        if (pai.findViewWithTag<View>(TAG_AVAL) != null) return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val margemH = (ancora.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart?.takeIf { it > 0 } ?: px(20)
        val chave = "${tipo}_$id"
        val prefs = activity.getSharedPreferences(PREFS_AVAL, Context.MODE_PRIVATE)

        val bloco = LinearLayout(activity).apply {
            tag = TAG_AVAL
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margemH, px(18), margemH, px(6)) }
        }

        data class Op(val valor: String, val rotulo: String, val icone: Int)
        val opcoes = listOf(
            Op("n", "Não é para mim", R.drawable.ic_aval_baixo),
            Op("g", "Gostei", R.drawable.ic_aval_gostei),
            Op("a", "Amei!", R.drawable.ic_aval_amei)
        )

        val botoes = mutableListOf<Triple<String, LinearLayout, Pair<ImageView, TextView>>>()
        fun pintar() {
            val atual = prefs.getString(chave, "") ?: ""
            botoes.forEach { (valor, caixa, par) ->
                val ativo = valor == atual
                (caixa.background as GradientDrawable).setColor(Color.parseColor(if (ativo) "#EBFFFFFF" else "#26FFFFFF"))
                val cor = if (ativo) Color.BLACK else Color.parseColor("#E8E8E8")
                if (valor == "a") {
                    // "Amei!" tem contorno próprio; por isso troca de desenho em vez de pintar de uma cor só.
                    par.first.clearColorFilter()
                    par.first.setImageResource(if (ativo) R.drawable.ic_aval_amei_escuro else R.drawable.ic_aval_amei)
                } else {
                    par.first.setColorFilter(cor)
                }
                par.second.setTextColor(cor)
            }
        }

        val linha = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        opcoes.forEachIndexed { idx, op ->
            val icone = ImageView(activity).apply {
                setImageResource(op.icone)
                adjustViewBounds = true
            }
            val rotulo = TextView(activity).apply {
                text = op.rotulo
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, px(6), 0, 0)
            }
            val caixa = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(px(6), px(12), px(6), px(10))
                background = GradientDrawable().apply { cornerRadius = 14 * dp }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.edit().putString(chave, op.valor).apply()
                    pintar()
                }
                addView(icone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(26)))
                addView(rotulo, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            botoes.add(Triple(op.valor, caixa, icone to rotulo))
            linha.addView(caixa, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = px(8)
            })
        }
        bloco.addView(linha)
        bloco.addView(TextView(activity).apply {
            text = "Curtindo este título? A avaliação nos ajuda a saber se devemos recomendar títulos semelhantes."
            setTextColor(Color.parseColor("#B5B5BD"))
            textSize = 12f
            setLineSpacing(0f, 1.15f)
            setPadding(0, px(12), 0, 0)
        })
        pintar()

        pai.addView(bloco, pai.indexOfChild(ancora) + 1)
    }
}
