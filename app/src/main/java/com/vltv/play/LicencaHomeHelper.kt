package com.vltv.play

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * LicencaHomeHelper — o que a LG mostra na Home sobre licença e vencimento.
 *
 * 1) Confere a licença do aparelho na VPS ao abrir a Home (no máximo 1 vez a
 *    cada 10 min) e liga o LicencaVigia, que confere a cada 30 s com o app aberto. Se venceu/bloqueou (e não é conta de
 *    parceiro), abre a tela de ativação e fecha o resto — igual ao "vigia"
 *    da LG.
 * 2) Mostra uma faixa âmbar na Home quando o TESTE do aplicativo está acabando
 *    (ou a licença está vencendo) e quando a LISTA (plano de canais, filmes e
 *    séries) está vencendo, com o limite de 7 dias da LG.
 *
 * ✅ DECISÃO DE ARQUITETURA: fica num arquivo próprio para a HomeActivity (3 mil
 * linhas) só ganhar UMA chamada em onResume. O aviso é uma pílula pequena criada
 * por código DENTRO do cabeçalho (ao lado do botão de Downloads), sem cobrir a
 * imagem do banner e sem mexer no layout existente. Toque nela para ver todos os
 * avisos e o código do aplicativo (como a LG mostra no canto da Home).
 */
object LicencaHomeHelper {

    private const val INTERVALO_LICENCA_MS = 10 * 60 * 1000L
    private const val INTERVALO_PLANO_MS = 30 * 60 * 1000L
    private const val TAG_FAIXA = "faixa_aviso_licenca"

    private var ultimaLicencaMs = 0L
    private var ultimoPlanoMs = 0L

