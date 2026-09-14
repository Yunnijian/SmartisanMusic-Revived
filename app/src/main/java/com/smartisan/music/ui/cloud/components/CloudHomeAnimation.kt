package com.smartisan.music.ui.cloud.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 首页可入场的区块上限：每日推荐 / 歌单 / 排行榜 / 专辑 / 艺人。 */
internal const val CloudHomeAnimatedSectionCount = 5

/** 相邻区块入场的错峰间隔，对齐旧版 delayMillisPerItem。 */
internal const val CloudHomeSectionStaggerMs = 60

/**
 * 首页区块入场动画的可见性状态集合，由宿主级数据仓库持有。
 *
 * 两个约束决定了它不能像旧版那样放在页内 remember：
 * - 首页用 [androidx.compose.foundation.lazy.LazyColumn] 承载区块，item 滚出视口即被回收，
 *   状态放在区块内部会让滚回来时重播一次入场动画；
 * - 首页在入口层之间会被移出组合，若每次重建都重播，区块会先折叠成零高度再展开，
 *   把宿主保管的滚动位置钳回顶部。因此动画只在数据首次就绪时播放一次，之后保持展开。
 */
@Stable
internal class CloudHomeSectionAnimation(private val scope: CoroutineScope) {
    private val states = List(CloudHomeAnimatedSectionCount) { MutableTransitionState(false) }
    private var requested = false

    fun stateAt(index: Int): MutableTransitionState<Boolean> = states[index]

    /** 首次调用后以错峰方式把各区块置为可见；重复调用直接跳过。 */
    fun requestPlay() {
        if (requested) return
        requested = true
        scope.launch {
            states.forEachIndexed { index, state ->
                launch {
                    val delayMs = index * CloudHomeSectionStaggerMs.toLong()
                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                    state.targetState = true
                }
            }
        }
    }
}

/**
 * 云音乐首页区块的错峰入场动画：淡入 220ms + 上滑 1/12 高度 280ms（FastOutSlowIn）。
 *
 * [visibleState] 由 [CloudHomeSectionAnimation] 统一持有。
 */
@Composable
internal fun CloudHomeAnimatedSection(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(220)) +
            slideInVertically(
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 12 },
            ),
    ) {
        content()
    }
}
