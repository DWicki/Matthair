package io.github.dwicki.matthair.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.commission.CheckId
import io.github.dwicki.matthair.commission.FailureKind
import io.github.dwicki.matthair.commission.Phase
import io.github.dwicki.matthair.matter.CommissioningFlow
import io.github.dwicki.matthair.matter.PayloadError

/*
 * Mapping of domain enums to string resources and icons. Kept as exhaustive `when`s so that a new
 * enum value is a compile error until it gets a translation.
 */

@StringRes fun phaseLabel(p: Phase): Int = when (p) {
    Phase.PREPARING -> R.string.phase_preparing
    Phase.SEARCHING -> R.string.phase_searching
    Phase.BLE_CONNECTED -> R.string.phase_ble
    Phase.PASE -> R.string.phase_pase
    Phase.ATTESTATION -> R.string.phase_attestation
    Phase.NETWORK -> R.string.phase_network
    Phase.CASE -> R.string.phase_case
    Phase.DONE -> R.string.phase_done
}

fun phaseIcon(p: Phase): ImageVector = when (p) {
    Phase.PREPARING -> Icons.Rounded.Settings
    Phase.SEARCHING -> Icons.Rounded.BluetoothSearching
    Phase.BLE_CONNECTED -> Icons.Rounded.Bluetooth
    Phase.PASE -> Icons.Rounded.Lock
    Phase.ATTESTATION -> Icons.Rounded.VerifiedUser
    Phase.NETWORK -> Icons.Rounded.Wifi
    Phase.CASE -> Icons.Rounded.Hub
    Phase.DONE -> Icons.Rounded.CheckCircle
}

@StringRes fun checkLabel(id: CheckId): Int = when (id) {
    CheckId.BLUETOOTH -> R.string.check_BLUETOOTH
    CheckId.PERMISSIONS -> R.string.check_PERMISSIONS
    CheckId.BATTERY -> R.string.check_BATTERY
    CheckId.NETWORK -> R.string.check_NETWORK
    CheckId.HA_ADMIN -> R.string.check_HA_ADMIN
    CheckId.MATTER_APP -> R.string.check_MATTER_APP
    CheckId.BLE_PROXY_OPTION -> R.string.check_BLE_PROXY_OPTION
    CheckId.BLE_ENDPOINT -> R.string.check_BLE_ENDPOINT
    CheckId.THREAD -> R.string.check_THREAD
    CheckId.WIFI -> R.string.check_WIFI
    CheckId.OTHER_PROXIES -> R.string.check_OTHER_PROXIES
    CheckId.DEVICE -> R.string.check_DEVICE
}

/** Help text shown under a check that is not green. */
@StringRes fun checkHelp(id: CheckId, weakSignal: Boolean): Int? = when (id) {
    CheckId.BATTERY -> R.string.check_help_BATTERY
    CheckId.NETWORK -> R.string.check_help_NETWORK
    CheckId.THREAD -> R.string.check_help_THREAD
    CheckId.WIFI -> R.string.check_help_WIFI
    CheckId.OTHER_PROXIES -> R.string.check_help_OTHER_PROXIES
    CheckId.DEVICE -> if (weakSignal) R.string.check_help_DEVICE_weak else R.string.check_help_DEVICE_missing
    CheckId.MATTER_APP -> R.string.check_help_MATTER_APP
    CheckId.BLE_PROXY_OPTION -> R.string.check_help_BLE_PROXY_OPTION
    CheckId.HA_ADMIN -> R.string.auth_admin_required
    else -> null
}

@StringRes fun failureTitle(k: FailureKind): Int = when (k) {
    FailureKind.NOT_ADMIN -> R.string.fail_NOT_ADMIN
    FailureKind.NO_MATTER_APP -> R.string.fail_NO_MATTER_APP
    FailureKind.PROXY_UNAVAILABLE -> R.string.fail_PROXY_UNAVAILABLE
    FailureKind.DEVICE_NOT_FOUND -> R.string.fail_DEVICE_NOT_FOUND
    FailureKind.OTHER_PROXY -> R.string.fail_OTHER_PROXY
    FailureKind.BLE_ERROR -> R.string.fail_BLE_ERROR
    FailureKind.PASE_FAILED -> R.string.fail_PASE_FAILED
    FailureKind.ATTESTATION_FAILED -> R.string.fail_ATTESTATION_FAILED
    FailureKind.NETWORK_FAILED -> R.string.fail_NETWORK_FAILED
    FailureKind.HA_ERROR -> R.string.fail_HA_ERROR
}

@StringRes fun failureTips(k: FailureKind): Int = when (k) {
    FailureKind.DEVICE_NOT_FOUND -> R.string.tips_DEVICE_NOT_FOUND
    FailureKind.OTHER_PROXY -> R.string.tips_OTHER_PROXY
    FailureKind.BLE_ERROR -> R.string.tips_BLE_ERROR
    FailureKind.PASE_FAILED -> R.string.tips_PASE_FAILED
    FailureKind.ATTESTATION_FAILED -> R.string.tips_ATTESTATION_FAILED
    FailureKind.NETWORK_FAILED -> R.string.tips_NETWORK_FAILED
    FailureKind.PROXY_UNAVAILABLE -> R.string.tips_PROXY_UNAVAILABLE
    else -> R.string.tips_generic
}

@StringRes fun flowLabel(f: CommissioningFlow): Int = when (f) {
    CommissioningFlow.STANDARD -> R.string.flow_STANDARD
    CommissioningFlow.USER_INTENT -> R.string.flow_USER_INTENT
    CommissioningFlow.CUSTOM -> R.string.flow_CUSTOM
    CommissioningFlow.RESERVED -> R.string.flow_RESERVED
}

@StringRes fun payloadErrorLabel(e: PayloadError): Int = when (e) {
    PayloadError.EMPTY -> R.string.payload_error_EMPTY
    PayloadError.BAD_PREFIX -> R.string.payload_error_BAD_PREFIX
    PayloadError.BAD_BASE38 -> R.string.payload_error_BAD_BASE38
    PayloadError.TOO_SHORT -> R.string.payload_error_TOO_SHORT
    PayloadError.BAD_LENGTH -> R.string.payload_error_BAD_LENGTH
    PayloadError.NOT_DIGITS -> R.string.payload_error_NOT_DIGITS
    PayloadError.BAD_CHECK_DIGIT -> R.string.payload_error_BAD_CHECK_DIGIT
    PayloadError.INVALID_PASSCODE -> R.string.payload_error_INVALID_PASSCODE
    PayloadError.BAD_VERSION -> R.string.payload_error_BAD_VERSION
}