    fun conferir(activity: AppCompatActivity, root: View, headerId: Int) {
        // Mostra já com o que está guardado; as conferências abaixo atualizam depois.
        mostrarFaixa(activity, root, headerId)

        // Vigia (30 s): continua conferindo a licença mesmo assistindo filme/série/TV ao vivo.
        LicencaVigia.iniciar(activity.application)

        val agora = System.currentTimeMillis()

        if (agora - ultimaLicencaMs > INTERVALO_LICENCA_MS) {
            ultimaLicencaMs = agora
            activity.lifecycleScope.launch {
                val lic = LicencaManager.verificar(activity)
                if (LicencaManager.precisaAtivar(activity, lic)) {
                    activity.startActivity(Intent(activity, AtivacaoActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    activity.finish()
                    return@launch
                }
                mostrarFaixa(activity, root, headerId)
            }
        }

        if (agora - ultimoPlanoMs > INTERVALO_PLANO_MS) {
            ultimoPlanoMs = agora
            atualizarPlano(activity) { mostrarFaixa(activity, root, headerId) }
        }
    }

    // Mesma consulta que as Configurações já fazem; guarda exp_date para o aviso.
    private fun atualizarPlano(activity: AppCompatActivity, depois: () -> Unit) {
        val prefs = activity.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("username", "").orEmpty()
        val pass = prefs.getString("password", "").orEmpty()
        if (user.isBlank() || pass.isBlank()) return
        try {
            XtreamApi.service.login(user, pass).enqueue(object : Callback<XtreamLoginResponse> {
                override fun onResponse(call: Call<XtreamLoginResponse>, response: Response<XtreamLoginResponse>) {
                    val info = response.body()?.user_info ?: return
                    prefs.edit().putString("exp_date", info.exp_date ?: "").apply()
                    activity.runOnUiThread { depois() }
                }
                override fun onFailure(call: Call<XtreamLoginResponse>, t: Throwable) { /* segue com o guardado */ }
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun diasDoPlano(ctx: Context): Long? {
        val exp = ctx.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).getString("exp_date", null)
        if (exp == null) return null                    // ainda não consultado
        val info = PlanoUtils.classificarPlano(exp)
        return when {
            info.isVitalicio -> null
            info.isExpirado -> -1L
            else -> info.diasRestantes
        }
    }

    // Pílula discreta no cabeçalho (ao lado do botão de Downloads), em vez de uma faixa sobre a imagem.
    // Mostra só o aviso mais urgente; tocando nela abre um quadro com todos os avisos e o código do app.
    private fun mostrarFaixa(activity: AppCompatActivity, root: View, headerId: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val layout = root as? ConstraintLayout ?: return
        val curto = LicencaManager.avisoCurto(activity, diasDoPlano(activity))

        var pilula = layout.findViewWithTag<TextView>(TAG_FAIXA)
        if (curto == null) { pilula?.visibility = View.GONE; return }

        val dp = activity.resources.displayMetrics.density
        if (pilula == null) {
            val ponto = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFB020"))
                setSize((6 * dp).toInt(), (6 * dp).toInt())
            }
            pilula = TextView(activity).apply {
                tag = TAG_FAIXA
                id = View.generateViewId()
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor("#FFB020"))
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (190 * dp).toInt()
                compoundDrawablePadding = (6 * dp).toInt()
                setCompoundDrawablesWithIntrinsicBounds(ponto, null, null, null)
                setPadding((10 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#26FFB020"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#4DFFB020"))
                    cornerRadius = 14 * dp
                }
                // O cabeçalho tem elevação 20 + translationZ 20; a pílula precisa ficar acima dele, sem sombra.
                z = 50 * dp
                outlineProvider = null
                isClickable = true
                isFocusable = true
                setOnClickListener { abrirQuadro(activity) }
                layoutParams = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = headerId
                    bottomToBottom = headerId
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    // 18dp de margem do cabeçalho + 40dp do botão de Downloads + 10dp de respiro
                    marginEnd = (68 * dp).toInt()
                }
            }
            layout.addView(pilula)
        }
        pilula.text = curto
        pilula.visibility = View.VISIBLE
    }

    // Quadro próprio do app (cartão escuro arredondado, igual ao resto das telas), em vez do diálogo padrão do Android.
    private fun abrirQuadro(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val avisos = LicencaManager.avisos(activity, diasDoPlano(activity), LicencaManager.AVISO_HOME_DIAS)
        val resumo = LicencaManager.resumo(activity)
        val dm = activity.resources.displayMetrics
        val dp = dm.density
        fun px(v: Int) = (v * dp).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(22), px(22), px(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181818"))
                setStroke(px(1), Color.parseColor("#26FFFFFF"))
                cornerRadius = 22 * dp
            }
        }

        // Título
        card.addView(TextView(activity).apply {
            text = "Avisos do aplicativo"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })

        // Lista de avisos, cada um com um pontinho âmbar
        avisos.forEach { aviso ->
            val linha = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, px(14), 0, 0)
            }
            val ponto = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FFB020"))
                }
            }
            linha.addView(ponto, LinearLayout.LayoutParams(px(8), px(8)).apply {
                topMargin = px(6); marginEnd = px(12)
            })
            linha.addView(TextView(activity).apply {
                text = aviso
                setTextColor(Color.parseColor("#E8E8E8"))
                textSize = 14.5f
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(linha)
        }

        // Código do aplicativo
        if (resumo.codigo.isNotBlank()) {
            card.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#1AFFFFFF")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                    topMargin = px(18); bottomMargin = px(16)
                })
            card.addView(TextView(activity).apply {
                text = "CÓDIGO DO APLICATIVO"
                setTextColor(Color.parseColor("#888888"))
                textSize = 11f
                letterSpacing = 0.1f
                typeface = Typeface.DEFAULT_BOLD
            })
            val linhaCodigo = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, 0)
            }
            linhaCodigo.addView(TextView(activity).apply {
                text = resumo.codigo
                setTextColor(Color.WHITE)
                textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.12f
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            linhaCodigo.addView(TextView(activity).apply {
                text = "Copiar"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(px(16), px(9), px(16), px(9))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#262626"))
                    cornerRadius = 12 * dp
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("codigo", resumo.codigo))
                    Toast.makeText(activity, "Código copiado", Toast.LENGTH_SHORT).show()
                }
            })
            card.addView(linhaCodigo)
            if (resumo.ajuda.isNotBlank()) {
                card.addView(TextView(activity).apply {
                    text = resumo.ajuda
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 12f
                    setPadding(0, px(10), 0, 0)
                })
            }
        }

        // Botão Fechar
        card.addView(TextView(activity).apply {
            text = "Fechar"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F3EF"))
                cornerRadius = 12 * dp
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)).apply { topMargin = px(20) })

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.65f)
            val largura = minOf(dm.widthPixels - px(32), px(440))
            setLayout(largura, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }
}
