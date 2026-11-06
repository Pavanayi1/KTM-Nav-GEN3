package com.mensa.riderintercom.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mensa.riderintercom.util.OemDetector

/**
 * The onboarding screen that decides whether the call survives a ride.
 *
 * Standard Android keeps a foreground service alive; several manufacturers do not, and
 * no amount of correct code changes that. The only fix is walking the rider through that
 * vendor's own settings once.
 */
@Composable
fun OemScreen(
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val guidance = OemDetector.guidanceFor()
    val exempt = OemDetector.isIgnoringBatteryOptimisations(context)

    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = "Keeping the call alive",
            subtitle = "Doze and battery managers are what end rides early",
            onBack = onBack,
        )

        SectionCard(title = "Step 1 · battery optimisation") {
            Text(
                if (exempt) "Done. This app is already exempt from Doze."
                else "Android's Doze will suspend the call once the screen has been off " +
                    "for a while. Ask for the exemption here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!exempt) {
                PrimaryButton("Allow background running", true, onRequestBatteryExemption)
            }
        }

        if (guidance != null) {
            SectionCard(title = "Step 2 · ${guidance.vendor}") {
                Text(
                    "This phone's manufacturer overrides standard Android and can kill " +
                        "the call anyway. Work through these once:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
                guidance.steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}.  $step",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (OemDetector.canOpen(context, guidance.intent)) {
                    PrimaryButton(
                        text = "Open ${guidance.vendor} settings",
                        enabled = true,
                        onClick = { guidance.intent?.let(onOpenOemSettings) },
                    )
                }
            }
        } else {
            SectionCard(title = "Step 2 · this phone") {
                Text(
                    "No vendor-specific battery manager detected on this phone. Standard " +
                        "Android rules apply, so the exemption above should be enough.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionCard(title = "What to expect anyway") {
            Text(
                "If the call does stop, the app posts a notification rather than " +
                    "restarting itself. Android 14 refuses to create a microphone service " +
                    "from the background, and a silent retry is what produces one-way " +
                    "audio. Open the app and tap Start again.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            SecondaryButton("Open this app's system settings", onOpenAppSettings)
        }
    }
}
