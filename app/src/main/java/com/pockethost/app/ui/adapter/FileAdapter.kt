package com.pockethost.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pockethost.app.databinding.ItemFileBinding
import com.pockethost.core.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileAdapter(
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (File) -> Unit
) : ListAdapter<File, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File) {
            binding.apply {
                fileName.text = file.name

                if (file.isDirectory) {
                    fileIcon.text = "рџ“Ѓ"
                    fileSize.text = "${file.listFiles()?.size ?: 0} items"
                } else {
                    fileIcon.text = "рџ“„"
                    fileSize.text = FileUtils.formatFileSize(file.length())
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                fileDate.text = dateFormat.format(Date(file.lastModified()))

                root.setOnClickListener {
                    onFileClick(file)
                }

                root.setOnLongClickListener {
                    onFileLongClick(file)
                    true
                }
            }
        }
    }

    private class FileDiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.absolutePath == newItem.absolutePath
        }

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.lastModified() == newItem.lastModified() &&
                    oldItem.length() == newItem.length()
        }
    }
}
