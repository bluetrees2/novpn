package com.github.bluetrees2.novpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.main_fragment.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.collections.ArrayList
import kotlin.math.ceil


class MainFragment : Fragment() {

    private val appListsController = AppListsController()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        appListsController.onActivityCreated(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        appListsController.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        appListsController.onStop()
    }

    fun selectAll() {
        appListsController.selectAll()
    }

    fun clearSelection() {
        appListsController.clearSelection()
    }

    private inner class AppListsController {
        private lateinit var selectionDB: SelectedAppsDatabase

        private lateinit var apps: CopyOnWriteArrayList<AppInfoModel>
        private lateinit var selectedApps: CopyOnWriteArrayList<AppInfoModel>

        private lateinit var mainActivityRef: WeakReference<MainActivity?>
        private val mainActivity: MainActivity? get() = mainActivityRef.get()

        private lateinit var iptablesModel: IPTablesModelAsync

        private var appInfoReceiver: BroadcastReceiver? = null

        private var isReady = false

        private val statusAction: StatusAction?
            get() = mainActivity?.statusAction

        fun onActivityCreated(savedInstanceState: Bundle?) {
            mainActivityRef = WeakReference(activity as? MainActivity)
            iptablesModel = IPTablesModelAsync(context).apply { onErrorListener = { e -> showStatus(e) } }
            when(resources.configuration.orientation) {
                ORIENTATION_LANDSCAPE -> {
                    mainLayout.apply {
                        orientation = LinearLayout.HORIZONTAL
                        children.forEach { child ->
                            with(child.layoutParams as LinearLayout.LayoutParams) {
                                width = 0
                                weight = 1F
                                height = MATCH_PARENT
                            }
                        }
                    }
                }
                else -> {}
            }

            val availableWidth = when(resources.configuration.orientation) {
                ORIENTATION_LANDSCAPE -> displayWidth / 2
                else -> displayWidth
            }
            listOf(recyclerViewSelected, recyclerViewInstalled)
                .forEach { it.apply {
                    setHasFixedSize(true)
                    layoutManager = GridLayoutManager(context, ceil(availableWidth.toFloat() / columnWidth).toInt())
                        .apply { isMeasurementCacheEnabled = false }
                    ViewCompat.setNestedScrollingEnabled(this, false)
                }}

            selectedApps = CopyOnWriteArrayList()
            recyclerViewSelected.adapter = AppListRecyclerViewAdapter(selectedApps,
                object : AppInfoModel.OnClickListener {
                    override fun onClick(m: AppInfoModel) = onDeselect(m)
                })

            GlobalScope.launch {
                val context = mainActivity?:return@launch
                val packageManager = context.applicationContext.packageManager
                apps = getInstalledApps(packageManager)
                    .sortedBy { it.sortKey }
                    .let { CopyOnWriteArrayList(it) }
                withContext(Dispatchers.Main) {
                    recyclerViewInstalled?.adapter = AppListRecyclerViewAdapter(apps,
                        object : AppInfoModel.OnClickListener {
                            override fun onClick(m: AppInfoModel) = onSelect(m)
                        })
                }

                appInfoReceiver = AppInfoBroadcastReceiver(packageManager)
                context.registerReceiver(appInfoReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                })

                selectionDB = SelectedAppsDatabase.newInstance(context)

                val uidInvalid = -1
                suspend fun restoreSelection(list: List<AppInfoModelLight>, initIPTables: Boolean) {
                    if (initIPTables) iptablesModel.cleanup()
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            updateViewsPreSelectionChange()
                            list.forEach { appInfoLight ->
                                val i = apps.indexOfBy(appInfoLight.name) { it.name }
                                if (i == -1) {
                                    if (appInfoLight.uid != uidInvalid)
                                        iptablesModel.removeUID(appInfoLight.uid)
                                    selectionDB.dao().delete(appInfoLight.name)
                                } else {
                                    val appInfo = apps[i]
                                    apps.removeAt(i)
                                    selectedApps.addSortedBy(
                                        appInfo.sortKey,
                                        appInfo
                                    ) { it.sortKey }
                                }
                            }
                            recyclerViewInstalled?.adapter?.notifyDataSetChanged()
                            recyclerViewSelected?.adapter?.notifyDataSetChanged()
                            updateViewsPostSelectionChange()
                            selectedApps
                        }.let {
                            if (initIPTables && it.isNotEmpty()) {
                                iptablesModel.apply {
                                    iptablesClearAndAddUIDs(it.map { a -> a.uid })
                                }
                            }
                        }
                    }
                }

