package com.vltv.play.ui

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.AvatarHelper
import com.vltv.play.R

/**
 * AvatarSelectionDialog — v5
 * Mostra só os 12 avatares próprios do VLTV (bichinhos originais), os MESMOS da TV.
 * Não há mais personagens de filmes/séries/anime (direito autoral / Google Play).
 * O id devolvido é o nome do drawable ("av_vltv_raposa"); o CloudSync converte para "vltv:raposa".
 * O desenho continua com center-crop real dentro do círculo (nunca esticado).
 */
class AvatarSelectionDialog(
    context: Context,
    private val onAvatarSelected: (String) -> Unit
) : Dialog(context) {

    // ─── Modelo ────────────────────────────────────────────────────────────────

    data class AvatarItem(
        val id: String,
        val nome: String,
        val drawableRes: Int,
        val categoria: String
    )

    // ─── Catálogo (igual ao da TV) ─────────────────────────────────────────────

    private val todosAvatares: List<AvatarItem> by lazy {
        AvatarHelper.IDS.mapNotNull { id ->
            val nome = "av_vltv_$id"
            val res = AvatarHelper.resId(context, nome)
            if (res == 0) null else AvatarItem(nome, AvatarHelper.NOMES[id] ?: id, res, "VLTV")
        }
    }

    private val ringColorByCat = mapOf("VLTV" to Color.parseColor("#7F5AF0"))

    // ─── Estado ───────────────────────────────────────────────────────────────

    private var idSelecionado: String? = null

    private var btnConfirmar: TextView? = null
    private var gridAdapter: AvatarGridAdapter? = null

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
    private val Float.dp: Float get() = (this * context.resources.displayMetrics.density)

    private fun filtrados() = todosAvatares

    private fun atualizarBotao() {
        val ativo = idSelecionado != null
        btnConfirmar?.apply {
            isEnabled = ativo
            val bg = background as? android.graphics.drawable.GradientDrawable
            if (ativo) {
                bg?.setColor(Color.WHITE)
                bg?.setStroke(0, Color.TRANSPARENT)
                setTextColor(Color.BLACK)
            } else {
                bg?.setColor(Color.parseColor("#1A1A1A"))
                bg?.setStroke(1.dp, Color.parseColor("#2A2A2A"))
                setTextColor(Color.parseColor("#444444"))
            }
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        fun divider() = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 14.dp)
        }
        header.addView(TextView(context).apply {
            text = "Escolher avatar"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener { dismiss() }
        })

        // Grid de avatares
        gridAdapter = AvatarGridAdapter(filtrados()) { id ->
            idSelecionado = id
            atualizarBotao()
        }
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            layoutManager = GridLayoutManager(context, 3)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            clipToPadding = false
            adapter = gridAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(12)
        }

        // Footer / Botão confirmar
        val btn = TextView(context).apply {
            text = "Confirmar Avatar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isEnabled = false
            setTextColor(Color.parseColor("#444444"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 8f.dp
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, 52.dp)
            setOnClickListener {
                val id = idSelecionado ?: return@setOnClickListener
                onAvatarSelected(id)
                dismiss()
            }
        }
        btnConfirmar = btn

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        footer.addView(divider())
        footer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 8.dp)
        })
        footer.addView(btn)

        root.addView(header)
        root.addView(divider())
        root.addView(recycler)
        root.addView(footer)

        setContentView(root)

        window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D0D0D"))
                cornerRadius = 16f.dp
            })
            val p = attributes
            p.width  = (context.resources.displayMetrics.widthPixels  * 0.93).toInt()
            p.height = (context.resources.displayMetrics.heightPixels * 0.85).toInt()
            attributes = p
        }
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    inner class AvatarGridAdapter(
        private var list: List<AvatarItem>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AvatarGridAdapter.VH>() {

        private var selectedPos = -1

        fun updateList(nova: List<AvatarItem>) {
            selectedPos = -1
            list = nova
            notifyDataSetChanged()
        }

        private val AVATAR_SIZE get() = 88.dp
        private val RING_STROKE get() = 3.dp

        inner class VH(val container: LinearLayout) : RecyclerView.ViewHolder(container) {
            val avatarView: AvatarDrawView = container.getChildAt(0) as AvatarDrawView
            val nameText: TextView         = container.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.MarginLayoutParams(MATCH, WRAP).apply {
                    setMargins(4.dp, 10.dp, 4.dp, 10.dp)
                }
                isClickable = true
                isFocusable = true
            }

            val avatarView = AvatarDrawView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE)
            }

            val nameText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = 6.dp
                }
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            container.addView(avatarView)
            container.addView(nameText)
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val sel  = selectedPos == position
            val ringColor = ringColorByCat[item.categoria] ?: Color.parseColor("#FFD700")

            holder.avatarView.bind(
                drawableRes  = item.drawableRes,
                ringColor    = ringColor,
                isSelected   = sel,
                ringStrokePx = RING_STROKE
            )

            holder.nameText.text = item.nome
            holder.nameText.setTextColor(if (sel) Color.WHITE else Color.parseColor("#888888"))
            holder.nameText.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            holder.container.setOnClickListener {
                val prev = selectedPos
                selectedPos = holder.adapterPosition
                if (prev >= 0) notifyItemChanged(prev)
                notifyItemChanged(selectedPos)
                onClick(item.id)
            }

            holder.container.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                            .scaleY(if (hasFocus) 1.08f else 1f)
                            .setDuration(120).start()
            }
        }

        override fun getItemCount() = list.size
    }

    // ─── View customizada para desenhar o avatar via Canvas ───────────────────

    /**
     * Desenha:
     *  1. O drawable (pôster/arte do personagem) com CENTER-CROP real dentro do
     *     círculo — nunca esticado, nunca cortado de forma aleatória.
     *  2. Anel colorido da categoria (fino, sempre visível)
     *  3. Anel dourado de seleção (quando selecionado)
     *
     * Correção do bug relatado: antes usávamos bgDrawable.setBounds(0,0,w,h),
     * que ESTICA a imagem inteira para caber no quadrado do view — se a
     * imagem original não fosse quadrada (a maioria dos pôsteres é retrato,
     * 2:3), o resultado distorcia e dava a impressão de personagem cortado ou
     * fora de posição. Agora extraímos o bitmap e recortamos um quadrado
     * central (com viés leve para cima em imagens retrato, já que o rosto do
     * personagem costuma estar na metade superior do pôster), então o
     * personagem sempre aparece inteiro e centralizado.
     */
    inner class AvatarDrawView(ctx: Context) : View(ctx) {

        private var drawableRes  = -1
        private var ringColor    = Color.YELLOW
        private var isSelected   = false
        private var ringStrokePx = 3.dp

        private var bgDrawable: Drawable? = null
        private var cachedBitmap: Bitmap? = null

        // Paint do anel
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun bind(
            drawableRes: Int,
            ringColor: Int,
            isSelected: Boolean,
            ringStrokePx: Int
        ) {
            this.drawableRes  = drawableRes
            this.ringColor    = ringColor
            this.isSelected   = isSelected
            this.ringStrokePx = ringStrokePx
            bgDrawable = ContextCompat.getDrawable(context, drawableRes)
            cachedBitmap = extractBitmap(bgDrawable)
            invalidate()
        }

        /** Converte qualquer Drawable num Bitmap para permitir o recorte manual (srcRect/dstRect). */
        private fun extractBitmap(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap

            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 200
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 200
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            return bmp
        }

        override fun onDraw(canvas: Canvas) {
            val w      = width.toFloat()
            val h      = height.toFloat()
            val cx     = w / 2f
            val cy     = h / 2f
            val radius = (w.coerceAtMost(h) / 2f) - ringStrokePx

            // 1. Clip circular + desenha o poster com center-crop real
            val clipPath = Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            val bmp = cachedBitmap
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()
                val cropSize = minOf(bmpW, bmpH)

                val srcLeft = (bmpW - cropSize) / 2f
                // Viés para cima em imagens retrato (rosto costuma ficar no terço superior)
                val topBias = if (bmpH > bmpW) 0.18f else 0.5f
                val srcTop = (bmpH - cropSize) * topBias

                val srcRect = Rect(
                    srcLeft.toInt(),
                    srcTop.toInt(),
                    (srcLeft + cropSize).toInt(),
                    (srcTop + cropSize).toInt()
                )
                val dstRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            } else {
                // Fallback: sem imagem disponível, preenche com cinza escuro
                canvas.drawColor(Color.parseColor("#1A1A1A"))
            }

            canvas.restore()

            // 2. Anel de seleção dourado OU anel sutil da categoria
            if (isSelected) {
                ringPaint.color       = Color.parseColor("#FFD700")
                ringPaint.strokeWidth = ringStrokePx.toFloat()
                canvas.drawCircle(cx, cy, radius - ringStrokePx / 2f, ringPaint)
            } else {
                ringPaint.color       = ringColor
                ringPaint.strokeWidth = 1.5f.dp
                canvas.drawCircle(cx, cy, radius - 1.dp, ringPaint)
            }
        }
    }

    // ─── Constantes de layout ──────────────────────────────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
