package com.smartisan.music.ui.playback

/**
 * 播放页的返回仲裁。
 *
 * 原先是两个并列的 `BackHandler`：`PlaybackPage` 里一个 `enabled = queueVisible` 的队列处理器，
 * 和 [PlaybackScreenSessionEffects] 里的播放页处理器。`PlaybackScreen` 在 `PlaybackPage` 之后才被调用，
 * 它的 `BackHandler` 注册更晚，而 `OnBackPressedDispatcher` 只回调最后注册且 enabled 的那一个，
 * 所以队列处理器从未生效——队列可见时按返回直接把整个播放页收起了。
 *
 * 这里把「队列可见」并入同一次仲裁，[PlaybackScreenSessionEffects] 只保留一个 `BackHandler`。
 * 优先级沿用原来的注册顺序（睡眠定时器弹层 > 更多面板），队列插在收起播放页之前：
 * 一次返回只做一件事，最上层的可见层先关闭，兜底才是收起播放页。
 */

/** 这一次返回由谁消费。 */
internal enum class PlaybackBackTarget {
    DismissSleepTimerDialog,
    DismissMorePanel,
    DismissQueue,
    Collapse,
}

/**
 * 播放页返回仲裁的纯逻辑：按优先级取第一个命中的分支，[PlaybackBackTarget.Collapse] 兜底，永不落空。
 * 因此 `BackHandler` 无需 `enabled` 条件，注册的那一个就是当前唯一该消费返回键的处理器。
 */
internal fun playbackBackTarget(
    showSleepTimerDialog: Boolean,
    showMorePanel: Boolean,
    queueVisible: Boolean,
): PlaybackBackTarget =
    when {
        showSleepTimerDialog -> PlaybackBackTarget.DismissSleepTimerDialog
        showMorePanel -> PlaybackBackTarget.DismissMorePanel
        queueVisible -> PlaybackBackTarget.DismissQueue
        else -> PlaybackBackTarget.Collapse
    }
