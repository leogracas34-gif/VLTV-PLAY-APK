package com.vltv.play

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LicencaVigia — o "vigia da licença" da LG (config.js: LICENCA_VIGIA_S = 30).
 *
 * Enquanto o app está ABERTO (inclusive assistindo filme, série ou TV ao vivo), confere
 * a licença na VPS a cada 30 s. Bloqueou ou venceu no painel → abre a tela de ativação
 * na hora, fechando o resto (o vídeo para). Sem internet / VPS fora do ar: nunca derruba
 * o cliente (só estado "expired" ou "blocked" vindo da VPS bloqueia).
 *
 * Também confere se o CÓDIGO DE PARCEIRO ainda vale no painel: apagado ou suspenso → o
 * aparelho deixa de ser "parceiro" e passa a valer a licença própria dele (teste/ativa);
 * se não tem, vai para a ativação com o aviso da LG.
 *
 * Só roda com o app em primeiro plano (conta as Activities iniciadas) e começa a partir da
 * Home (LicencaHomeHelper.conferir chama iniciar(); é seguro chamar várias vezes).
 */
object LicencaVigia {

    private const val INTERVALO_MS = 30_000L
    const val EXTRA_MSG = "msg_ativacao"
    private const val MSG_PARCEIRO =
        "O código de parceiro desta lista não está mais ativo. Para continuar, ative a licença deste aparelho."

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var registrado = false
    private var iniciadas = 0
    private var app: Application? = null

    fun iniciar(application: Application) {
        app = application
        if (!registrado) {
            registrado = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { iniciadas++; rodar() }
                override fun onActivityStopped(activity: Activity) {
                    iniciadas = (iniciadas - 1).coerceAtLeast(0)
                    if (iniciadas == 0) { job?.cancel(); job = null }   // app em segundo plano: para de conferir
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            iniciadas = 1   // quem chamou iniciar() está com uma Activity na tela
        }
        rodar()
    }

    private fun rodar() {
        if (job?.isActive == true) return
        job = escopo.launch {
            while (isActive) {
                delay(INTERVALO_MS)
                conferir()
            }
        }
    }

    private suspend fun conferir() {
        val a = app ?: return

        // Tela de ativação já aberta: ela mesma confere a cada 10 s.
        // Sem sessão (login/splash): ainda não há o que vigiar.
        val prefs = a.getSharedPreferences("vltv_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getString("username", "").isNullOrBlank()) return

        // 1) Código de parceiro: precisa continuar ativo no painel.
        var msg: String? = null
        if (LicencaManager.ehParceiro(a)) {
            val codigo = LicencaManager.parceiroAtual(a)
            val r = withContext(Dispatchers.IO) { LicencaManager.conferirParceiro(codigo) }
            if (r == "codigo_invalido" || r == "suspenso") {
                LicencaManager.limparParceiro(a)
                msg = MSG_PARCEIRO
            } else {
                return   // parceiro valendo (ou sem rede): fica "Ativo", sem consultar licença própria
            }
        }

        // 2) Licença REAL do aparelho (sem código de parceiro).
        val lic = LicencaManager.verificar(a, parceiroForcado = "")
        if (lic.estado == "erro") return   // sem internet / VPS fora: não derruba o cliente
        if (LicencaManager.precisaAtivar(a, lic)) {
            a.startActivity(Intent(a, AtivacaoActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                if (msg != null) putExtra(EXTRA_MSG, msg)
            })
        }
    }
}
