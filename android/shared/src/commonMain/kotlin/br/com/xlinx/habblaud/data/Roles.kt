package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.THINKING_LEVELS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Papéis do omp que as Configurações trocam (modelRoles global da conta padrão do servidor). */
val EDITABLE_ROLES = listOf("default", "task", "plan", "advisor", "tiny")

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

private val PROVIDER_LABELS = mapOf(
    "anthropic" to "Anthropic",
    "openai" to "OpenAI",
    "openai-codex" to "OpenAI Codex",
    "google" to "Google",
    "google-antigravity" to "Google Antigravity",
    "openrouter" to "OpenRouter",
    "deepseek" to "DeepSeek",
    "meta" to "Meta",
    "meta-llama" to "Meta Llama",
    "mistralai" to "Mistral",
    "moonshotai" to "Moonshot",
    "qwen" to "Qwen",
    "x-ai" to "xAI",
    "z-ai" to "Z.ai",
    "cohere" to "Cohere",
    "minimax" to "MiniMax",
    "nvidia" to "NVIDIA",
    "xiaomi" to "Xiaomi",
)

/** Nome do provedor para mostrar (`openrouter/anthropic` -> "OpenRouter · Anthropic"; desconhecido -> o id). */
fun providerLabel(id: String): String =
    if ('/' in id) "${providerLabel(id.substringBefore('/'))} · ${providerLabel(id.substringAfter('/'))}"
    else PROVIDER_LABELS[id] ?: id

private fun rawModelName(model: String): String = model.substringAfterLast('/')

private val MODEL_WORD_CASE = mapOf("deepseek" to "DeepSeek", "minimax" to "MiniMax", "glm" to "GLM", "vl" to "VL", "gpt" to "GPT")
private val MODEL_WORD_SEPARATOR = Regex("[-_\\s]+")
private val MODEL_SIZE = Regex("\\d+[bB]")
private val CLAUDE_LEGACY = Regex("^claude-(\\d+(?:[.-]\\d+)*)-([a-z]+)(?=-|$)")
private val CLAUDE_VERSION = Regex("^claude-([a-z]+)-(\\d+(?:-\\d+)+)(?=-|$)")

private fun modelWordsLabel(value: String): String = value.split(MODEL_WORD_SEPARATOR).filter { it.isNotEmpty() }.joinToString(" ") { word ->
    MODEL_WORD_CASE[word.lowercase()] ?: if (MODEL_SIZE.matches(word)) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() }
}

/** Nome legível da opção, sem o provedor, preservando versão, data e variantes. */
fun modelOptionLabel(model: String): String {
    val parts = rawModelName(model).split(':')
    val date = DATED.find(parts.first())?.value
    var name = if (date == null) parts.first() else parts.first().removeSuffix(date)
    name = CLAUDE_LEGACY.replace(name) { "claude-${it.groupValues[2]}-${it.groupValues[1].replace('-', '.')}" }
    name = CLAUDE_VERSION.replace(name) { "claude-${it.groupValues[1]}-${it.groupValues[2].replace('-', '.')}" }
    val label = modelWordsLabel(name).let { if (name.startsWith("gpt-", ignoreCase = true)) it.replaceFirst("GPT ", "GPT-") else it }
    return (listOf(label) + listOfNotNull(date?.removePrefix("-")) + parts.drop(1).map(::modelWordsLabel)).joinToString(" · ")
}

/** "Provedor · modelo" para um id `provedor/modelo`; sem provedor, só o modelo. */
fun modelLabel(model: String) = if ('/' in model) "${providerLabel(modelGroupKey(model))} · ${modelOptionLabel(model)}" else modelOptionLabel(model)

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

private fun modelGroupKey(model: String): String {
    val root = model.substringBefore('/')
    val rest = model.substringAfter('/')
    if (root == "openrouter" && '/' in rest) {
        val vendor = rest.substringBefore('/').removePrefix("~")
        if (vendor.isNotEmpty()) return "$root/$vendor"
    }
    return root
}

/**
 * Opções do seletor de um papel ou sessão, agrupadas por provedor (e por fabricante no OpenRouter), com o nome do
 * modelo sem o prefixo do grupo. Some `:batch` em qualquer provedor; no OpenRouter, some também `:free`/`:extended`
 * e os apelidos `openrouter/~...`. Some a cópia datada (`-AAAAMMDD`) de um modelo que aparece sem data. Dentro do
 * grupo, por família, com a versão mais nova primeiro. `keep` (o modelo atual) fica sempre, mesmo que fosse sumir.
 */
fun roleModelGroups(models: List<String>, keep: String? = null, query: String = ""): List<Pair<String, List<String>>> {
    val all = (models + listOfNotNull(keep)).filter { '/' in it }.toSet()
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = all.filter { m ->
        val hiddenRouterVariant = m.startsWith("openrouter/") && (HIDDEN_VARIANT.containsMatchIn(m) || m.startsWith("openrouter/~"))
        val kept = m == keep || !(m.endsWith(":batch") || hiddenRouterVariant || DATED.find(m)?.let { all.contains(m.substring(0, it.range.first)) } == true)
        if (!kept) false
        else if (m == keep || terms.isEmpty()) true
        else {
            val group = modelGroupKey(m)
            val hay = "${providerLabel(group)} ${modelOptionLabel(m)} $m".lowercase()
            terms.all { hay.contains(it) }
        }
    }
    // A data não conta como versão: `opus-4-20250514` fica junto de `opus-4`, depois de `opus-4-8`.
    val key = { m: String -> rawModelName(m).replace(DATED, "") }
    return shown.groupBy(::modelGroupKey).entries.sortedWith { a, b -> a.key.compareTo(b.key, ignoreCase = true) }
        .map { (provider, list) -> provider to list.sortedWith { a, b -> compareModelNames(key(a), key(b)).takeIf { it != 0 } ?: a.compareTo(b) } }
}

private val DATED = Regex("-\\d{8}$")
private val HIDDEN_VARIANT = Regex(":(?:batch|free|extended)$")

/** Partes alfanuméricas em ordem; texto em ordem alfabética, número do maior para o menor (`opus-5-5` antes de `opus-4-8`). */
private fun compareModelNames(a: String, b: String): Int {
    val x = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val y = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i].toLongOrNull(); val q = y[i].toLongOrNull()
        val c = when {
            p != null && q != null -> q.compareTo(p)
            p != null -> -1
            q != null -> 1
            else -> x[i].compareTo(y[i])
        }
        if (c != 0) return c
    }
    // Um continua o outro: versão a mais (`5` › `5-5`) vem antes; sufixo de variante (`flash` › `flash-lite`) vem depois.
    if (x.size == y.size) return a.compareTo(b)
    val longerFirst = (if (x.size > y.size) x[y.size] else y[x.size]).toLongOrNull() != null
    return if ((x.size > y.size) == longerFirst) -1 else 1
}
