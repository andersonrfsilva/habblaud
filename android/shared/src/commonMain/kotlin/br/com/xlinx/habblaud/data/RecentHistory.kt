package br.com.xlinx.habblaud.data

import kotlinx.coroutines.flow.MutableStateFlow

private const val LAST_RECENT_STEP = 4

/** Última resposta bem-sucedida de um servidor, com o degrau usado no pedido. */
data class RecentWindow(val requestedStep: Int, val days: Int, val maxStep: Int = 1)

internal data class RecentLoad(val step: Int = 1, val loading: Boolean = false, val failed: Boolean = false) {
    fun begin(): RecentLoad = if (loading || step >= LAST_RECENT_STEP) this else RecentLoad(step + 1, loading = true)

    fun finish(anyLoaded: Boolean): RecentLoad = when {
        !loading -> this
        anyLoaded -> copy(loading = false)
        else -> RecentLoad(step - 1, failed = true)
    }
}

// Compartilhado pelas telas e pelos controladores enquanto o processo está vivo.
internal val recentLoad = MutableStateFlow(RecentLoad())

internal data class RecentFooter(val days: Int?, val canLoadMore: Boolean)

internal fun recentFooter(windows: List<RecentWindow>, load: RecentLoad): RecentFooter =
    RecentFooter(
        windows.maxOfOrNull { it.days },
        load.step < LAST_RECENT_STEP && windows.any { it.maxStep > load.step },
    )
