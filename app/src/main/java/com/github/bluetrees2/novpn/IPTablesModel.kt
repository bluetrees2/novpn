package com.github.bluetrees2.novpn

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.Exception

class IPTablesError(val result: CommandResult) : Exception(result.toString())
class IP6TablesError(val result: CommandResult) : Exception(result.toString())

interface IPTablesModelI {
    suspend fun listUIDs() : List<Int>
    suspend fun addUID(uid: Int)
    suspend fun rejectUID(uid: Int)
    suspend fun unRejectUID(uid: Int)
    suspend fun removeUID(uid: Int)
    suspend fun clearUIDs()
    suspend fun clearAndAddUIDs(uidList: List<Int>)
    suspend fun clearAndRejectUIDs(uidList: List<Int>)
    suspend fun cleanup()
    suspend fun enable()
    suspend fun disable()
    suspend fun masquerade()
    suspend fun unmasquerade()
}

class IPTablesCombinedModel(val rootShellModel: RootShellModel) : IPTablesModelI {
    val iptables = IPTablesModel(rootShellModel)
    val ip6tables = IP6TablesModel(rootShellModel)

    override suspend fun listUIDs(): List<Int> = throw NotImplementedError()

    override suspend fun addUID(uid: Int) {
        iptables.addUID(uid)
        ip6tables.addUID(uid)
    }

    override suspend fun removeUID(uid: Int) {
        iptables.removeUID(uid)
        ip6tables.removeUID(uid)
    }

    override suspend fun clearUIDs() {
        iptables.clearUIDs()
        ip6tables.clearUIDs()
    }

    override suspend fun rejectUID(uid: Int) {
        iptables.rejectUID(uid)
        ip6tables.rejectUID(uid)
    }

    override suspend fun unRejectUID(uid: Int) {
        iptables.unRejectUID(uid)
        ip6tables.unRejectUID(uid)
    }

    override suspend fun clearAndAddUIDs(uidList: List<Int>) {
        iptables.clearAndAddUIDs(uidList)
        ip6tables.clearAndAddUIDs(uidList)
    }

    override suspend fun clearAndRejectUIDs(uidList: List<Int>) {
        iptables.clearAndRejectUIDs(uidList)
        ip6tables.clearAndRejectUIDs(uidList)
    }

    override suspend fun cleanup() {
        iptables.cleanup()
        ip6tables.cleanup()
    }

    override suspend fun enable() {
        iptables.enable()
        ip6tables.enable()
    }

    override suspend fun disable() {
        iptables.disable()
        ip6tables.disable()
    }

    override suspend fun masquerade() {
        iptables.masquerade()
        ip6tables.masquerade()
    }

    override suspend fun unmasquerade() {
        iptables.unmasquerade()
        ip6tables.unmasquerade()
    }
}

class IP6TablesModel(rootShellModel: RootShellModel) : IPTablesModel(rootShellModel) {
    override val chainMangleOutput = CustomChainIPv6(rootShellModel, "mangle", "OUTPUT", "bt2.novpn_mout")
    override val chainNatPostRouting = CustomChainIPv6(rootShellModel, "nat", "POSTROUTING", "bt2.novpn_npsr")
    override val chainFilterOutput = CustomChainIPv6(rootShellModel, "filter", "OUTPUT", "bt2.novpn_fout")

    override fun onCommandError (result: CommandResult): Nothing =
        throw IP6TablesError(result)
}

open class IPTablesModel(val rootShellModel: RootShellModel) : IPTablesModelI {

    protected open val chainMangleOutput = CustomChain(rootShellModel, "mangle", "OUTPUT", "bt2.novpn_mout")
    protected open val chainNatPostRouting = CustomChain(rootShellModel, "nat", "POSTROUTING", "bt2.novpn_npsr")
    protected open val chainFilterOutput = CustomChain(rootShellModel, "filter", "OUTPUT", "bt2.novpn_fout")

    protected open fun onCommandError (result: CommandResult): Nothing =
        throw IPTablesError(result)

    private fun enforceResult(result: CommandResult) { if (result.returnCode != 0) onCommandError(result) }

    private companion object {
        const val protectFromVPNMark = "0x20000/0x20000"
    }

    private val mutex = Mutex()

    override suspend fun listUIDs() : List<Int> = mutex.withLock {
        val result = chainMangleOutput.listRules()
        if (result.returnCode != 0)
            return emptyList()

        val regex = Regex(
            "--uid-owner\\s*(\\d+)\\s*.*-j\\s*MARK.*$protectFromVPNMark", RegexOption.MULTILINE)
        return regex.findAll(result.stdout)
            .map { m -> m.groupValues[1].toInt() }
            .toList()
    }

    override suspend fun addUID(uid: Int) = mutex.withLock { toggleUID(uid, true) }

    override suspend fun removeUID(uid: Int) = mutex.withLock { toggleUID(uid, false) }

    override suspend fun rejectUID(uid: Int) = mutex.withLock { toggleRejectUID(uid, true) }

