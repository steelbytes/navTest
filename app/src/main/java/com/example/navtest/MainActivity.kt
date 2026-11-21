package com.example.navtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.navigation.NavigationApi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) checkBatteryOptimisation()
            else Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
        }

    private val batteryOptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppLog.d("ignoringBatteryOptimizations=${isIgnoringBatteryOptimizations()}")
            checkTermsThenStart()
        }

    private fun isIgnoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // deliberate: this test app needs to keep running in the background
    private fun checkBatteryOptimisation() {
        if (isIgnoringBatteryOptimizations()) {
            checkTermsThenStart()
        } else {
            AppLog.d("requesting ignore battery optimizations")
            batteryOptLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Edge-to-edge is enforced at targetSdk 35+, so keep clear of the system bars.
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                // No side margin here so the log list runs edge to edge; buttons get their own.
                v.setPadding(bars.left, bars.top + 48, bars.right, bars.bottom)
                insets
            }
            val buttonParams = {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(48, 0, 48, 0) }
            }
            addView(startButton, buttonParams())
            addView(stopButton, buttonParams())
            addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            // Defaults captured from the first row, then each reduced by 25%.
            // (Captured once because recycled rows already carry the reduced values.)
            private var logTextPx = 0f
            private var padV = 0
            private var minH = 0
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    if (logTextPx == 0f) {
                        logTextPx = textSize * 0.75f
                        padV = (paddingTop * 0.75f).toInt()
                        minH = (minimumHeight * 0.75f).toInt()
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, logTextPx)
                    setPadding(0, padV, 0, padV)
                    // android:minHeight sets both the View and TextView minimums, so reduce both.
                    minimumHeight = minH
                    minHeight = minH
                }
        }
        logView.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppLog.lines.collect { lines ->
                        adapter.clear()
                        adapter.addAll(lines)
                        logView.setSelection(lines.size - 1)
                    }
                }
                NavService.running.collect { running ->
                    startButton.isEnabled = !running
                    stopButton.isEnabled = running
                }
            }
        }

        onStartClicked() // auto start; the service ignores repeat starts while its sequence runs
    }

    private val startButton by lazy {
        Button(this).apply {
            text = "Start nav service"
            setOnClickListener { onStartClicked() }
        }
    }

    private val stopButton by lazy {
        Button(this).apply {
            text = "Stop nav service"
            setOnClickListener { stopService(Intent(context, NavService::class.java)) }
        }
    }

    private val logView by lazy {
        ListView(this).apply { transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL }
    }

    private fun onStartClicked() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) checkBatteryOptimisation()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    // getNavigator() fails with TERMS_NOT_ACCEPTED until the Navigation SDK terms are
    // accepted, and that dialog needs an Activity, so it's done here rather than in the service.
    private fun checkTermsThenStart() {
        val termsAccepted = NavigationApi.areTermsAccepted(application)
        AppLog.d("termsAccepted=$termsAccepted")
        if (termsAccepted) {
            startNavService()
        } else {
            NavigationApi.showTermsAndConditionsDialog(this, "NavTest") { accepted ->
                AppLog.d("terms dialog accepted=$accepted")
                if (accepted) startNavService()
            }
        }
    }

    private fun startNavService() {
        ContextCompat.startForegroundService(this, Intent(this, NavService::class.java))
    }
}
