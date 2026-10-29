package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
 * linhas) só ganhar UMA chamada em onResume. A faixa é criada por código e
 * encaixada logo abaixo do cabeçalho, sem mexer no layout existente.
 * Toque na faixa para dispensá-la até a próxima abertura da Home.
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

    private fun mostrarFaixa(activity: AppCompatActivity, root: View, headerId: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val layout = root as? ConstraintLayout ?: return
        val avisos = LicencaManager.avisos(activity, diasDoPlano(activity))

        var faixa = layout.findViewWithTag<TextView>(TAG_FAIXA)
        if (avisos.isEmpty()) { faixa?.visibility = View.GONE; return }

        if (faixa == null) {
            val dp = activity.resources.displayMetrics.density
            faixa = TextView(activity).apply {
                tag = TAG_FAIXA
                id = View.generateViewId()
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FFB020"))
                textSize = 12.5f
                setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6141416"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#66FFB020"))
                    cornerRadius = 10 * dp
                }
                elevation = 20 * dp
                isClickable = true
                setOnClickListener { visibility = View.GONE }   // dispensa até a próxima vez
                layoutParams = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToBottom = headerId
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin = (6 * dp).toInt()
                    marginStart = (16 * dp).toInt()
                    marginEnd = (16 * dp).toInt()
                }
            }
            layout.addView(faixa)
        }
        val novoTexto = avisos.joinToString("  •  ")
        if (faixa.text.toString() != novoTexto) {
            faixa.text = novoTexto
            faixa.visibility = View.VISIBLE   // texto novo reaparece mesmo se dispensada
        }
    }
}
