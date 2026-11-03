package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.THINKING_LEVELS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Papéis do omp que as Configurações trocam (modelRoles global da conta padrão do servidor). */
val EDITABLE_ROLES = listOf("task", "plan", "advisor")

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
