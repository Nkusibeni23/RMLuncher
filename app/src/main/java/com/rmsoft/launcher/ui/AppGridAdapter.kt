package com.rmsoft.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rmsoft.launcher.databinding.ItemAppBinding
import com.rmsoft.launcher.model.AppItem

class AppGridAdapter(
    private val apps: List<AppItem>,
    private val onAppClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {

    inner class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppItem) {
            binding.appIcon.setImageDrawable(app.icon)
            binding.appName.text = app.label
            binding.root.setOnClickListener { onAppClick(app) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size
}
