package com.github.bluetrees2.novpn

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigator


@Navigator.Name("StatefulFragment")
class StatefulFragmentNavigator(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    containerId: Int) : FragmentNavigator(context, fragmentManager, containerId) {

    private val states = mutableMapOf<String, Fragment.SavedState?>()

    init {
        fragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                val fragment = super.instantiate(classLoader, className)
                fragment.retainInstance = false
                states[className]?.let { state ->
                    fragment.setInitialSavedState(state)
                    states.remove(className)
                }
                return fragment
            }
        }
        fragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (savedInstanceState == null) {
                    val className = f.javaClass.name
                    states[className]?.let { state ->
                        fun Fragment.withNullFragmentManager(block: Fragment.() -> Unit) {
                            Fragment::class.java.getDeclaredField("mFragmentManager").let { field ->
                                field.isAccessible = true
                                val oldVal = field.get(this)
                                field.set(this, null)
                                try { block() }
                                finally { field.set(this, oldVal) }
                            }
                        }
                        // Hacky way of overriding savedInstanceState
                        f.withNullFragmentManager {
                            setInitialSavedState(state)
                            states.remove(className)
                        }
                    }
                }
            }
            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                val state = fragmentManager.saveFragmentInstanceState(f)
                states[f.javaClass.name] = state
            }
        }, false)
    }

    override fun navigate(destination: Destination, args: Bundle?, navOptions: NavOptions?,
                          navigatorExtras: Navigator.Extras?): NavDestination? {
        val className = destination.className.let { className ->
            val prefix = if (className[0] == '.') context.packageName else ""
            prefix + className
        }
        fragmentManager.primaryNavigationFragment?.let {
            if (it.javaClass.name == className && fragmentManager.backStackEntryCount == 0) {
                return null
            }
        }
        return super.navigate(destination, args, navOptions, navigatorExtras)
    }
}