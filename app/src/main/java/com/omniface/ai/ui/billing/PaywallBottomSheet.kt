package com.omniface.ai.ui.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.billing.PlayBillingManager
import com.omniface.ai.billing.SubscriptionTier
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.ui.components.IOSCard
import com.omniface.ai.ui.theme.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallBottomSheet(
    triggerReason: PaywallTriggerReason,
    onDismiss: () -> Unit,
    onUpgradeSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = LocalThemeIsDark.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedPlan by remember { mutableStateOf(SubscriptionTier.PREMIUM) }
    val playPrice by PlayBillingManager.formattedPrice.collectAsState()

    // Listen for Billing purchase/restore events
    LaunchedEffect(Unit) {
        PlayBillingManager.billingEvents.collect { event ->
            when (event) {
                is PlayBillingManager.BillingEvent.PurchaseSuccess -> {
                    Toast.makeText(context, "🎉 Welcome to OmniFace Premium! All features unlocked.", Toast.LENGTH_LONG).show()
                    onUpgradeSuccess?.invoke()
                    onDismiss()
                }
                is PlayBillingManager.BillingEvent.PurchasePending -> {
                    Toast.makeText(context, "Payment pending completion with your bank.", Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                is PlayBillingManager.BillingEvent.PurchaseFailed -> {
                    Toast.makeText(context, "Payment unsuccessful: ${event.message}", Toast.LENGTH_LONG).show()
                }
                is PlayBillingManager.BillingEvent.UserCanceled -> {
                    // Clean user dismissal
                }
                is PlayBillingManager.BillingEvent.RestoreResult -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    if (event.success) {
                        onUpgradeSuccess?.invoke()
                        onDismiss()
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero Icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(omniEmerald(isDark).copy(alpha = 0.2f), omniCyan(isDark).copy(alpha = 0.2f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = omniEmerald(isDark),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Headline
            Text(
                text = triggerReason.headline,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = omniTextPrimary(isDark),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Description
            Text(
                text = triggerReason.description,
                fontSize = 13.sp,
                color = omniTextSecondary(isDark),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Plan Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Free Plan (Current)
                PlanSelectionCard(
                    title = "Free",
                    price = "₹0",
                    period = "forever",
                    badge = "Current",
                    features = listOf("25 people limit", "Face recognition", "Offline operation", "Local database"),
                    isSelected = selectedPlan == SubscriptionTier.FREE,
                    isDark = isDark,
                    modifier = Modifier.width(135.dp),
                    onClick = { selectedPlan = SubscriptionTier.FREE }
                )

                // Premium Plan (Featured)
                PlanSelectionCard(
                    title = "Premium",
                    price = playPrice.substringBefore(" /").ifBlank { "₹199" },
                    period = "/ month",
                    badge = "POPULAR",
                    badgeColor = omniEmerald(isDark),
                    features = listOf("250 people limit", "Auto cloud sync", "Excel/PDF export", "Multi-device fleet"),
                    isSelected = selectedPlan == SubscriptionTier.PREMIUM,
                    isDark = isDark,
                    modifier = Modifier.width(145.dp),
                    onClick = { selectedPlan = SubscriptionTier.PREMIUM }
                )

                // Pro Plan
                PlanSelectionCard(
                    title = "Pro",
                    price = "₹349",
                    period = "/ month",
                    badge = "PRO",
                    badgeColor = OmniViolet,
                    features = listOf("500 users limit", "Advanced analytics", "Multiple classes", "Priority support"),
                    isSelected = selectedPlan == SubscriptionTier.PRO,
                    isDark = isDark,
                    modifier = Modifier.width(145.dp),
                    onClick = { selectedPlan = SubscriptionTier.PRO }
                )

                // Institution Plan
                PlanSelectionCard(
                    title = "Institution",
                    price = "Custom",
                    period = "pricing",
                    badge = "Institutes",
                    badgeColor = omniCyan(isDark),
                    features = listOf("500+ users", "Unlimited devices", "Multi-admin", "Departments & classes", "Audit logs & reports", "Custom onboarding"),
                    isSelected = selectedPlan == SubscriptionTier.INSTITUTION,
                    isDark = isDark,
                    modifier = Modifier.width(145.dp),
                    onClick = { selectedPlan = SubscriptionTier.INSTITUTION }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action Button
            if (selectedPlan == SubscriptionTier.PREMIUM) {
                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) {
                            PlayBillingManager.launchBillingFlow(activity) { launched, msg ->
                                if (!launched) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            PlayBillingManager.activateSandboxPremium()
                            Toast.makeText(context, "🎉 Welcome to OmniFace Premium! All features unlocked.", Toast.LENGTH_LONG).show()
                            onUpgradeSuccess?.invoke()
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = omniEmerald(isDark)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START PREMIUM — $playPrice".uppercase(), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (selectedPlan == SubscriptionTier.PRO) {
                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) {
                            PlayBillingManager.launchBillingFlow(activity) { launched, msg ->
                                if (!launched) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            SubscriptionTierManager.setSubscription(SubscriptionTier.PRO, System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(30))
                            Toast.makeText(context, "🎉 Welcome to OmniFace Pro! All features unlocked.", Toast.LENGTH_LONG).show()
                            onUpgradeSuccess?.invoke()
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OmniViolet),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.WorkspacePremium, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START PRO (₹349 / MO)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (selectedPlan == SubscriptionTier.INSTITUTION) {
                Button(
                    onClick = {
                        val portalUrl = "https://omniface.vercel.app/subscription"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Contact sales at omniface.ai for Institution Plan setup.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Business, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CONTACT INSTITUTION SALES", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Continue with Free (25 people)", color = omniTextSecondary(isDark), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore Purchases / Terms Footer
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    Toast.makeText(context, "Checking Google Play for active subscriptions...", Toast.LENGTH_SHORT).show()
                    PlayBillingManager.restorePurchases { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        if (success) {
                            onUpgradeSuccess?.invoke()
                            onDismiss()
                        }
                    }
                }) {
                    Text("Restore", fontSize = 11.5.sp, color = omniTextSecondary(isDark))
                }
                Text("•", fontSize = 11.sp, color = omniTextSecondary(isDark))
                TextButton(onClick = {
                    Toast.makeText(context, "Syncing license with Web Cloud...", Toast.LENGTH_SHORT).show()
                    PlayBillingManager.pullSubscriptionFromBackend(
                        onResolved = { tier ->
                            if (tier != SubscriptionTier.FREE) {
                                Toast.makeText(context, "✓ Active $tier subscription synced from Web!", Toast.LENGTH_LONG).show()
                                onUpgradeSuccess?.invoke()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "No active Web subscription found for this organization.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }) {
                    Text("Sync Web License", fontSize = 11.5.sp, color = omniCyan(isDark))
                }
                Text("•", fontSize = 11.sp, color = omniTextSecondary(isDark))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://omniface.vercel.app/terms"))
                    try { context.startActivity(intent) } catch (_: Exception) {}
                }) {
                    Text("Terms & Privacy", fontSize = 11.5.sp, color = omniTextSecondary(isDark))
                }
            }
        }
    }
}

@Composable
private fun PlanSelectionCard(
    title: String,
    price: String,
    period: String,
    badge: String,
    badgeColor: Color = Color.Gray,
    features: List<String>,
    isSelected: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) {
        if (title == "Premium") omniEmerald(isDark) else omniCyan(isDark)
    } else {
        if (isDark) Color(0x22FFFFFF) else Color(0x11000000)
    }

    val bgColor = if (isSelected) {
        if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
    } else {
        if (isDark) Color(0x11FFFFFF) else Color(0x06000000)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badgeColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = badgeColor)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = omniTextPrimary(isDark))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(price, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = omniTextPrimary(isDark))
            Text(period, fontSize = 9.sp, color = omniTextSecondary(isDark))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            features.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (isSelected) omniEmerald(isDark) else Color.Gray,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = f,
                        fontSize = 9.sp,
                        color = omniTextSecondary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
