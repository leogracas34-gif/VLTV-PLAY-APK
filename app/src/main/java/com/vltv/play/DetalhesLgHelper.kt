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
    /**
     * Tira o fundo da área que rola e coloca numa camada fixa atrás de tudo.
     * Devolve a vista de escurecimento (alpha 0..1 conforme a rolagem) ou null.
     */
    fun fixarFundo(activity: AppCompatActivity, imgBase: ImageView): View? {
        val raiz = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as? FrameLayout ?: return null
        val pai = imgBase.parent as? ViewGroup ?: return null
        if (pai === raiz) return null
        pai.removeView(imgBase)
        val fixo = FrameLayout(activity)
        fixo.addView(imgBase, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val escuro = View(activity).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            alpha = 0f
        }
        raiz.addView(fixo, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // O escurecimento fica acima das camadas de troca (que entram logo após imgBase).
        escuro.tag = "vltv_fundo_escuro"
        fixo.addView(escuro, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return escuro
    }

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

    const val FRASE_AVALIACAO =
        "Curtindo este título? A avaliação nos ajuda a saber se devemos recomendar títulos semelhantes."

    class AvaliacaoUi(val view: View, val atualizar: () -> Unit)

    /**
     * Os três polegares (Não é para mim / Gostei / Amei!), SEM caixas em volta: só o desenho.
     * O escolhido fica dourado (como na LG). tipo: "filme" ou "serie". A nota fica guardada no aparelho e é a
     * mesma na tela de detalhes e na tela de pausa do player.
     */
    fun criarAvaliacao(
        ctx: Context, tipo: String, id: Int,
        comRotulos: Boolean, alturaIconeDp: Int = 30, aoCentro: Boolean = true
    ): AvaliacaoUi {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val prefs = ctx.getSharedPreferences(PREFS_AVAL, Context.MODE_PRIVATE)
        val chave = "${tipo}_$id"
        val dourado = Color.parseColor("#FFD700")
        val claro = Color.parseColor("#F2F2F2")

        data class Op(val valor: String, val rotulo: String, val icone: Int, val iconeAtivo: Int?)
        val opcoes = listOf(
            Op("n", "Não é para mim", R.drawable.ic_aval_baixo, null),
            Op("g", "Gostei", R.drawable.ic_aval_gostei, null),
            Op("a", "Amei!", R.drawable.ic_aval_amei, R.drawable.ic_aval_amei_ativo)
        )

        val linha = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (aoCentro) Gravity.CENTER_HORIZONTAL else Gravity.START
        }
        val itens = mutableListOf<Triple<Op, ImageView, TextView?>>()

        fun pintar() {
            val atual = prefs.getString(chave, "") ?: ""
            itens.forEach { (op, img, rot) ->
                val ativo = op.valor == atual
                if (op.iconeAtivo != null) {
                    img.clearColorFilter()
                    img.setImageResource(if (ativo) op.iconeAtivo else op.icone)
                } else {
                    img.setColorFilter(if (ativo) dourado else claro)
                }
                rot?.setTextColor(if (ativo) dourado else Color.parseColor("#C9C9D0"))
            }
        }

        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)

        opcoes.forEachIndexed { idx, op ->
            val img = ImageView(ctx).apply {
                setImageResource(op.icone)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = op.rotulo
            }
            val rot = if (comRotulos) TextView(ctx).apply {
                text = op.rotulo
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, px(6), 0, 0)
                setShadowLayer(8f, 0f, 2f, Color.parseColor("#99000000"))
            } else null
            val coluna = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(14), px(8), px(14), px(8))
                isClickable = true
                isFocusable = true
                try { setBackgroundResource(tv.resourceId) } catch (e: Exception) { }
                setOnClickListener {
                    prefs.edit().putString(chave, op.valor).apply()
                    pintar()
                }
                addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(alturaIconeDp)))
                if (rot != null) addView(rot, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            itens.add(Triple(op, img, rot))
            linha.addView(coluna, LinearLayout.LayoutParams(
                if (comRotulos) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (comRotulos) 1f else 0f
            ).apply { if (idx > 0 && !comRotulos) marginStart = px(10) })
        }
        pintar()
        return AvaliacaoUi(linha, ::pintar)
    }

    /** tipo: "filme" ou "serie". Entra logo abaixo da sinopse (tvPlot). */
    fun adicionarAvaliacao(activity: AppCompatActivity, ancora: View, tipo: String, id: Int) {
        val pai = ancora.parent as? LinearLayout ?: return
        if (pai.findViewWithTag<View>(TAG_AVAL) != null) return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val margemH = (ancora.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart?.takeIf { it > 0 } ?: px(20)

        val bloco = LinearLayout(activity).apply {
            tag = TAG_AVAL
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margemH, px(14), margemH, px(6)) }
        }
        val aval = criarAvaliacao(activity, tipo, id, comRotulos = true, alturaIconeDp = 30)
        bloco.addView(aval.view)
        bloco.addView(TextView(activity).apply {
            text = FRASE_AVALIACAO
            setTextColor(Color.parseColor("#B5B5BD"))
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(px(8), px(8), px(8), 0)
        })
        pai.addView(bloco, pai.indexOfChild(ancora) + 1)
    }

    /**
     * Liga o botão "Avaliar" (estilo Netflix) da tela de detalhes: abre um quadro com os três polegares.
     * Em layouts sem o botão (TV/paisagem) cai no bloco de polegares dentro da página.
     */
    fun configurarAvaliar(activity: AppCompatActivity, ancora: View, tipo: String, id: Int) {
        val botao = activity.findViewById<View>(R.id.btnAvaliar)
        if (botao == null) {
            adicionarAvaliacao(activity, ancora, tipo, id)
            return
        }
        val icone = activity.findViewById<ImageView>(R.id.imgAvaliar)
        fun pintarIcone() {
            val v = try {
                activity.getSharedPreferences("vltv_avaliacoes", android.content.Context.MODE_PRIVATE)
                    .getString("${tipo}_$id", null)
            } catch (e: Exception) { null }
            icone?.setImageResource(when (v) {
                "n" -> R.drawable.ic_aval_baixo
                "a" -> R.drawable.ic_aval_amei_ativo
                else -> R.drawable.ic_aval_gostei
            })
            icone?.alpha = if (v == null) 0.85f else 1f
        }
        pintarIcone()
        botao.setOnClickListener {
            val dp = activity.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()
            val dialogo = android.app.Dialog(activity)
            dialogo.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val caixa = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(22), px(20), px(20))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C20"))
                    cornerRadius = 18f * dp
                }
            }
            val aval = criarAvaliacao(activity, tipo, id, comRotulos = true, alturaIconeDp = 34)
            caixa.addView(aval.view)
            caixa.addView(TextView(activity).apply {
                text = FRASE_AVALIACAO
                setTextColor(Color.parseColor("#B5B5BD"))
                textSize = 12f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(px(6), px(14), px(6), 0)
            })
            dialogo.setContentView(caixa)
            dialogo.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setLayout((activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dialogo.setOnDismissListener { aval.atualizar(); pintarIcone() }
            dialogo.show()
        }
    }
}
