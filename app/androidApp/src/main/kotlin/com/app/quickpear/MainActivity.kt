package com.app.quickpear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.service.TransferForegroundService
import com.app.quickpear.ui.TransferViewModel
import com.app.quickpear.util.PermissionHelper
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Ensure background service is running
        TransferForegroundService.startService(this)

        if (!PermissionHelper.hasAllPermissions(this)) {
            requestPermissionsLauncher.launch(PermissionHelper.getRequiredPermissions().toTypedArray())
        }

        setContent {
            val viewModel = remember {
                TransferViewModel(TransferForegroundService.activeNode)
            }

            LaunchedEffect(Unit) {
                while (TransferForegroundService.activeNode == null) {
                    delay(100)
                }
                TransferForegroundService.activeNode?.let { node ->
                    viewModel.attachNode(node)
                    node.setMode(DiscoveryMode.ACTIVE)
                }
            }

            App(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        TransferForegroundService.activeNode?.setMode(DiscoveryMode.ACTIVE)
    }

    override fun onPause() {
        super.onPause()
        TransferForegroundService.activeNode?.setMode(DiscoveryMode.BACKGROUND)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
