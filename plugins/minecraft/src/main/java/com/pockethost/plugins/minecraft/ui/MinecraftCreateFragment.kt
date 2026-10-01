package com.pockethost.plugins.minecraft.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pockethost.plugins.minecraft.MinecraftServerManager
import com.pockethost.plugins.minecraft.MinecraftServerSoftware
import com.pockethost.plugins.minecraft.databinding.FragmentMinecraftCreateBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MinecraftCreateFragment : Fragment() {

    private var _binding: FragmentMinecraftCreateBinding? = null
    private val binding get() = _binding!!

    private lateinit var minecraftManager: MinecraftServerManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMinecraftCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // H4: getExternalFilesDir РјРѕР¶Рµ Р±СѓС‚Рё null (РЅРµРјР° СЃС…РѕРІРёС‰Р°) вЂ” fallback РЅР° filesDir Р· Toast.
        val extRoot = requireContext().getExternalFilesDir(null)?.absolutePath
        val baseRoot = extRoot ?: run {
            try {
                android.widget.Toast.makeText(requireContext(), "Р—РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ вЂ” РІРёРєРѕСЂРёСЃС‚РѕРІСѓСЋ РІРЅСѓС‚СЂС–С€РЅС”", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            requireContext().filesDir.absolutePath
        }
        val baseDir = "$baseRoot/servers"
        minecraftManager = MinecraftServerManager(baseDir)

        setupViews()
    }

    private fun setupViews() {
        binding.btnCreate.setOnClickListener {
            createMinecraftServer()
        }

        val softwareOptions = MinecraftServerSoftware.entries.map { it.name }
        binding.spinnerSoftware.adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            softwareOptions
        )
    }

    private fun createMinecraftServer() {
        val name = binding.editServerName.text.toString()
        val version = binding.editVersion.text.toString()
        val port = binding.editPort.text.toString().toIntOrNull() ?: 25565
        val softwareIndex = binding.spinnerSoftware.selectedItemPosition
        val software = MinecraftServerSoftware.entries[softwareIndex]

        if (name.isEmpty()) {
            Snackbar.make(binding.root, "Server name is required", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (version.isEmpty()) {
            Snackbar.make(binding.root, "Version is required", Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCreate.isEnabled = false

        lifecycleScope.launch {
            val server = minecraftManager.createMinecraftServer(name, version, software, port)

            // H8: РїС–СЃР»СЏ РјРµСЂРµР¶РµРІРѕРіРѕ await С„СЂР°РіРјРµРЅС‚ РјС–Рі РІС–Рґ'С”РґРЅР°С‚РёСЃСЏ вЂ” РЅРµ С‡С–РїР°С”РјРѕ binding.
            if (!isAdded || _binding == null) return@launch
            binding.progressBar.visibility = View.GONE
            binding.btnCreate.isEnabled = true

            if (server != null) {
                Snackbar.make(binding.root, "Server created successfully", Snackbar.LENGTH_SHORT).show()
                try { requireActivity().finish() } catch (_: Exception) {}
            } else {
                Snackbar.make(binding.root, "Failed to create server", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
