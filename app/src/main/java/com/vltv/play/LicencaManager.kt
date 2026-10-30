package com.vltv.play

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * LicencaManager — licença do APLICATIVO (igual à do app da LG).
 *
 * Regra (mesma da LG): o aparelho se apresenta à VPS (POST /lg/register) e
 * recebe um estado: trial (teste grátis de 7 dias) | active (licença paga,
 * anual ou vitalícia) | expired | blocked. A licença é paga FORA do app: o
 * cliente vê o "Código do aparelho", ativa no site e o app confere de novo.
 *
 * ✅ DECISÃO DE ARQUITETURA: reaproveita exatamente os endpoints que a LG já
 * usa (/lg/register, /lg/status), em vez de criar um backend novo. Como o
 * Android não expõe o MAC, a identidade do aparelho é "uid-" + 24 hex
 * derivados do ANDROID_ID (mesmo formato "uid-..." que a LG usa quando não
 * tem MAC). Ela é recalculada sempre, não é guardada: assim um backup
 * restaurado em OUTRO aparelho não herda o teste do aparelho antigo (o cache
 * guarda o id e é ignorado se não bater).
 *
 * Sem internet: vale o último estado válido (teste/ativa) por até 3 dias.
 * Conta de PARCEIRO (marcada com marcarParceiro) fica "Ativa" sem teste,
 * sem vencimento e sem bloqueio, como na LG.
 */
object LicencaManager {

    data class Estado(
        val estado: String,          // trial | active | expired | blocked | erro
        val vitalicio: Boolean = false,
        val codigo: String = "",
        val dias: Int = 0,
        val expiraEm: Long = 0L,     // epoch em segundos
        val pagarUrl: String = "",
        val texto: String = ""       // mensagem quando estado = erro
    )

    private const val PREFS = "vltv_licenca"
    private const val URL_BASE = "https://api.vltvplay.tech"
    private const val TOLERANCIA_OFFLINE_S = 3 * 86400L
    const val AVISO_VENCIMENTO_DIAS = 7
    /** A pílula da Home só aparece quando faltam este número de dias (ou menos). */
    const val AVISO_HOME_DIAS = 2

    private val JSON_MT = "application/json".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(9, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .dns(XtreamApi.buildSafeDns())   // mesmo DNS seguro do login
            .build()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun agoraS() = System.currentTimeMillis() / 1000L

    // ── Identidade do aparelho ──────────────────────────────────────────────
    fun deviceId(ctx: Context): String {
        val androidId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { null }
        val base = if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") {
            // Raríssimo (aparelhos com ANDROID_ID quebrado): id aleatório guardado.
            prefs(ctx).getString("id_fallback", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs(ctx).edit().putString("id_fallback", it).apply()
            }
        } else androidId
        val hash = MessageDigest.getInstance("SHA-256").digest("vltv-play:$base".toByteArray())
        val hex = hash.joinToString("") { "%02X".format(it) }
        return "uid-" + hex.substring(0, 24)
    }

    // ── Conta de parceiro (exceção da regra, como na LG) ─────────────────────
    fun marcarParceiro(ctx: Context, codigo: String) {
        prefs(ctx).edit().putString("parceiro", codigo.uppercase()).apply()
    }
    fun limparParceiro(ctx: Context) { prefs(ctx).edit().remove("parceiro").apply() }
    fun ehParceiro(ctx: Context): Boolean = !prefs(ctx).getString("parceiro", "").isNullOrBlank()
    fun parceiroAtual(ctx: Context): String = prefs(ctx).getString("parceiro", "") ?: ""