                val (selection, initIPTables) = with(null) {
                    savedInstanceState?.apply {
                        if (getBoolean("is_ready")) {
                            getParcelableArrayList<AppInfoModelLight>("selected_apps")?.let {
                                return@with it to false
                            }
                        }
                    }
                    showStatusPending()
                    selectionDB.dao().getAll().let { rows ->
                        rows.map { AppInfoModelLight(it.name, uidInvalid) }
                    } to true
                }
                restoreSelection(selection, initIPTables)
                if (initIPTables)
                    iptablesModel.onCompletionListener = {
                        checkStatus { showStatusReady() }
                    }
                isReady = true
            }
        }

        fun onStop() {
            try {
                appInfoReceiver?.let { context?.unregisterReceiver(it) }
            } catch (_: IllegalArgumentException) {
            }
        }

        fun onSaveInstanceState(outState: Bundle) {
            outState.putBoolean("is_ready", isReady)
            try {
                outState.putParcelableArrayList(
                    "selected_apps",
                    ArrayList(selectedApps.map { AppInfoModelLight(it.name, it.uid) })
                )
            } catch (_: UninitializedPropertyAccessException) {}
        }

        fun selectAll() {
            if (apps.isEmpty())
                return
            if (selectedApps.isEmpty())
                selectedApps.addAll(apps)
            else
                apps.forEach { a -> selectedApps.addSortedBy(a.sortKey, a) { it.sortKey } }
            apps.clear()
            recyclerViewInstalled?.adapter?.notifyDataSetChanged()
            recyclerViewSelected?.adapter?.notifyDataSetChanged()
            selectedApps.let { list ->
                GlobalScope.launch {
                    showStatusPending()
                    iptablesModel.apply {
                        cleanup()
                        iptablesClearAndAddUIDs(list.map { it.uid })
                    }
                    selectionDB.dao().insertAll(
                        list.map { it.name }
                    )
                }
            }
        }

        fun clearSelection() {
            if (selectedApps.isEmpty())
                return
            if (apps.isEmpty())
                apps.addAll(selectedApps)
            else
                selectedApps.forEach { a -> apps.addSortedBy(a.sortKey, a) { it.sortKey } }
            selectedApps.clear()
            recyclerViewSelected?.adapter?.notifyDataSetChanged()
            recyclerViewInstalled?.adapter?.notifyDataSetChanged()
            apps.let { list ->
                GlobalScope.launch {
                    showStatusPending()
                    iptablesModel.apply {
                        cleanup()
                        onCompletionListener = { checkStatus() }
                    }
                    selectionDB.dao().deleteAll(
                        list.map { it.name }
                    )
                }
            }
        }

        private val AppInfoModel.sortKey
            get() = label.toLowerCase(Locale.getDefault())

        private val displayWidth: Int
            get() {
                val metrics = DisplayMetrics()
                val windowManager: WindowManager = context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(metrics)
                return metrics.widthPixels
            }

        private val columnWidth: Int
            get() = context!!.resources.getDimensionPixelSize(R.dimen.app_info_view_width)


        private suspend fun iptablesClearAndAddUIDs(uidList: List<Int>) {
            iptablesModel.apply {
                clearAndAddUIDs(uidList)
                enable()
                masquerade()
                onCompletionListener = { checkStatus() }
            }
        }

        private suspend fun iptablesAddUID(uid: Int) {
            iptablesModel.apply {
                addUID(uid)
                enable()
                masquerade()
                onCompletionListener = { checkStatus() }
            }
        }

        private suspend fun clearStatus() = withContext(Dispatchers.Main) {
            statusAction?.let { it.status = StatusAction.Status.PENDING }
        }

        private suspend fun checkStatus(block: suspend () -> Unit = { showStatus() }) = withContext(Dispatchers.Main) {
            statusAction?.status?.let { status ->
                if (status <= StatusAction.Status.SUCCESS)
                    block()
            }
        }

        private suspend fun showStatus(error: Throwable? = null, successMessage: String? = null) = withContext(Dispatchers.Main) {
            val context = mainActivity?:return@withContext
            val status = when(error) {
                null -> {
                    when {
                        !iptablesModel.iptablesOK -> StatusAction.Status.ERROR
                        !iptablesModel.ip6tablesOK -> StatusAction.Status.WARNING
                        else -> StatusAction.Status.SUCCESS
                    }
                }
                is IP6TablesError -> StatusAction.Status.WARNING
                else -> StatusAction.Status.ERROR
            }
            if (status <= StatusAction.Status.WARNING)
                Log.v("Main", context.resources
                    .getQuantityString(R.plurals.log_apps_bypassing_vpn, selectedApps.size)
                    .format(selectedApps.size))
            if (status == StatusAction.Status.WARNING) {
                when(iptablesModel.ip6tablesRejectOK) {
                    Ternary.YES -> Log.v("Main", context.getString(R.string.log_ip6tables_reject))
                    Ternary.NO -> Log.v("Main", context.getString(R.string.log_ip6tables_leak))
                    else -> {}
                }
            }
            if (status == StatusAction.Status.SUCCESS)
                Log.i("Main", context.getString(R.string.log_success))

            if (statusAction == null)
                return@withContext
            val builder = StringBuilder()
            val lineSep = context.getString(R.string.line_separator)
            when {
                error is RootError -> builder.append(context.getString(R.string.root_error))
                error is TimeoutException -> builder.append(context.getString(R.string.timeout_error))
                error is IPTablesError || !iptablesModel.iptablesOK -> {
                    builder.append(
                        context.getString(R.string.iptables_error),
                        lineSep, lineSep,
                        context.getString(R.string.see_log)
                    )
                }
                error is IP6TablesError || !iptablesModel.ip6tablesOK -> {
                    builder.append(
                        context.getString(R.string.iptables_success),
                        lineSep,
                        context.resources
                            .getQuantityString(R.plurals.n_apps_bypassing_vpn, selectedApps.size)
                            .format(selectedApps.size),

                        lineSep, lineSep,

                        context.getString(R.string.ip6tables_error)
                    )
                    when (iptablesModel.ip6tablesRejectOK) {
                        Ternary.YES -> builder.append(
                            lineSep,
                            context.getString(R.string.ipv6_rejected)
                        )
                        Ternary.NO -> builder.append(
                            lineSep,
                            context.getString(R.string.ipv6_leaking)
                        )
                        else -> {}
                    }
                    builder.append(
                        lineSep, lineSep,
                        context.getString(R.string.see_log)
                    )
                }
                status == StatusAction.Status.SUCCESS -> {
                    if (successMessage != null)
                        builder.append(successMessage)
                    else
                        builder.append(context.getString(R.string.message_success),
                            lineSep,
                            context.resources
                                .getQuantityString(R.plurals.n_apps_bypassing_vpn, selectedApps.size)
                                .format(selectedApps.size)
                            )
                }
                else -> builder.append(context.getString(R.string.unknown_error))
            }
            val message = builder.toString()
            val urlHandler = { url: String ->
                when (url) {
                    "log" -> {
                        val priority = when(statusAction?.status) {
                            StatusAction.Status.WARNING -> Log.WARN
                            StatusAction.Status.ERROR -> Log.ERROR
                            else -> -1
                        }
                        mainActivity?.currentFragment.let { fragment ->
                            when(fragment) {
                                is LogFragment -> {
                                    if (priority != -1)
                                        fragment.scrollToLastPriority(priority)
                                    else null
                                }
                                else -> {
                                    mainActivity?.findNavController(R.id.navHostFragment)
                                        ?.navigate(
                                            R.id.navLog,
                                            if (priority != -1) bundleOf("scrollToLastPriority" to priority)
                                            else null
                                        )
                                }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            val popupContent = when(status) {
                StatusAction.Status.SUCCESS -> StatusAction.PopupContent(message, urlHandler)
                else -> {
                    StatusAction.PopupContent(message,
                        urlHandler,
                        context.getString(R.string.retry)
                    ) {
                        selectedApps.let { list ->
                            GlobalScope.launch {
                                showStatusPending()
                                iptablesModel.apply {
                                    cleanup()
                                    iptablesClearAndAddUIDs(list.map { it.uid })
                                }
                            }
                        }
                    }
                }
            }
            statusAction!!.showAction(status, popupContent)
        }

        private suspend fun showStatusReady() = withContext(Dispatchers.Main) {
            val context = mainActivity?:return@withContext
            val message =
                if (selectedApps.isNotEmpty()) null
                else context.getString(R.string.message_select_an_app)
            showStatus(null, message)
        }

        private suspend fun showStatusPending() = withContext(Dispatchers.Main) {
            val context = mainActivity?:return@withContext
            Log.v("Main", context.getString(R.string.log_pending))
            statusAction?.showAction(
                StatusAction.Status.PENDING,
                StatusAction.PopupContent(context.getString(R.string.message_pending))
            )
        }

        private suspend fun showStatusPendingIfNoError() = withContext(Dispatchers.Main) {
            statusAction?.status?.let { status ->
                if (status < StatusAction.Status.ERROR)
                    showStatusPending()
            }
        }

        private fun onSelect(appInfo: AppInfoModel) {
            GlobalScope.launch {
                showStatusPendingIfNoError()
                updateViewsPreSelectionChange()
                selectAppsBy(appInfo.uid) { it.uid }
                recyclerViewSelected?.scrollTo(appInfo)
                onPostSelect(appInfo.uid)
            }
        }

        private fun onDeselect(appInfo: AppInfoModel) {
            GlobalScope.launch {
                showStatusPendingIfNoError()
                updateViewsPreSelectionChange()
                deselectAppsBy (appInfo.uid) { it.uid }
                recyclerViewInstalled?.scrollTo(appInfo)
                onPostDeselect(appInfo.uid)
            }
        }

        private suspend fun onPostSelect(uid: Int) {
            updateViewsPostSelectionChange()
            iptablesAddUID(uid)
            selectionDB.dao().insertAll(
                selectedApps
                    .filter { it.uid == uid }
                    .map { it.name }
            )
        }

        private suspend fun onPostDeselect(uid: Int) {
            updateViewsPostSelectionChange()
            iptablesModel.apply {
                if (selectedApps.isNotEmpty())
                    removeUID(uid)
                else {
                    clearStatus()
                    cleanup()
                }
                onCompletionListener = { checkStatus() }
            }
            selectionDB.dao().deleteAll(
                apps
                    .filter { it.uid == uid }
                    .map { it.name }
            )
        }

        private suspend fun updateViewsPreSelectionChange() = withContext(Dispatchers.Main) {
            recyclerViewSelected?.requestLayout()
        }

        private suspend fun updateViewsPostSelectionChange() = withContext(Dispatchers.Main) {
        }

        private suspend fun RecyclerView.scrollTo(appInfo: AppInfoModel) = withContext(Dispatchers.Main) {
            val list = when (this@scrollTo) {
                recyclerViewInstalled -> apps
                recyclerViewSelected -> selectedApps
                else -> return@withContext
            }
            val i = list.indexOf(appInfo)
            if (i != -1)
                scrollToPosition(i)
        }

        private suspend inline fun <T : Comparable<T>> selectAppsBy(key: T, noinline selector: (AppInfoModel) -> T?): Int {
            return toggleAppsBy(key, true, selector)
        }

        private suspend inline fun <T : Comparable<T>> deselectAppsBy(key: T, noinline selector: (AppInfoModel) -> T?): Int {
            return toggleAppsBy(key, false, selector)
        }

        private suspend fun <T : Comparable<T>> toggleAppsBy(key: T, on :Boolean, selector: (AppInfoModel) -> T?): Int
                = withContext(Dispatchers.Main) {
            val srcList = if (!on) selectedApps else apps
            val dstList = if (on) selectedApps else apps
            val srcAdapter = if (!on) recyclerViewSelected?.adapter else recyclerViewInstalled?.adapter
            val dstView = if (on) recyclerViewSelected else recyclerViewInstalled
            val dstAdapter = dstView?.adapter
            var count = 0
            var i = 0
            while (i < srcList.size) {
                val m = srcList[i]
                if (selector(m) == key) {
                    ++count
                    srcList.removeAt(i)
                    srcAdapter?.notifyItemRemoved(i)
                    val j = dstList.addSortedBy(m.sortKey, m) { it.sortKey }
                    dstAdapter?.notifyItemInserted(j)
                    continue
                }
                ++i
            }
            count
        }

        private inner class AppInfoBroadcastReceiver(val packageManager: PackageManager) : BroadcastReceiver() {
            override fun onReceive(p0: Context?, intent: Intent?) {
                when(intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val uri : Uri? = intent.data
                        if (uri != null) {
                            val name : String = uri.schemeSpecificPart
                            val pkgInfo : PackageInfo = packageManager.getPackageInfo(name, 0)
                            GlobalScope.launch (Dispatchers.Main) {
                                val appInfo = AppInfoModel(
                                    pkgInfo.applicationInfo.loadLabel(packageManager).toString(),
                                    pkgInfo.packageName,
                                    pkgInfo.applicationInfo.loadIcon(packageManager),
                                    pkgInfo.applicationInfo.uid)
                                val i = apps.addSortedBy(appInfo.sortKey, appInfo) { it.sortKey }
                                recyclerViewInstalled?.adapter?.notifyItemInserted(i)
                            }
                        }
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val uri : Uri? = intent.data
                        if (uri != null) {
                            val name : String = uri.schemeSpecificPart
                            GlobalScope.launch {
                                suspend fun findAndRemove(list: CopyOnWriteArrayList<AppInfoModel>,
                                                          adapter: RecyclerView.Adapter<*>?,
                                                          postRemoveAction: (suspend (Int) -> Unit)? = null) {
                                    withContext(Dispatchers.Main) {
                                        val uidList = ArrayList<Int>()
                                        var i = 0
                                        while (i < list.size) {
                                            val m = list[i]
                                            if (m.name != name) {
                                                ++i
                                                continue
                                            }
                                            list.removeAt(i)
                                            adapter?.notifyItemRemoved(i)
                                            if (uidList.indexOf(m.uid) == -1)
                                                uidList.add(m.uid)
                                        }
                                        uidList
                                    }.let { uidList ->
                                        if (postRemoveAction != null)
                                            uidList.forEach { postRemoveAction(it) }
                                    }
                                }
                                findAndRemove(apps, recyclerViewInstalled?.adapter)
                                findAndRemove(selectedApps, recyclerViewSelected?.adapter, ::onPostDeselect)
                            }
                        }
                    }
                }
            }
        }
    }
}
