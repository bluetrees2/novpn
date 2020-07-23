package com.github.bluetrees2.novpn

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import kotlinx.android.synthetic.main.main_action_bar.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.main_frame.*


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration

    private val statusActionImpl = StatusActionImpl()
    private var showStatusMenuOption = false

    internal val statusAction: StatusAction
        get() = statusActionImpl

    internal val currentFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            ?.childFragmentManager?.primaryNavigationFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopBootUpService()
        setContentView(R.layout.main_activity)
        setSupportActionBar(toolbar)

        val navController = findNavController(R.id.navHostFragment)
        val navigator = StatefulFragmentNavigator(
            this, navHostFragment.childFragmentManager, R.id.navHostFragment)
        navController.navigatorProvider.addNavigator(navigator)
        navController.setGraph(R.navigation.main_nav_graph)
        appBarConfiguration = AppBarConfiguration(navController.graph.map { it.id }.toSet(), drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_options, menu)
        menu.findItem(R.id.action_status).let { menuItem ->
            menuItem.isVisible = showStatusMenuOption
            statusActionImpl.menuItem = menuItem
            if (!showStatusMenuOption)
                return@let
            val (titleRes, imageRes) = when(statusActionImpl.status) {
                StatusAction.Status.PENDING -> R.string.action_pending to -1
                StatusAction.Status.SUCCESS -> R.string.action_success to R.drawable.ic_success
                StatusAction.Status.ERROR -> R.string.action_error to R.drawable.ic_error
                StatusAction.Status.WARNING -> R.string.action_warning to R.drawable.ic_warning
            }
            menuItem.apply {
                title = getString(titleRes)
                when(statusActionImpl.status) {
                    StatusAction.Status.PENDING -> {
                        setActionView(R.layout.progress_action_menu_item)
                    }
                    else -> {
                        setActionView(R.layout.status_action_menu_item)
                        (actionView as ImageButton).apply {
                            setImageDrawable(ContextCompat.getDrawable(context, imageRes))
                            val animation = AnimationUtils.loadAnimation(context, R.anim.pop)
                            startAnimation(animation)
                            setOnClickListener { onOptionsItemSelected(menuItem) }
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        currentFragment.let { fragment ->
            (fragment is MainFragment).let {
                menu.findItem(R.id.action_select_all).isVisible = it
                menu.findItem(R.id.action_clear_selection).isVisible = it
            }
            (fragment is LogFragment).let {
                menu.findItem(R.id.action_copy).isVisible = it
                menu.findItem(R.id.action_clear_log).isVisible = it
            }
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.navHostFragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.action_status -> {
                statusActionImpl.showPopup()
                true
            }
            R.id.action_select_all, R.id.action_clear_selection -> {
                (currentFragment as? MainFragment)
                    ?.let { fragment ->
                        when (item.itemId) {
                            R.id.action_select_all -> fragment.selectAll()
                            R.id.action_clear_selection -> fragment.clearSelection()
                    }
                }
                true
            }
            R.id.action_copy, R.id.action_clear_log -> {
                (currentFragment as? LogFragment)?.let { fragment ->
                    when(item.itemId) {
                        R.id.action_copy -> fragment.copyToClipboard()
                        R.id.action_clear_log -> fragment.clear()
                    }
                }
                true
            }
            R.id.action_about -> {
                @SuppressLint("InflateParams")
                val view = layoutInflater.inflate(R.layout.about_popup, null)
                view.findViewById<TextView>(R.id.text).apply {
                    text = HtmlCompat.fromHtml(getString(R.string.app_about), HtmlCompat.FROM_HTML_MODE_COMPACT)
                }
                AlertDialog.Builder(this).run {
                    setView(view)
                    create()
                }.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private inner class StatusActionImpl : StatusAction {
        override var status = StatusAction.Status.SUCCESS
        var popupContent = StatusAction.PopupContent("")

        lateinit var menuItem: MenuItem

        override fun showAction(status: StatusAction.Status, popupContent: StatusAction.PopupContent) {
            this.status = status
            this.popupContent = popupContent
            showStatusMenuOption = true
            invalidateOptionsMenu()
        }

        override fun hideAction() {
            showStatusMenuOption = false
            invalidateOptionsMenu()
        }

        override fun showPopup() {
            @SuppressLint("InflateParams")
            val view = layoutInflater.inflate(R.layout.status_popup, null)
            val popup = PopupWindow(
                view,
                resources.getDimensionPixelSize(R.dimen.status_popup_width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            view.findViewById<LinearLayout>(R.id.popupContent).background.colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    ContextCompat.getColor(this@MainActivity, R.color.colorPopupBackground),
                    BlendModeCompat.SRC_ATOP
                )

            view.findViewById<TextView>(R.id.popupText).apply {
                text = HtmlCompat.fromHtml(popupContent.messageHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
                movementMethod = LinkMovementMethod.getInstance()
                urls.forEach {
                    val spannable = text as Spannable
                    val params = arrayOf(spannable.getSpanStart(it), spannable.getSpanEnd(it),
                                        spannable.getSpanFlags(it))
                    spannable.removeSpan(it)
                    spannable.setSpan(object : URLSpan(it.url) {
                        override fun onClick(widget: View) {
                            if (popupContent.urlOnClick?.invoke(url) == true)
                                popup.dismiss()
                        }
                    }, params[0], params[1], params[2])
                }
            }
            view.findViewById<Button>(R.id.popupButton).apply {
                if (popupContent.actionOnClick == null) {
                    visibility = View.GONE
                } else {
                    visibility = View.VISIBLE
                    text = popupContent.actionName
                    setOnClickListener {
                        popupContent.actionOnClick!!.invoke()
                        popup.dismiss()
                    }
                }
            }

            popup.showAsDropDown(findViewById(menuItem.itemId), 0, 0, Gravity.BOTTOM)
        }
    }
}

interface StatusAction {
    enum class Status {
        PENDING,
        SUCCESS,
        WARNING,
        ERROR,
    }

    var status: Status

    data class PopupContent(val messageHtml: String, val urlOnClick: ((url: String) -> Boolean)? = null,
                            val actionName: String = "", val actionOnClick: (() -> Unit)? = null)

    fun showAction(status: Status, popupContent: PopupContent)
    fun hideAction()
    fun showPopup()
}
