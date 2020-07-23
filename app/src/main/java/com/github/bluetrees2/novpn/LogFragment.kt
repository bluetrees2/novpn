package com.github.bluetrees2.novpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.android.synthetic.main.log_fragment.*

class LogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.log_fragment, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        recyclerView.apply {
            ViewCompat.setNestedScrollingEnabled(this, false)
            layoutManager = LinearLayoutManagerSnapEnd(context)
            adapter = LogRecordsRecyclerViewAdapter()
        }
        arguments?.getInt("scrollToLastPriority")?.let { priority ->
            recyclerView.post {
                scrollToLastPriority(priority)
            }
        }
    }

    fun scrollToLastPriority(priority: Int) {
        context?:return
        val position = Log.records.lastIndexOfBy(priority) { it.priority }
        if (position == -1)
            return
        (recyclerView.layoutManager as? LinearLayoutManager)?.apply {
            val firstVisible = findFirstCompletelyVisibleItemPosition()
            val lastVisible = findLastCompletelyVisibleItemPosition()
            if (position < firstVisible || position > lastVisible) {
                recyclerView.smoothScrollToPosition(position)
            }
        }
    }

    fun copyToClipboard() {
        context?:return
        val clipboardManager = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("log",
            Log.records
                .map { record -> with(record) { "${priorityToChar(priority)}/$tag: $msg" } }
                .joinToString("\n"))
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun clear() {
        Log.records.clear()
        recyclerView?.adapter?.notifyDataSetChanged()
    }

    private fun priorityToChar(priority: Int): Char = when(priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'F'
        else -> ' '
    }
}