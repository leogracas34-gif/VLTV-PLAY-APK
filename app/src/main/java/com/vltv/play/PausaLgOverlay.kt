package com.vltv.play

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * PausaLgOverlay — tela de pausa igual à do app da LG (estilo Netflix).
 *
 * Com o vídeo PAUSADO por mais de 5 s (filme ou episódio; canal ao vivo não),
 * escurece a imagem e mostra: "Pausado" em cima, o nome do título grande à
 * esquerda (com "Temporada X • Episódio Y" quando for série) e embaixo o ícone
 * de pausa, o tempo (decorrido / total) e a barra de progresso. Voltou a tocar →
 * some na hora. Não bloqueia toque nem controle remoto: os controles normais
 * do player continuam funcionando por baixo.
 *
 * ✅ DECISÃO DE ARQUITETURA: em vez de plugar listeners nos 3 lugares onde a
 * PlayerActivity cria o ExoPlayer, este overlay "olha" o player a cada 500 ms
 * (barato: lê 3 campos). Assim a PlayerActivity só ganha duas linhas (criar e
 * parar) e a regra de pausa mora em um lugar só.
 */
class PausaLgOverlay(
    private val raiz: ViewGroup,
    private val player: () -> ExoPlayer?,
    private val titulo: () -> String,
    private val ehAoVivo: () -> Boolean,
    // Endereço da logo do título (a mesma da tela de detalhes). null = mostra o nome em texto.
    private val logoUrl: () -> String? = { null },
    // ("filme"|"serie", id) para os polegares de avaliação. null = não mostra a avaliação.
    private val avaliacao: () -> Pair<String, Int>? = { null }
) {
    companion object {
        private const val ATRASO_PAUSA_MS = 5000L   // igual à LG (ATRASO_PAUSA_MS)
        private const val PERIODO_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pausadoDesde = 0L
    private var rodando = false

    private val painel: LinearLayout
    private val tvPausado: TextView
    private val tvEp: TextView
    private val tvTitulo: TextView
    private val imgLogo: ImageView
    private val contAval: LinearLayout
    private var logoAtual: String? = null
    private var avalAtual: String? = null
    private var avalUi: DetalhesLgHelper.AvaliacaoUi? = null
    private val tvTempo: TextView
    private val barra: ProgressBar

    init {
        val ctx = raiz.context
        val dp = ctx.resources.displayMetrics.density

        // Painel de tela cheia: escuro à esquerda/embaixo, como o fundo da LG.
        val fundo = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#EB000000"), Color.parseColor("#99000000"), Color.parseColor("#33000000"))
        )
        painel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = fundo
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            setPadding((48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt(), (120 * dp).toInt())
            elevation = 6 * dp
        }

        tvPausado = TextView(ctx).apply {
            text = "PAUSADO"
            setTextColor(Color.parseColor("#9C9CA8"))
            textSize = 13f
            letterSpacing = 0.2f
        }
        painel.addView(tvPausado)

        val meio = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvEp = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF4B55"))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
        }
        tvTitulo = TextView(ctx).apply {
            setTextColor(Color.parseColor("#F5F5F7"))
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(14f, 0f, 3f, Color.parseColor("#B3000000"))
            maxWidth = (520 * dp).toInt()
        }
        // Logo do título (igual à LG): no lugar do nome em texto, quando existir.
        imgLogo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams((340 * dp).toInt(), (120 * dp).toInt())
        }
        // Polegares (só o desenho) + frase, logo abaixo da logo.
        contAval = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        meio.addView(tvEp)
        meio.addView(imgLogo)
        meio.addView(tvTitulo)
        meio.addView(contAval)
        painel.addView(meio)

        // Embaixo: ícone de pausa + tempo + barra (igual à LG).
        val linha = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icone = TextView(ctx).apply {
            text = "❚❚"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, (16 * dp).toInt(), 0)
        }
        barra = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = GradientDrawable().apply { setColor(Color.parseColor("#E50914")); cornerRadius = 4 * dp }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E50914"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#59FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, (5 * dp).toInt(), 1f)
        }
        tvTempo = TextView(ctx).apply {
            setTextColor(Color.parseColor("#E6E6EC"))
            textSize = 14f
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }
        linha.addView(icone)
        linha.addView(barra)
        linha.addView(tvTempo)
        painel.addView(linha)

        painel.layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        raiz.addView(painel)
    }

    private val tique = object : Runnable {
        override fun run() {
            if (!rodando) return
            atualizar()
            handler.postDelayed(this, PERIODO_MS)
        }
    }

    fun iniciar() {
        if (rodando) return
        rodando = true
        handler.post(tique)
    }

    fun parar() {
        rodando = false
        handler.removeCallbacks(tique)
        painel.visibility = View.GONE
    }

    private fun atualizar() {
        val p = player()
        // "Pausa" só vale com o vídeo já tocando alguma vez (READY) e pausado de verdade.
        val pausado = p != null && !ehAoVivo() &&
            p.playbackState == Player.STATE_READY && !p.playWhenReady
        if (!pausado) {
            pausadoDesde = 0L
            if (painel.visibility != View.GONE) painel.visibility = View.GONE
            return
        }
        val agora = System.currentTimeMillis()
        if (pausadoDesde == 0L) pausadoDesde = agora
        if (agora - pausadoDesde < ATRASO_PAUSA_MS) return

        val pos = p!!.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        barra.progress = if (dur > 0) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        tvTempo.text = if (dur > 0) "${fmt(pos)} / ${fmt(dur)}" else fmt(pos)

        // "T1E05 - Nome" → "Temporada 1 • Episódio 5" + nome do episódio.
        val bruto = titulo().trim()
        val m = Regex("^[Tt](\\d+)\\s*[Ee](\\d+)\\s*[-–:]?\\s*(.*)$").find(bruto)
        if (m != null) {
            tvEp.text = "Temporada ${m.groupValues[1].toInt()} • Episódio ${m.groupValues[2].toInt()}"
            tvEp.visibility = View.VISIBLE
            tvTitulo.text = m.groupValues[3].ifBlank { bruto }
        } else {
            tvEp.visibility = View.GONE
            tvTitulo.text = bruto
        }
        atualizarLogo()
        atualizarAvaliacao()
        if (painel.visibility != View.VISIBLE) painel.visibility = View.VISIBLE
    }

    // Logo no lugar do nome (se não carregar, volta o nome em texto).
    private fun atualizarLogo() {
        val url = logoUrl()?.takeIf { it.isNotBlank() }
        if (url == null) {
            logoAtual = null
            imgLogo.visibility = View.GONE
            tvTitulo.visibility = View.VISIBLE
            return
        }
        if (url == logoAtual) return
        logoAtual = url
        try {
            com.bumptech.glide.Glide.with(imgLogo.context)
                .load(url)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .dontAnimate()
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean): Boolean {
                        imgLogo.post { imgLogo.visibility = View.GONE; tvTitulo.visibility = View.VISIBLE }
                        return false
                    }
                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                        imgLogo.post { imgLogo.visibility = View.VISIBLE; tvTitulo.visibility = View.GONE }
                        return false
                    }
                })
                .into(imgLogo)
        } catch (e: Exception) {
            imgLogo.visibility = View.GONE; tvTitulo.visibility = View.VISIBLE
        }
    }

    // Polegares + frase (mesma nota guardada da tela de detalhes).
    private fun atualizarAvaliacao() {
        val a = avaliacao()
        if (a == null) { contAval.visibility = View.GONE; return }
        val chave = "${a.first}_${a.second}"
        val ctx = contAval.context
        val dp = ctx.resources.displayMetrics.density
        if (chave != avalAtual) {
            avalAtual = chave
            contAval.removeAllViews()
            val ui = DetalhesLgHelper.criarAvaliacao(ctx, a.first, a.second,
                comRotulos = false, alturaIconeDp = 34, aoCentro = false)
            avalUi = ui
            contAval.addView(ui.view)
            contAval.addView(TextView(ctx).apply {
                text = DetalhesLgHelper.FRASE_AVALIACAO
                setTextColor(Color.parseColor("#D0D0D8"))
                textSize = 14f
                maxWidth = (520 * dp).toInt()
                setLineSpacing(0f, 1.15f)
                setShadowLayer(10f, 0f, 2f, Color.parseColor("#B3000000"))
                setPadding((6 * dp).toInt(), (10 * dp).toInt(), 0, 0)
            })
        } else {
            avalUi?.atualizar?.invoke()   // a nota pode ter mudado em outra tela
        }
        contAval.visibility = View.VISIBLE
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
