package com.vltv.play

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vltv.play.databinding.ActivityAtivacaoBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AtivacaoActivity — tela de ativação da licença (igual à "tela-licenca" da LG).
 *
 * Aparece quando: o teste de 7 dias acabou / a licença venceu ("Ative o seu
 * VLTV Play"), o aparelho foi bloqueado ("Aparelho bloqueado") ou não há
 * conexão com a VPS na 1ª abertura ("Sem conexão com o servidor").
 *
 * A licença é paga FORA do app: o cliente informa o "Código deste aparelho"
 * no site. Aqui o app confere sozinho a cada 10 s (igual ao poll da LG) e
 * também pelo botão "Já ativei — verificar". Liberou → volta ao fluxo normal.
 */
class AtivacaoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAtivacaoBinding
    private var verificando = false
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAtivacaoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLicVerificar.setOnClickListener { verificar() }
        binding.boxCodigo.setOnClickListener {
            val cod = binding.tvLicCodigo.text.toString()
            if (cod.isNotBlank() && cod != "--------") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("codigo", cod))
                Toast.makeText(this, "Código copiado", Toast.LENGTH_SHORT).show()
            }
        }

        // Mostra já o que está guardado; a conferência com a VPS vem em seguida.
        mostrar(LicencaManager.estadoSalvo(this) ?: LicencaManager.Estado(estado = "expired"))
        verificar()
    }

    override fun onResume() {
        super.onResume()
        iniciarPoll()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    // Confere sozinho a cada 10 s enquanto a tela está aberta.
    private fun iniciarPoll() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                verificar(silencioso = true)
            }
        }
    }

    private fun mostrar(lic: LicencaManager.Estado) {
        val sem = lic.estado == "erro"
        val bloqueado = lic.estado == "blocked"

        binding.tvLicTitulo.text = when {
            bloqueado -> "Aparelho bloqueado"
            sem -> "Sem conexão com o servidor"
            else -> "Ative o seu VLTV Play"
        }
        binding.tvLicMsg.text = when {
            bloqueado -> "Este aparelho foi bloqueado. Fale com o suporte informando o código abaixo."
            sem -> lic.texto.ifBlank { "Conecte o aparelho à internet e toque em tentar novamente." }
            else -> intent.getStringExtra(LicencaVigia.EXTRA_MSG)
                ?: "Para continuar assistindo, ative a licença deste aparelho."
        }
        binding.boxCodigo.visibility = if (sem) View.GONE else View.VISIBLE
        binding.tvLicCodigo.text = lic.codigo.ifBlank { "--------" }

        if (!sem && !bloqueado) {
            val site = lic.pagarUrl.ifBlank { "vltvplay.tech/ativar" }
            binding.tvLicSite.visibility = View.VISIBLE
            binding.tvLicSite.text = "No celular ou computador, acesse $site e informe o código."
        } else {
            binding.tvLicSite.visibility = View.GONE
        }
        binding.btnLicVerificar.text = if (sem) "Tentar novamente" else "Já ativei — verificar"
    }

    private fun verificar(silencioso: Boolean = false) {
        if (verificando) return
        verificando = true
        if (!silencioso) {
            binding.progressLic.visibility = View.VISIBLE
            binding.tvLicStatus.text = ""
            binding.btnLicVerificar.isEnabled = false
        }
        lifecycleScope.launch {
            val lic = LicencaManager.verificar(this@AtivacaoActivity)
            verificando = false
            binding.progressLic.visibility = View.INVISIBLE
            binding.btnLicVerificar.isEnabled = true

            if (lic.estado == "trial" || lic.estado == "active") {
                // Liberou: volta ao fluxo normal (Splash → Login → Perfis).
                startActivity(Intent(this@AtivacaoActivity, SplashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return@launch
            }
            mostrar(lic)
            if (!silencioso && lic.estado != "erro") {
                binding.tvLicStatus.setTextColor(Color.parseColor("#9C9CA8"))
                binding.tvLicStatus.text = "A licença ainda não foi ativada."
            }
        }
    }

    // Voltar sai do app: sem licença não há para onde voltar.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAffinity()
    }
}
