package com.github.bluetrees2.novpn

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class LogRecordsRecyclerViewAdapter : RecyclerView.Adapter<LogRecordsRecyclerViewAdapter.ViewHolder>() {
    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    init {
        Log.records.observe(LogObserver(this))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val textView = inflater.inflate(R.layout.log_record_view, parent, false) as TextView

        textView.apply {
            fun onFocusChange(hasFocus: Boolean) {
                if (hasFocus) {
                    ellipsize = null
                    maxLines = Int.MAX_VALUE
                    setTextIsSelectable(true)
                } else {
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 2
                    setTextIsSelectable(false)
                }
                isFocusable = true
                isClickable = true
            }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                onFocusChange(hasFocus)
            }
            setOnClickListener  {
                onFocusChange(!hasFocus())
            }
        }
        return ViewHolder(textView)
    }

    override fun getItemCount(): Int = Log.records.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.apply {
            Log.records[position].let { record ->
                text = with(record) { "$tag: $msg" }
                setTextColor(ContextCompat.getColor(context,
                    when(record.priority) {
                        Log.VERBOSE -> R.color.colorLogVerbose
                        Log.DEBUG -> R.color.colorLogDebug
                        Log.INFO -> R.color.colorLogInfo
                        Log.WARN -> R.color.colorLogWarn
                        Log.ERROR -> R.color.colorLogError
                        Log.ASSERT -> R.color.colorLogFatal
                        else -> R.color.colorLogDefault
                }))
            }
        }
    }

    private class LogObserver(adapter: LogRecordsRecyclerViewAdapter) : RingBuffer.MutationObserver<LogRecord> {
        private val adapterRef = WeakReference(adapter)
        override fun onChange(mutations: List<RingBuffer.Mutation<LogRecord>>): Boolean {
            GlobalScope.launch(Dispatchers.Main) {
                adapterRef.get()?.apply {
                    notifyDataSetChanged()
                }
            }
            return adapterRef.get() != null
        }
    }
}