    /**
     * O código de parceiro ainda vale no painel? (mesmo endpoint da LG)
     * Devolve: ok | codigo_invalido | suspenso | limite | rede.
     * Só "codigo_invalido" e "suspenso" tiram a condição de parceiro; "rede"/"limite" nunca derrubam ninguém.
     */
    fun conferirParceiro(codigo: String): String {
        return try {
            val req = Request.Builder()
                .url(URL_BASE + "/parceiro/resolver?codigo=" + java.net.URLEncoder.encode(codigo, "UTF-8"))
                .get().build()
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 404 -> "codigo_invalido"
                    r.code == 403 -> "suspenso"
                    r.code == 429 -> "limite"
                    !r.isSuccessful -> "rede"
                    else -> "ok"
                }
            }
        } catch (e: Exception) { "rede" }
    }

    // ── Cache local ─────────────────────────────────────────────────────────
    private fun lerCache(ctx: Context): Triple<Estado, String, Long>? {
        return try {
            val j = JSONObject(prefs(ctx).getString("cache", null) ?: return null)
            if (j.optString("id") != deviceId(ctx)) return null
            Triple(paraEstado(j.getJSONObject("e")), j.optString("token"), j.optLong("em"))
        } catch (e: Exception) { null }
    }

    private fun paraEstado(j: JSONObject): Estado {
        val exp = j.optLong("expira_em", 0L)
        return Estado(
            estado = j.optString("estado"),
            vitalicio = j.optBoolean("vitalicio", false) || (exp > 0 && exp - agoraS() > 10L * 365 * 86400),
            codigo = j.optString("codigo"),
            dias = j.optInt("dias", 0),
            expiraEm = exp,
            pagarUrl = j.optString("pagar_url")
        )
    }

    private fun guardar(ctx: Context, j: JSONObject, token: String): Estado {
        val e = paraEstado(j)
        val eJson = JSONObject()
            .put("estado", e.estado).put("vitalicio", e.vitalicio).put("codigo", e.codigo)
            .put("dias", e.dias).put("expira_em", e.expiraEm).put("pagar_url", e.pagarUrl)
        val cache = JSONObject().put("e", eJson).put("token", token)
            .put("id", deviceId(ctx)).put("em", agoraS())
        prefs(ctx).edit().putString("cache", cache.toString()).apply()
        return e
    }

    /** Último estado guardado neste aparelho (sem rede). Serve para mostrar o código e o texto. */
    fun estadoSalvo(ctx: Context): Estado? = lerCache(ctx)?.first

    /**
     * Abertura rápida: se há um estado guardado que ainda vale (teste ou ativa,
     * não vencido, visto há menos de 3 dias), o app abre na hora e a VPS é
     * conferida por trás. Caso contrário devolve null e a abertura espera a VPS.
     */
    fun estadoRapido(ctx: Context): Estado? {
        if (ehParceiro(ctx)) return Estado(estado = "active", codigo = estadoSalvo(ctx)?.codigo ?: "")
        val (e, _, em) = lerCache(ctx) ?: return null
        if (e.estado != "trial" && e.estado != "active") return null
        if (e.expiraEm > 0 && e.expiraEm < agoraS()) return null
        if (agoraS() - em > TOLERANCIA_OFFLINE_S) return null
        return e
    }

    // ── Rede ────────────────────────────────────────────────────────────────
    private data class Resp(val status: Int, val json: JSONObject)

    private fun chamar(metodo: String, caminho: String, corpo: JSONObject?, token: String?): Resp {
        val rb = Request.Builder().url(URL_BASE + caminho)
        if (token != null) rb.header("Authorization", "Bearer $token")
        if (metodo == "POST") rb.post((corpo ?: JSONObject()).toString().toRequestBody(JSON_MT)) else rb.get()
        http.newCall(rb.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            return Resp(r.code, j)
        }
    }

    /**
     * Confere a licença na VPS. Resolve sempre com um Estado:
     * trial | active | expired | blocked | erro.
     * Sem internet, cai no último estado válido (até 3 dias).
     */
    suspend fun verificar(ctx: Context, loginInformado: String = "", parceiroForcado: String? = null): Estado = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val id = deviceId(app)
        // parceiroForcado (igual à LG): "" = "a lista que vai entrar NÃO é de parceiro" — confere a licença
        // REAL do aparelho, sem o código de parceiro que ficou guardado da lista anterior.
        val parceiro = (parceiroForcado ?: prefs(app).getString("parceiro", "") ?: "").uppercase()
        // Login (só o usuário, nunca a senha) e modo seguem junto em toda consulta, para o painel mostrar
        // quem está usando este aparelho. Fica guardado (como a LG) para as consultas sem login explícito.
        if (loginInformado.isNotBlank()) prefs(app).edit().putString("login", loginInformado).apply()
        val login = loginInformado.ifBlank { prefs(app).getString("login", "") ?: "" }
        val modo = XtreamApi.modoLogin(app)
        val cache = lerCache(app)

        fun offline(): Estado {
            if (cache != null) {
                val (e, _, em) = cache
                val venceu = e.expiraEm > 0 && e.expiraEm < agoraS()
                if (agoraS() - em < TOLERANCIA_OFFLINE_S && !venceu && (e.estado == "trial" || e.estado == "active")) return e
            }
            return Estado(estado = "erro", texto = "Conecte o aparelho à internet e tente de novo.")
        }

        fun registrar(): Estado {
            return try {
                val versao = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
                val corpo = JSONObject()
                    .put("device_id", id).put("mac", "")
                    .put("model", ((Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "")).trim())
                    .put("brand", "android")   // a VPS só conhece LG | Samsung | Roku | Android
                    .put("version", versao).put("parceiro", parceiro)
                    .put("login", login).put("modo", modo)
                val r = chamar("POST", "/lg/register", corpo, null)
                if (r.status == 200 && r.json.optString("estado").isNotBlank()) {
                    guardar(app, r.json, r.json.optString("token"))
                } else if (r.status >= 500 || r.status == 0) offline()
                else Estado(estado = "erro", texto = r.json.optString("error"))
            } catch (e: Exception) { offline() }
        }

        if (cache != null && cache.second.isNotBlank()) {
            try {
                val q = "?parceiro=" + java.net.URLEncoder.encode(parceiro, "UTF-8") +
                        (if (login.isNotBlank()) "&login=" + java.net.URLEncoder.encode(login, "UTF-8") else "") +
                        "&modo=" + java.net.URLEncoder.encode(modo, "UTF-8")
                val r = chamar("GET", "/lg/status$q", null, cache.second)
                when {
                    r.status == 200 && r.json.optString("estado").isNotBlank() ->
                        guardar(app, r.json, r.json.optString("token").ifBlank { cache.second })
                    r.status == 401 || r.status == 404 -> registrar()   // token vencido: se apresenta de novo
                    else -> offline()
                }
            } catch (e: Exception) { offline() }
        } else registrar()
    }

    /** Aparelho sem direito de uso agora? (vencido/bloqueado e não é de parceiro) */
    fun precisaAtivar(ctx: Context, e: Estado): Boolean =
        !ehParceiro(ctx) && (e.estado == "expired" || e.estado == "blocked")

    // ── Textos (mesmos da LG) ───────────────────────────────────────────────
    private fun dataBr(s: Long): String =
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(s * 1000))

    /** Ex.: "Teste grátis: 5 dias (até 12/11/2026)", "Ativa até 07/10/2027", "Vitalícia (sem vencimento)". */
    fun textoCurto(ctx: Context): String {
        if (ehParceiro(ctx)) return "Ativo"
        val e = estadoSalvo(ctx) ?: return ""
        return when {
            e.estado == "active" ->
                if (e.vitalicio) "Vitalícia (sem vencimento)" else "Ativa até " + dataBr(e.expiraEm)
            e.estado == "trial" ->
                "Teste grátis: ${e.dias} " + (if (e.dias == 1) "dia" else "dias") + " (até " + dataBr(e.expiraEm) + ")"
            e.estado == "expired" -> "Vencida"
            e.estado == "blocked" -> "Bloqueada"
            else -> ""
        }
    }

    /** "Teste", "Anual" ou "Vitalícia" para a linha de Configurações. */
    fun tipoCurto(ctx: Context): String {
        if (ehParceiro(ctx)) return "Parceiro"
        val e = estadoSalvo(ctx) ?: return ""
        return when {
            e.estado == "trial" -> "Teste"
            e.estado == "active" && e.vitalicio -> "Vitalícia"
            e.estado == "active" -> "Anual"
            e.estado == "expired" -> "Vencida"
            else -> ""
        }
    }

    // ── Resumo para a tela de Configurações e para o aviso da Home ───────────
    data class Resumo(
        val titulo: String,       // "Teste grátis", "Licença ativa"...
        val detalhe: String,      // "7 dias restantes • até 05/11/2026"
        val badge: String,        // "TESTE", "ATIVA"... ("" = sem selo)
        val corHex: String,       // cor do selo/título
        val codigo: String,       // código do aplicativo (vazio = ainda não veio da VPS)
        val ajuda: String         // frase curta embaixo do código
    )

    /** Site onde o cliente informa o código (o mesmo texto da tela de ativação). */
    fun siteAtivacao(ctx: Context): String =
        estadoSalvo(ctx)?.pagarUrl?.takeIf { it.isNotBlank() } ?: "vltvplay.tech/ativar"

    private fun diasAte(seg: Long): Int =
        Math.ceil((seg * 1000.0 - System.currentTimeMillis()) / 86400000.0).toInt()

    fun resumo(ctx: Context): Resumo {
        val e = estadoSalvo(ctx)
        val codigo = e?.codigo.orEmpty()
        val site = siteAtivacao(ctx)
        val ajudaAtivar = "Para ativar, acesse $site no celular ou computador e informe este código."
        val verde = "#5BE3A4"; val ambar = "#FFB020"; val vermelho = "#FF6B6B"
        if (ehParceiro(ctx)) {
            return Resumo("Conta de parceiro", "Licença do aplicativo sempre ativa", "PARCEIRO", verde, codigo,
                "Se precisar de suporte, informe este código.")
        }
        if (e == null) {
            return Resumo("Verificando licença...", "", "", ambar, codigo, "Conecte à internet para ver o código do aplicativo.")
        }
        return when (e.estado) {
            "active" ->
                if (e.vitalicio) Resumo("Licença vitalícia", "Sem vencimento", "VITALÍCIA", verde, codigo,
                    "Se precisar de suporte, informe este código.")
                else Resumo("Licença ativa", "Válida até " + dataBr(e.expiraEm), "ATIVA", verde, codigo,
                    "Para renovar, acesse $site e informe este código.")
            "trial" -> {
                val d = if (e.expiraEm > 0) diasAte(e.expiraEm).coerceAtLeast(0) else e.dias
                Resumo("Teste grátis",
                    "$d " + (if (d == 1) "dia restante" else "dias restantes") + " • até " + dataBr(e.expiraEm),
                    "TESTE", ambar, codigo, ajudaAtivar)
            }
            "expired" -> Resumo("Licença vencida", "Ative para continuar usando", "VENCIDA", vermelho, codigo, ajudaAtivar)
            "blocked" -> Resumo("Aparelho bloqueado", "Fale com o suporte", "BLOQUEADO", vermelho, codigo,
                "Informe este código ao suporte.")
            else -> Resumo("Licença não verificada", "Sem resposta do servidor", "", ambar, codigo,
                "Conecte à internet e abra de novo.")
        }
    }

    /**
     * Texto CURTO para a pílula da Home (o aviso mais urgente). Os avisos completos
     * continuam em avisos(). Devolve null quando não há nada para avisar.
     */
    fun avisoCurto(ctx: Context, diasPlano: Long?): String? {
        data class Item(val dias: Long, val texto: String)
        val itens = mutableListOf<Item>()
        fun quando(d: Long) = when (d) { 0L -> "hoje"; 1L -> "amanhã"; else -> "em $d dias" }

        val e = estadoSalvo(ctx)
        if (e != null && !ehParceiro(ctx) && !e.vitalicio && e.expiraEm > 0 &&
            (e.estado == "trial" || e.estado == "active")) {
            val d = diasAte(e.expiraEm).toLong()
            if (d in 0..AVISO_HOME_DIAS.toLong()) {
                itens.add(Item(d, if (e.estado == "trial") "Teste termina ${quando(d)}" else "Licença vence ${quando(d)}"))
            }
        }
        if (diasPlano != null) {
            if (diasPlano < 0) itens.add(Item(-1, "Plano vencido"))
            else if (diasPlano <= AVISO_HOME_DIAS) itens.add(Item(diasPlano, "Plano vence ${quando(diasPlano)}"))
        }
        if (itens.isEmpty()) return null
        val primeiro = itens.minByOrNull { it.dias }!!
        return if (itens.size > 1) primeiro.texto + "  (+${itens.size - 1})" else primeiro.texto
    }

    /**
     * Avisos da Home: licença do app (teste acabando ou licença vencendo) e plano
     * de canais/filmes/séries (lista vencendo). diasPlano = null quando não vale
     * (vitalício ou sem informação).
     */
    fun avisos(ctx: Context, diasPlano: Long?, limite: Int = AVISO_VENCIMENTO_DIAS): List<String> {
        val lista = mutableListOf<String>()
        val e = estadoSalvo(ctx)
        if (e != null && !ehParceiro(ctx) && !e.vitalicio && e.expiraEm > 0 &&
            (e.estado == "trial" || e.estado == "active")) {
            val d = Math.ceil((e.expiraEm * 1000.0 - System.currentTimeMillis()) / 86400000.0).toInt()
            if (d in 0..limite) {
                val quando = when (d) { 0 -> "hoje"; 1 -> "amanhã"; else -> "em $d dias" }
                lista.add(if (e.estado == "trial") "Seu teste grátis do aplicativo termina $quando"
                          else "A licença do aplicativo vence $quando")
            }
        }
        if (diasPlano != null) {
            if (diasPlano < 0) lista.add("O plano de canais, filmes e séries venceu. Fale com o seu provedor.")
            else if (diasPlano <= limite) {
                lista.add("O plano de canais, filmes e séries vence " +
                        (when (diasPlano) { 0L -> "hoje"; 1L -> "amanhã"; else -> "em $diasPlano dias" }))
            }
        }
        return lista
    }
}
