package com.plaintext.audible

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.plaintext.audible.data.BackupManager
import com.plaintext.audible.data.ReleaseRepository
import com.plaintext.audible.ui.TrackerScreen
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: ReleaseRepository

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or declined */ }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val json = withContext(Dispatchers.IO) { BackupManager.exportJson(repo) }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this@MainActivity, "[ Backup Exported ]", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val raw = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (!raw.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        BackupManager.importJson(repo, raw)
                        repo.syncCatalog(forceRefresh = false)
                    }
                    Toast.makeText(this@MainActivity, "[ Backup Restored ]", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ReleaseRepository(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = repo.getPrefs()
        ReleaseCheckWorker.scheduleDailyCheck(this, prefs.notifyHour, prefs.notifyMinute)

        setContent {
            TrackerScreen(
                repo = repo,
                onExportBackup = { exportLauncher.launch("nocturnetxt-backup.json") },
                onImportBackup = { importLauncher.launch(arrayOf("application/json", "*/*")) }
            )
        }
    }
}
