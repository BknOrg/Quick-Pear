package com.app.quickpear.session

import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.security.PeerIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class DesktopTransferApproval(
    val peer: PeerIdentity,
    val request: MetadataRequest,
    val responseDeferred: CompletableDeferred<ApprovalDecision>
)

data class DesktopPairingApproval(
    val peer: PeerIdentity,
    val sasCode: String,
    val responseDeferred: CompletableDeferred<Boolean>
)

class DesktopApprovalHandler : ApprovalHandler {

    private val _pendingTransfer = MutableStateFlow<DesktopTransferApproval?>(null)
    val pendingTransfer: StateFlow<DesktopTransferApproval?> = _pendingTransfer.asStateFlow()

    private val _pendingPairing = MutableStateFlow<DesktopPairingApproval?>(null)
    val pendingPairing: StateFlow<DesktopPairingApproval?> = _pendingPairing.asStateFlow()

    override suspend fun onTransferRequest(peer: PeerIdentity, request: MetadataRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        val approval = DesktopTransferApproval(peer, request, deferred)
        _pendingTransfer.value = approval

        val decision = withTimeoutOrNull(TIMEOUT_TRANSFER_MS) {
            deferred.await()
        } ?: ApprovalDecision.REJECT

        _pendingTransfer.value = null
        return decision
    }

    override suspend fun onPairingRequest(peer: PeerIdentity, sasCode: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val approval = DesktopPairingApproval(peer, sasCode, deferred)
        _pendingPairing.value = approval

        val accepted = withTimeoutOrNull(TIMEOUT_PAIRING_MS) {
            deferred.await()
        } ?: false

        _pendingPairing.value = null
        return accepted
    }

    fun submitTransferDecision(decision: ApprovalDecision) {
        _pendingTransfer.value?.responseDeferred?.complete(decision)
    }

    fun submitPairingDecision(accepted: Boolean) {
        _pendingPairing.value?.responseDeferred?.complete(accepted)
    }

    companion object {
        private const val TIMEOUT_TRANSFER_MS = 60_000L
        private const val TIMEOUT_PAIRING_MS = 120_000L
    }
}
