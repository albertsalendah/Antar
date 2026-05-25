package com.richard_salendah.antar.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Core shimmer brush ─────────────────────────────────────────────────────────

@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color(0xFFE8E8E8),
        Color(0xFFF5F5F5),
        Color(0xFFE8E8E8),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue  = -600f,
        targetValue   =  600f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(offset, offset),
        end    = Offset(offset + 400f, offset + 400f),
    )
}

// ── Primitive: a single shimmer-filled rounded box ────────────────────────────

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp   = 6.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush()),
    )
}

// ── Trip history card skeleton ────────────────────────────────────────────────

@Composable
fun TripCardSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row: type badge + status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ShimmerBox(modifier = Modifier.width(72.dp).height(22.dp), cornerRadius = 6.dp)
                ShimmerBox(modifier = Modifier.width(60.dp).height(22.dp), cornerRadius = 6.dp)
            }
            Spacer(Modifier.height(12.dp))
            // Pickup line
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.85f).height(12.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(12.dp))
            Spacer(Modifier.height(12.dp))
            // Divider
            ShimmerBox(modifier = Modifier.fillMaxWidth().height(1.dp), cornerRadius = 0.dp)
            Spacer(Modifier.height(12.dp))
            // Footer: fare + button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column {
                    ShimmerBox(modifier = Modifier.width(90.dp).height(14.dp))
                    Spacer(Modifier.height(4.dp))
                    ShimmerBox(modifier = Modifier.width(110.dp).height(11.dp))
                }
                ShimmerBox(modifier = Modifier.width(64.dp).height(34.dp), cornerRadius = 8.dp)
            }
        }
    }
}

// ── Profile skeleton ──────────────────────────────────────────────────────────

@Composable
fun ProfileSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Avatar circle
        ShimmerBox(
            modifier     = Modifier.size(96.dp),
            cornerRadius = 48.dp,
        )
        Spacer(Modifier.height(12.dp))
        // Name
        ShimmerBox(modifier = Modifier.width(140.dp).height(18.dp))
        Spacer(Modifier.height(6.dp))
        // Island
        ShimmerBox(modifier = Modifier.width(80.dp).height(12.dp))
        Spacer(Modifier.height(28.dp))
        // Info card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                repeat(3) { index ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        ShimmerBox(modifier = Modifier.fillMaxWidth().height(1.dp), cornerRadius = 0.dp)
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ShimmerBox(modifier = Modifier.size(20.dp), cornerRadius = 4.dp)
                        Column {
                            ShimmerBox(modifier = Modifier.width(40.dp).height(10.dp))
                            Spacer(Modifier.height(4.dp))
                            ShimmerBox(modifier = Modifier.width(130.dp).height(14.dp))
                        }
                    }
                }
            }
        }
    }
}