    override suspend fun unRejectUID(uid: Int) = mutex.withLock { toggleRejectUID(uid, false) }

    private suspend fun toggleUID(uid: Int, on: Boolean) {
        val chain = chainMangleOutput
        val rule = "-m owner --uid-owner $uid -j MARK --set-mark $protectFromVPNMark"
        toggle(rule, chain, on)
    }

    private suspend fun toggleRejectUID(uid: Int, on: Boolean) {
        val chain = chainFilterOutput
        val rule = "-m owner --uid-owner $uid -j REJECT"
        toggle(rule, chain, on)
    }

    private suspend fun toggle(rule: String, chain: CustomChain, on: Boolean) {
        when (on) {
            true -> do {
                val result = chain.addRule(rule)
                if (result.returnCode != 0) {
                    if (!chain.exists()) {
                        enforceResult(chain.create())
                        continue
                    }
                    if (chain.hasRule(rule))
                        return
                    onCommandError(result)
                }
                break
            } while (true)

            false -> {
                if (chain.hasRule(rule))
                    enforceResult(chain.removeRule(rule))
            }
        }
    }

    override suspend fun clearUIDs() = mutex.withLock {
        listOf(chainMangleOutput, chainFilterOutput).forEach { chain ->
            enforceResult(chain.flush())
        }
    }

    override suspend fun clearAndAddUIDs(uidList: List<Int>) = mutex.withLock {
        if (!chainMangleOutput.exists())
            enforceResult(chainMangleOutput.create())
        else
            enforceResult(chainMangleOutput.flush())
        if (chainFilterOutput.exists())
            enforceResult(chainFilterOutput.flush())

        val rule = "-m owner --uid-owner %d -j MARK --set-mark $protectFromVPNMark"
        uidList.forEach { uid -> enforceResult(chainMangleOutput.addRule(rule.format(uid))) }
    }

    override suspend fun clearAndRejectUIDs(uidList: List<Int>) = mutex.withLock {
        if (!chainFilterOutput.exists())
            enforceResult(chainFilterOutput.create())
        else
            enforceResult(chainFilterOutput.flush())

        val rule = "-m owner --uid-owner %d -j REJECT"
        uidList.forEach { uid -> enforceResult(chainFilterOutput.addRule(rule.format(uid))) }
    }

    override suspend fun cleanup() = mutex.withLock {
        cleanup(chainMangleOutput)
        cleanup(chainFilterOutput)
        cleanup(chainNatPostRouting)
    }

    override suspend fun enable() = mutex.withLock {
        listOf(chainMangleOutput, chainFilterOutput).forEach { chain ->
            if (!chain.exists())
                enforceResult(chain.create())
            if (!chain.isEnabled())
                enforceResult(chain.enable())
        }
    }

    override suspend fun disable() = mutex.withLock {
        listOf(chainMangleOutput, chainFilterOutput).forEach { chain ->
            if (chain.isEnabled())
                enforceResult(chain.disable())
        }
    }

    override suspend fun masquerade() = mutex.withLock {
        val chain = chainNatPostRouting
        if (!chain.exists())
            enforceResult(chain.create())
        if (!chain.isEnabled())
            enforceResult(chain.enable())
        val rule = "-m mark --mark $protectFromVPNMark -j MASQUERADE"
        if (!chain.hasRule(rule))
            enforceResult(chain.addRule(rule))
    }

    override suspend fun unmasquerade() = mutex.withLock {
        cleanup(chainNatPostRouting)
    }

    private suspend fun cleanup(chain: CustomChain) {
        if (chain.exists()) {
            enforceResult(chain.flush())
            if (chain.isEnabled())
                enforceResult(chain.disable())
            enforceResult(chain.delete())
        }
    }
}

class CustomChainIPv6(rootShellModel: RootShellModel, table: String, parent: String,
                              name: String) : CustomChain(rootShellModel, table, parent, name) {
    override val iptables = "ip6tables"
}

open class CustomChain(val rootShellModel: RootShellModel, val table: String,
                               val parent: String, val name: String) {
    protected open val iptables = "iptables"

    suspend fun exists(): Boolean =
        listRules().returnCode == 0

    suspend fun create(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -N $name")

    suspend fun delete(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -X $name")

    suspend fun flush(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -F $name")

    suspend fun isEnabled(): Boolean =
        rootShellModel.runCmd("$iptables -t $table -C $parent -j $name")
            .returnCode == 0

    suspend fun enable(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -A $parent -j $name")

    suspend fun disable(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -D $parent -j $name")

    suspend fun listRules(): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -S $name")

    suspend fun hasRule(rule: String): Boolean =
        rootShellModel.runCmd("$iptables -t $table -C $name $rule")
            .returnCode == 0

    suspend fun addRule(rule: String): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -A $name $rule")

    suspend fun removeRule(rule: String): CommandResult =
        rootShellModel.runCmd("$iptables -t $table -D $name $rule")
}