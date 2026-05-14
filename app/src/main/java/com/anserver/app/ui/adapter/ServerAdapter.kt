package com.anserver.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anserver.app.databinding.ItemServerBinding
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import java.text.SimpleDateFormat
import java.util.*

class ServerAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onServerStart: (Server) -> Unit,
    private val onServerStop: (Server) -> Unit
) : ListAdapter<Server, ServerAdapter.ServerViewHolder>(ServerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ServerViewHolder(
        private val binding: ItemServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(server: Server) {
            binding.apply {
                serverName.text = server.name
                serverType.text = server.type.name
                serverPort.text = "Port: ${server.port}"
                serverStatus.text = server.status.name

                val statusColor = when (server.status) {
                    ServerStatus.RUNNING -> android.graphics.Color.GREEN
                    ServerStatus.STOPPED -> android.graphics.Color.GRAY
                    ServerStatus.STARTING -> android.graphics.Color.YELLOW
                    ServerStatus.STOPPING -> android.graphics.Color.YELLOW
                    ServerStatus.ERROR -> android.graphics.Color.RED
                }
                serverStatus.setTextColor(statusColor)

                val lastStarted = server.lastStarted
                if (lastStarted != null) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    serverLastStarted.text = "Last started: ${dateFormat.format(Date(lastStarted))}"
                } else {
                    serverLastStarted.text = "Never started"
                }

                root.setOnClickListener {
                    onServerClick(server)
                }

                btnStart.setOnClickListener {
                    onServerStart(server)
                }

                btnStop.setOnClickListener {
                    onServerStop(server)
                }

                btnStart.isEnabled = server.status == ServerStatus.STOPPED
                btnStop.isEnabled = server.status == ServerStatus.RUNNING
            }
        }
    }

    private class ServerDiffCallback : DiffUtil.ItemCallback<Server>() {
        override fun areItemsTheSame(oldItem: Server, newItem: Server): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Server, newItem: Server): Boolean {
            return oldItem == newItem
        }
    }
}
