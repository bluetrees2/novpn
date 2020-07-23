package com.github.bluetrees2.novpn

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeoutException

class IPTablesModelAsync(val context: Context?) {

    private val iptablesCombinedModel = IPTablesCombinedModel(RootShellModel.instance)

    val iptablesOK: Boolean get() = !iptablesHalted
    @Suppress("unused")
    val ip6tablesOK: Boolean get() = !ip6tablesHalted
    val ip6tablesRejectOK: Ternary get() = ip6tablesRejectState

    var onCompletionListener: (suspend () -> Unit)? = null
    var onErrorListener: (suspend (Throwable) -> Unit)? = null

    private var iptablesHalted = false
    private var ip6tablesHalted = false
    private var ip6tablesRejectState = Ternary.UNKNOWN

    private val scope = SequentialScope()

    init {
        scope.onCompletionListener = { cause ->
            if (cause !is CancellationException)
                onCompletionListener?.invoke()
        }
    }

    private val activeModel: IPTablesModelI?
        get() = when {
            iptablesHalted -> null
            ip6tablesHalted -> iptablesCombinedModel.iptables
            else -> iptablesCombinedModel
        }

    private suspend fun logError(e: Throwable, priority: Int? = null) = withContext(Dispatchers.Main) {
        context?:return@withContext
        with(Log) {
            when (e) {
                is RootError -> ERROR to context.getString(R.string.log_root_error)
                is TimeoutException -> ERROR to context.getString(R.string.log_timeout_error)
                is IPTablesError -> ERROR to context.getString(R.string.log_iptables_error)
                is IP6TablesError -> WARN to context.getString(R.string.log_ip6tables_error)
                else -> ERROR to context.getString(R.string.log_unknown_error) + " $e"
            }.let {
                println(priority ?: it.first, "Main", it.second)
            }
        }
    }

    private suspend fun addOrReject(
        add: suspend (IPTablesModelI) -> Unit,
        reject: suspend (IPTablesModelI) -> Unit
    ) {
        if (iptablesHalted)
            return
        try {
            add(iptablesCombinedModel.iptables)
        } catch (e: Throwable) {
            if (e !is CancellationException) {
                iptablesHalted = true
                logError(e)
                onErrorListener?.invoke(e)
            }
            return
        }
        var ip6Error: Throwable? = null
        if (!ip6tablesHalted) {
            try {
                add(iptablesCombinedModel.ip6tables)
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    ip6tablesHalted = true
                    logError(e)
                    if (e !is IP6TablesError || ip6tablesRejectState == Ternary.NO) {
                        onErrorListener?.invoke(e)
                        return
                    }
                    ip6Error = e
                }
            }
        }
        if (ip6tablesHalted && ip6tablesRejectState != Ternary.NO) {
            try {
                reject(iptablesCombinedModel.ip6tables)
                ip6tablesRejectState = Ternary.YES
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    ip6tablesRejectState = Ternary.NO
                    logError(e, Log.VERBOSE)
                    onErrorListener?.invoke(ip6Error ?: e)
                    return
                }
            }
        }
        ip6Error?:return
        onErrorListener?.invoke(ip6Error)
    }

    suspend fun addUID(uid: Int) {
        scope.launch {
            addOrReject({ m -> m.addUID(uid) }, { m -> m.rejectUID(uid) })
        }
    }

    suspend fun clearAndAddUIDs(uidList: List<Int>) {
        scope.launch {
            addOrReject(
                { m -> m.clearAndAddUIDs(uidList) },
                { m -> m.clearAndRejectUIDs(uidList) })
        }
    }

    private fun updateFlagsOnError(e: Throwable) {
        when(e) {
            is IP6TablesError -> ip6tablesHalted = true
            else -> iptablesHalted = true
        }
    }

    private suspend fun safeExec(block: suspend () -> Unit, onError: suspend (Throwable) -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            onError(e)
        }
    }

    suspend fun cancel() {
        scope.cancel()
        iptablesHalted = false
        ip6tablesHalted = false
        ip6tablesRejectState = Ternary.UNKNOWN
    }

    suspend fun cleanup() {
        scope.cancel()
        iptablesHalted = false
        ip6tablesHalted = false
        ip6tablesRejectState = Ternary.UNKNOWN
        scope.launch {
            safeExec({ iptablesCombinedModel.cleanup() }) { e ->
                if (e !is CancellationException) {
                    updateFlagsOnError(e)
                    logError(e)
                    onErrorListener?.invoke(e)
                }
            }
        }
    }

    suspend fun enable() {
        scope.launch {
            safeExec({ activeModel?.enable() }) { e ->
                if (e !is CancellationException) {
                    updateFlagsOnError(e)
                    if (e is IP6TablesError)
                        ip6tablesRejectState = Ternary.NO
                    logError(e)
                    onErrorListener?.invoke(e)
                }
            }
        }
    }

    suspend fun masquerade() {
        scope.launch {
            safeExec({ activeModel?.masquerade() }) { e ->
                if (e !is CancellationException) {
                    updateFlagsOnError(e)
                    logError(e)
                    if (e is IP6TablesError) {
                        if (ip6tablesRejectState != Ternary.NO) {
                            ip6tablesRejectState = try {
                                val ip6tables = iptablesCombinedModel.ip6tables
                                ip6tables.listUIDs().forEach { uid ->
                                    ip6tables.rejectUID(uid)
                                }
                                ip6tables.enable()
                                Ternary.YES
                            } catch (e: Throwable) {
                                logError(e, Log.VERBOSE)
                                Ternary.NO
                            }
                        }
                    }
                    onErrorListener?.invoke(e)
                }
            }
        }
    }

    suspend fun removeUID(uid: Int) {
        scope.launch {
            if (iptablesHalted)
                return@launch
            listOf(suspend { activeModel?.removeUID(uid) },
                suspend { activeModel?.unRejectUID(uid) })
                .forEach { block ->
                    try {
                        block()
                    } catch (e: Throwable) {
                        if (e !is CancellationException) {
                            updateFlagsOnError(e)
                            if (e is IP6TablesError)
                                ip6tablesRejectState = Ternary.UNKNOWN
                            logError(e)
                            onErrorListener?.invoke(e)
                            return@launch
                        }
                    }
                }
        }
    }
}

