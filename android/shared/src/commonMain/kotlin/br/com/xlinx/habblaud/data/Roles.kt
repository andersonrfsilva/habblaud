package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.THINKING_LEVELS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Papéis do omp que as Configurações trocam (modelRoles global da conta padrão do servidor). */
val EDITABLE_ROLES = listOf("task", "plan", "advisor", "tiny")

/** `provedor/modelo` sem espaço nem controle: o mesmo formato que o servidor aceita para um papel. */
val ROLE_MODEL = Regex("^[A-Za-z0-9][\\w.-]*/[^\\s\\x00-\\x1f\\x7f]{1,200}$")

/** `provedor/modelo:nível` em (modelo, nível); sem um nível conhecido no fim, o `:` é parte do modelo. */
fun splitRole(value: String): Pair<String, String?> {
    val i = value.lastIndexOf(':')
    val level = if (i > value.lastIndexOf('/')) value.substring(i + 1) else null
    return if (level in THINKING_LEVELS) value.substring(0, i) to level else value to null
}

fun joinRole(model: String, level: String?): String = if (level == null) model else "$model:$level"

/** Modelos que os agentes omp abertos anunciam (`models` de cada um no snapshot), sem repetição e em ordem. */
fun snapshotModels(snapshot: String): List<String> =
    wireJson.parseToJsonElement(snapshot).jsonObject.objects("agents").flatMap { agent ->
        (agent["models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    }.distinct().sorted()

/** Nome do provedor para mostrar (o id do omp quando não é um dos conhecidos). */
fun providerLabel(id: String) = when (id) {
    "anthropic" -> "Anthropic"
    "openai-codex" -> "OpenAI Codex"
    "google-antigravity" -> "Google Antigravity"
    "openrouter" -> "OpenRouter"
    else -> id
}

/** "Provedor · modelo" para um id `provedor/modelo`; sem provedor, só o modelo. */
fun modelLabel(model: String) = if ('/' in model) "${providerLabel(model.substringBefore('/'))} · ${model.substringAfter('/')}" else model

/** Família do modelo curto da sessão (`claude-opus-5-5`, `gpt-5.5`, `gemini-3-pro`) para o símbolo junto do avatar. */
fun modelBrand(model: String?): String? {
    val m = model?.lowercase()?.substringAfterLast('/') ?: return null
    return when {
        m.startsWith("claude") -> "claude"
        m.startsWith("gpt") || m.startsWith("codex") || Regex("^o\\d").containsMatchIn(m) -> "openai"
        m.startsWith("gemini") -> "gemini"
        else -> null
    }
}

/**
 * Opções do seletor de um papel, agrupadas por provedor (em ordem alfabética) com o nome do modelo sem o provedor.
 * Some a variante `:batch` (API em lote, não serve para conversa) e a cópia datada (`-AAAAMMDD`) de um modelo que
 * também aparece sem data. Dentro do provedor, por família, com a versão mais nova primeiro. `keep` (o modelo atual
 * do papel) fica sempre, mesmo que fosse sumir.
 */
fun roleModelGroups(models: List<String>, keep: String? = null): List<Pair<String, List<String>>> {
    val all = (models + listOfNotNull(keep)).filter { '/' in it }.toSet()
    val shown = all.filter { m ->
        m == keep || !(m.endsWith(":batch") || DATED.find(m)?.let { all.contains(m.substring(0, it.range.first)) } == true)
    }
    // A data não conta como versão: `opus-4-20250514` fica junto de `opus-4`, depois de `opus-4-8`.
    val key = { m: String -> m.substringAfter('/').replace(DATED, "") }
    return shown.groupBy { it.substringBefore('/') }.entries.sortedWith { a, b -> a.key.compareTo(b.key, ignoreCase = true) }
        .map { (provider, list) -> provider to list.sortedWith { a, b -> compareModelNames(key(a), key(b)).takeIf { it != 0 } ?: a.compareTo(b) } }
}

private val DATED = Regex("-\\d{8}$")

/** Partes alfanuméricas em ordem; texto em ordem alfabética, número do maior para o menor (`opus-5-5` antes de `opus-4-8`). */
private fun compareModelNames(a: String, b: String): Int {
    val x = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val y = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i].toLongOrNull(); val q = y[i].toLongOrNull()
        val c = if (p != null && q != null) q.compareTo(p) else x[i].compareTo(y[i])
        if (c != 0) return c
    }
    // Um continua o outro: versão a mais (`5` › `5-5`) vem antes; sufixo de variante (`flash` › `flash-lite`) vem depois.
    if (x.size == y.size) return a.compareTo(b)
    val longerFirst = (if (x.size > y.size) x[y.size] else y[x.size]).toLongOrNull() != null
    return if ((x.size > y.size) == longerFirst) -1 else 1
}
