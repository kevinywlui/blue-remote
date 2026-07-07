package com.kevinywlui.garageremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kevinywlui.garageremote.ui.MainScreen
import com.kevinywlui.garageremote.ui.MainScreenActions
import com.kevinywlui.garageremote.ui.RemoteSheet
import com.kevinywlui.garageremote.ui.SettingsSheet
import com.kevinywlui.garageremote.ui.theme.GarageTheme
import com.kevinywlui.garageremote.ui.theme.resolveTheme

class MainActivity : ComponentActivity() {

    private val vm: GarageViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                vm.onPermissionsGranted()
            } else {
                // Denied with rationale suppressed = "don't ask again": the
                // request dialog will never show again, so the recovery
                // affordance must deep-link to app settings instead (§4).
                val permanent = grants.filterValues { !it }.keys.any {
                    !shouldShowRequestPermissionRationale(it)
                }
                vm.onPermissionsDenied(permanent)
            }
        }

    // Covers ACTION_REQUEST_ENABLE and the settings deep links (§4 guard).
    private val systemActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.notifySystemActivityResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(vm)

        setContent {
            val theme by vm.theme.collectAsState()
            val isDark = resolveTheme(theme, isSystemInDarkTheme()).isDark
            SideEffect {
                // Both bars follow the ACTIVE theme's darkness, not the
                // system's — they can differ once a fixed theme is chosen.
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
            GarageTheme(theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    @Composable
    private fun Root() {
        // Saveable: follow-system makes uiMode recreation routine; it must
        // not close a sheet. The two sheets are mutually exclusive entry
        // points — gear → Settings (theme), connected chip → This remote.
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var remoteOpen by rememberSaveable { mutableStateOf(false) }
        // First launch goes straight to the permission request; recreations
        // (and later denials) recover via the in-screen affordances instead.
        var permissionsRequested by rememberSaveable { mutableStateOf(false) }

        val actions = remember {
            MainScreenActions(
                requestEnableBluetooth = ::requestEnableBluetooth,
                requestPermissions = { permissionLauncher.launch(GarageViewModel.requiredPermissions()) },
                openAppSettings = ::openAppSettings,
                openLocationSettings = {
                    vm.notifySystemActivityLaunched()
                    systemActivityLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
                openSettingsSheet = { settingsOpen = true },
                openRemoteSheet = { remoteOpen = true },
            )
        }

        LaunchedEffect(Unit) {
            if (!permissionsRequested && !GarageViewModel.hasPermissions(this@MainActivity)) {
                permissionsRequested = true
                permissionLauncher.launch(GarageViewModel.requiredPermissions())
            }
        }
        // Drives the bond-list refresh + poll while the "This remote" sheet
        // (the only phone-list surface) is visible.
        LaunchedEffect(remoteOpen) { vm.onPhonesSheetVisible(remoteOpen) }

        MainScreen(vm, actions)
        if (settingsOpen) {
            val theme by vm.theme.collectAsState()
            SettingsSheet(
                currentTheme = theme,
                onThemeSelected = { vm.setTheme(it) },
                onDismiss = { settingsOpen = false },
            )
        }
        if (remoteOpen) {
            val state by vm.uiState.collectAsState()
            val phonesAvailable by vm.phonesAvailable.collectAsState()
            val pairedDevices by vm.pairedDevices.collectAsState()
            RemoteSheet(
                phonesAvailable = phonesAvailable,
                connected = state is UiState.Ready,
                pairedDevices = pairedDevices,
                onPairNewPhone = {
                    vm.openPairingWindow()
                    // The result snackbar renders in MainScreen's Scaffold,
                    // which the modal sheet would cover.
                    remoteOpen = false
                },
                onUnpair = { vm.unpair(it) },
                onDismiss = { remoteOpen = false },
            )
        }
    }

    private fun requestEnableBluetooth() {
        // Firing ACTION_REQUEST_ENABLE without BLUETOOTH_CONNECT throws on 31+.
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(GarageViewModel.requiredPermissions())
            return
        }
        vm.notifySystemActivityLaunched()
        systemActivityLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun openAppSettings() {
        vm.notifySystemActivityLaunched()
        systemActivityLauncher.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
