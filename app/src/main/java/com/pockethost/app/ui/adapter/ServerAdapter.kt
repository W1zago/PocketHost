package com.pockethost.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pockethost.app.databinding.ItemServerBinding
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerStatus
import java.text.SimpleDateFormat
import java.util.*

class ServerAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onServerStart: (Server) -> Unit,
    private val onServerStop: (Server) -> Unit,
    private val onServerRestart: (Server) -> Unit = {},
    private val onServerConsole: (Server) -> Unit = {},
    private val onServerFiles: (Server) -> Unit = {}
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
                serverPort.text = ":${server.port}"

                // Letter avatar, tinted by server type (design: icon box per runtime).
                serverIcon.text = server.name.firstOrNull()?.uppercase() ?: "?"
                serverIcon.setTextColor(
                    when (server.type) {
                        com.pockethost.core.model.ServerType.MINECRAFT ->
                            androidx.core.content.ContextCompat.getColor(root.context, com.pockethost.app.R.color.tertiary)
                        else ->
                            androidx.core.content.ContextCompat.getColor(root.context, com.pockethost.app.R.color.secondary)
                    }
                )

                // Status badge: pill bg + dot + label, driven by REAL server state.
                val ctx = root.context
                val labelFg: Int
                val dotDrawable: android.graphics.drawable.Drawable?
                when (server.status) {
                    ServerStatus.RUNNING -> {
                        serverStatus.text = "Running"
                        labelFg = androidx.core.content.ContextCompat.getColor(ctx, com.pockethost.app.R.color.badge_running_fg)
                        dotDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.pockethost.app.R.drawable.bg_dot_running)
                    }
                    ServerStatus.STARTING -> {
                        serverStatus.text = "Starting"
                        labelFg = androidx.core.content.ContextCompat.getColor(ctx, com.pockethost.app.R.color.badge_warning_fg)
                        dotDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.pockethost.app.R.drawable.bg_dot_warning)
                    }
                    ServerStatus.STOPPING -> {
                        serverStatus.text = "Stopping"
                        labelFg = androidx.core.content.ContextCompat.getColor(ctx, com.pockethost.app.R.color.badge_warning_fg)
                        dotDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.pockethost.app.R.drawable.bg_dot_warning)
                    }
                    ServerStatus.ERROR -> {
                        serverStatus.text = "Error"
                        labelFg = androidx.core.content.ContextCompat.getColor(ctx, com.pockethost.app.R.color.badge_error_fg)
                        dotDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.pockethost.app.R.drawable.bg_dot_error)
                    }
                    else -> {
                        serverStatus.text = "Stopped"
                        labelFg = androidx.core.content.ContextCompat.getColor(ctx, com.pockethost.app.R.color.badge_idle_fg)
                        dotDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.pockethost.app.R.drawable.bg_dot_idle)
                    }
                }
                val pillBgRes = when (server.status) {
                    ServerStatus.RUNNING -> com.pockethost.app.R.drawable.bg_status_running
                    ServerStatus.STARTING, ServerStatus.STOPPING -> com.pockethost.app.R.drawable.bg_status_warning
                    ServerStatus.ERROR -> com.pockethost.app.R.drawable.bg_status_error
                    else -> com.pockethost.app.R.drawable.bg_status_idle
                }
                statusBadge.setBackgroundResource(pillBgRes)
                serverStatus.setTextColor(labelFg)
                statusDot.background = dotDrawable

                // Uptime (real: now - lastStarted while running) + last run.
                val lastStarted = server.lastStarted
                if ((server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) && lastStarted != null) {
                    serverUptime.text = formatUptime(System.currentTimeMillis() - lastStarted)
                } else if (server.status == ServerStatus.STOPPING) {
                    serverUptime.text = "вЂ¦"
                } else {
                    serverUptime.text = "вЂ”"
                }
                if (lastStarted != null) {
                    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    serverLastStarted.text = dateFormat.format(Date(lastStarted))
                } else {
                    serverLastStarted.text = "Never"
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

                btnRestart.setOnClickListener {
                    onServerRestart(server)
                }

                btnConsole.setOnClickListener {
                    onServerConsole(server)
                }

                btnFiles.setOnClickListener {
                    onServerFiles(server)
                }

                // State machine: buttons follow ACTUAL state (pr.md).
                btnStart.isEnabled = server.status == ServerStatus.STOPPED || server.status == ServerStatus.ERROR
                btnStop.isEnabled = server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING
                btnRestart.isEnabled = server.status == ServerStatus.RUNNING
            }
        }

        private fun formatUptime(millis: Long): String {
            if (millis < 0) return "вЂ”"
            val minutes = millis / 60000
            if (minutes < 1) return "just now"
            if (minutes < 60) return "${minutes}m"
            val hours = minutes / 60
            if (hours < 24) return "${hours}h ${minutes % 60}m"
            val days = hours / 24
            return "${days}d ${hours % 24}h"
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
