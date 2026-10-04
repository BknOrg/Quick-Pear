package com.app.quickpear.ui

import androidx.compose.runtime.Composable
import com.app.quickpear.domain.PeerDevice
import okio.Path

@Composable
expect fun rememberFilePickerLauncher(
    onFilesSelected: (peer: PeerDevice, paths: List<Path>) -> Unit
): (peer: PeerDevice) -> Unit
