package com.oracole.readit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 后台任务悬浮条
 *
 * 用来告诉用户有件事还在跑，可以点回来看
 *
 * 为什么放底部：三个主页面都用 MediumTopAppBar，展开态占 112dp
 * 顶部悬浮条会压住标题。底部悬浮也是后台任务提示的通行位置（下载、导出都这么做）
 */
@Composable
fun TaskPill(
    visible: Boolean,
    title: String,
    subtitle: String = "点击查看进度",
    /** 左边那个转圈。只在「还在跑」时显示；已经完成的通知不该转，转圈意味着没结束 */
    showProgress: Boolean = true,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            initialOffsetY = { it }
        ) + fadeIn(),
        exit = slideOutVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            targetOffsetY = { it }
        ) + fadeOut()
    ) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showProgress) {
                    CircularProgressIndicator(
                        Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}
