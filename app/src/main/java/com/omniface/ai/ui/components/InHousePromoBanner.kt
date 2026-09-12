package com.omniface.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniface.ai.billing.SubscriptionTier
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.ui.theme.*

/**
 * Non-intrusive in-house promotional banner displayed only for Free Plan users.
 * Automatically disappears when user upgrades to Premium or Business.
 */
@Composable
fun InHousePromoBanner(
    modifier: Modifier = Modifier,
    onUpgradeClick: () -> Unit
) {
    val currentTier by SubscriptionTierManager.currentTier.collectAsState()
    val isDark = LocalThemeIsDark.current

    // Never display ads or promos to Premium/Business subscribers
    if (currentTier != SubscriptionTier.FREE) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(omniEmerald(isDark).copy(alpha = 0.5f), omniCyan(isDark).copy(alpha = 0.5f))
                ),
                RoundedCornerShape(16.dp)
            )
            .background(
                Brush.horizontalGradient(
                    if (isDark) {
                        listOf(Color(0xFF064E3B).copy(alpha = 0.35f), Color(0xFF0C4A6E).copy(alpha = 0.35f))
                    } else {
                        listOf(Color(0xFFECFDF5), Color(0xFFF0F9FF))
                    }
                )
            )
            .clickable { onUpgradeClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(omniEmerald(isDark).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = omniEmerald(isDark),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Upgrade to Premium",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = omniTextPrimary(isDark)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(omniEmerald(isDark))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("₹199/mo", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                    }
                }
                Text(
                    text = "250 slots • Excel & PDF reports • Drive cloud sync",
                    fontSize = 11.5.sp,
                    color = omniTextSecondary(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = omniEmerald(isDark),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
