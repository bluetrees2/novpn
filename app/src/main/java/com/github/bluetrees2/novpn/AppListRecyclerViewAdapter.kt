package com.github.bluetrees2.novpn

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.bluetrees2.novpn.databinding.AppInfoViewBinding

class AppListRecyclerViewAdapter(private val apps: List<AppInfoModel>, private val onClickListener: AppInfoModel.OnClickListener) :
    RecyclerView.Adapter<AppListRecyclerViewAdapter.ViewHolder>() {

    class ViewHolder(val appInfoViewBinding: AppInfoViewBinding) : RecyclerView.ViewHolder(appInfoViewBinding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val appInfoViewBinding = AppInfoViewBinding.inflate(inflater, parent, false)
        appInfoViewBinding.clickListener = onClickListener
        return ViewHolder(appInfoViewBinding)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.appInfoViewBinding.model = apps[position]
    }
}