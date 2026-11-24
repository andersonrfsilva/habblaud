package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Um limite de uso: rótulo (do servidor ou fixo), percentual usado e quando reinicia. */
data class UsageLimit(val label: String, val utilization: Double?, val resetsAt: Long?, val exhausted: Boolean = false, val windowMs: Long? = null)

/** Um grupo de limites: um provedor do omp ou as janelas de uma conta Claude/Codex. */
data class UsageGroup(val provider: String?, val plan: String?, val limits: List<UsageLimit>, val note: String? = null)

/** Uma conta do servidor com o uso do plano (`accounts` do /api/snapshot). */
data class AccountQuota(val id: String, val name: String, val provider: String, val plan: String?, val status: String, val noQuota: Boolean, val groups: List<UsageGroup>, val fetchedAt: Long?)

/** Rótulos das janelas fixas (Claude/Codex), resolvidos pela tela. */
const val WINDOW_FIVE_HOUR = "fiveHour"
const val WINDOW_SEVEN_DAY = "sevenDay"
const val WINDOW_SEVEN_DAY_OPUS = "sevenDayOpus"
const val WINDOW_SEVEN_DAY_SONNET = "sevenDaySonnet"

private const val SEVEN_DAYS_MS = 604_800_000L
private const val FIVE_HOURS_MS = 18_000_000L

fun parseAccounts(snapshot: String): List<AccountQuota> = parseAccounts(wireJson.parseToJsonElement(snapshot).jsonObject)

internal fun parseAccounts(snapshot: JsonObject): List<AccountQuota> =
    snapshot.objects("accounts").mapNotNull { account ->
        val id = account.string("id") ?: return@mapNotNull null
        val usage = account["usage"] as? JsonObject
        val providers = usage?.objects("ompProviders").orEmpty()
        val groups = if (providers.isNotEmpty()) providers.map { p ->
            UsageGroup(p.string("provider"), p.string("plan"), p.objects("limits").mapNotNull { l ->
                UsageLimit(l.string("label") ?: return@mapNotNull null, l.string("utilization")?.toDoubleOrNull(), l.string("resetsAt")?.toLongOrNull(), l.flag("exhausted"), l.string("windowMs")?.toLongOrNull())
            }, p.string("note"))
        } else listOfNotNull(usage?.let { u ->
            val windows = listOf(WINDOW_FIVE_HOUR, WINDOW_SEVEN_DAY, WINDOW_SEVEN_DAY_OPUS, WINDOW_SEVEN_DAY_SONNET).mapNotNull { name ->
                (u[name] as? JsonObject)?.let { w -> UsageLimit(name, w.string("utilization")?.toDoubleOrNull(), w.string("resetsAt")?.toLongOrNull(),
                    exhausted = w.flag("exhausted"), windowMs = if (name == WINDOW_FIVE_HOUR) FIVE_HOURS_MS else SEVEN_DAYS_MS) }
            }
            UsageGroup(null, null, windows, u.string("note")).takeIf { windows.isNotEmpty() || it.note != null }
        })
        AccountQuota(id, account.string("name") ?: id, account.string("provider") ?: "claude", account.string("plan"), account.string("usageStatus") ?: "disabled",
            usage?.flag("noQuota") == true, groups, usage?.string("fetchedAt")?.toLongOrNull())
    }

internal data class WeeklyQuota(val label: String, val utilization: Double, val resetsAt: Long?, val exhausted: Boolean)

/** Mostra a janela semanal mais usada de cada provedor, sem repetir contas de outros servidores. */
internal fun weeklyQuotas(accounts: Iterable<AccountQuota>): List<WeeklyQuota> {
    val rows = linkedMapOf<String, WeeklyQuota>()
    for (account in accounts) for (group in account.groups) {
        val label = group.provider?.let(::providerLabel) ?: account.name
        var worst: UsageLimit? = null
        var maximum = Double.NEGATIVE_INFINITY
        for (limit in group.limits) {
            val used = limit.utilization ?: continue
            if (limit.windowMs == SEVEN_DAYS_MS && used.isFinite() && used >= maximum) {
                maximum = used
                worst = limit
            }
        }
        if (worst != null && (rows[label]?.utilization ?: Double.NEGATIVE_INFINITY) < maximum) {
            rows[label] = WeeklyQuota(label, maximum, worst.resetsAt, worst.exhausted)
        }
    }
    return rows.values.toList()
}
