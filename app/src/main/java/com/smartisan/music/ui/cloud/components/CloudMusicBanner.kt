package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.smartisan.music.data.online.OnlineBanner
import kotlinx.coroutines.delay

/** Banner 高度：对齐旧版 CloudBannerHeight。 */
internal val CloudMusicBannerHeight = 142.dp

/** Banner 两侧留白，使轮播卡片与页面内容左右对齐。 */
internal val CloudMusicBannerHorizontalPadding = 12.dp

/** Banner 圆角。 */
internal val CloudMusicBannerCornerRadius = 8.dp

/** Banner 自动轮播间隔：对齐旧版 CloudBannerAutoScrollMs。 */
internal const val CloudMusicBannerAutoScrollIntervalMs = 5_000L

/**
 * Banner 素材请求宽度（px）：对齐旧版 CloudBannerArtworkWidthPx。
 *
 * 约等于绘制宽度（1200px 屏减去左右 12dp），配合 INEXACT 让 Coil 就近取图，
 * 不放大也不方裁——不能复用 96dp 卡片那套 `param=260y260` 的封面请求。
 */
private const val CloudMusicBannerArtworkWidthPx = 900

/** 未选中圆点颜色。 */
private val CloudMusicBannerDotColor = Color(0x80FFFFFF)
private val CloudMusicBannerDotSelectedColor = Color.White

/**
 * 云音乐首页 Banner 轮播：基于 [HorizontalPager]，自动滚动 + 底部指示器 + 文案渐变蒙层。
 *
 * 点击行为按 target 类型分发：歌单 → [onOpenPlaylist]，专辑 → [onOpenAlbum]，
 * 歌曲 → [onPlayTrack]（由宿主解析占位 URI 后交给播放控制器）。
 */
@Composable
internal fun CloudMusicBanner(
    banners: List<OnlineBanner>,
    active: Boolean,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onTrackClick: (banner: OnlineBanner) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (banners.isEmpty()) return
    val pageCount = banners.size
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // 自动轮播：仅在活跃且多于 1 页时启动；pageCount/active 变化后重启计时。
    LaunchedEffect(pagerState.pageCount, active) {
        if (!active || pagerState.pageCount <= 1) return@LaunchedEffect
        while (true) {
            delay(CloudMusicBannerAutoScrollIntervalMs)
            val nextPage = (pagerState.currentPage + 1) % pagerState.pageCount
            pagerState.animateScrollToPage(nextPage)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val banner = banners[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CloudMusicBannerHorizontalPadding)
                    .height(CloudMusicBannerHeight)
                    .clip(RoundedCornerShape(CloudMusicBannerCornerRadius))
                    .cloudMusicPressable(
                        onClick = {
                            banner.dispatchClick(
                                onOpenPlaylist = onOpenPlaylist,
                                onOpenAlbum = onOpenAlbum,
                                onTrackClick = onTrackClick,
                            )
                        },
                    ),
            ) {
                CloudMusicBannerArtwork(
                    imageUrl = banner.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                )
                // 底部渐变蒙层 + 标题 / 副标题，保持与旧版云音乐 Banner 文案样式一致。
                if (banner.title.isNotBlank() || !banner.subtitle.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xCC000000)),
                                ),
                            )
                            .padding(12.dp),
                    ) {
                        Column {
                            if (banner.title.isNotBlank()) {
                                Text(
                                    text = banner.title,
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!banner.subtitle.isNullOrBlank()) {
                                Text(
                                    text = banner.subtitle,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = Color(0xCCFFFFFF),
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部圆点指示器：选中态拉长为胶囊，非选中态为圆点。
        if (pageCount > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(pageCount) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(
                                width = if (isSelected) 16.dp else 6.dp,
                                height = 6.dp,
                            )
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isSelected) CloudMusicBannerDotSelectedColor
                                else CloudMusicBannerDotColor,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Banner 专用素材：原图 URL（不带 `param`，不方裁）+ 大请求宽度 + INEXACT。
 *
 * 复用封面加载器会把宽幅素材压成 260px 方图再放大到整屏宽，视觉上是"被放大且缺内容"。
 */
@Composable
private fun CloudMusicBannerArtwork(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safeUrl =
        imageUrl
            ?.replaceFirst("http://", "https://")
            ?.takeIf(String::isNotBlank)
    if (safeUrl == null) {
        Box(modifier = modifier.background(CloudArtworkPlaceholderColor))
        return
    }
    val request =
        remember(context, safeUrl) {
            ImageRequest.Builder(context)
                .data(safeUrl)
                .size(CloudMusicBannerArtworkWidthPx)
                .precision(Precision.INEXACT)
                .build()
        }
    Box(modifier = modifier.background(CloudArtworkPlaceholderColor)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 按 target 类型把 Banner 点击分发到对应入口；无 target 时忽略。 */
private fun OnlineBanner.dispatchClick(
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onTrackClick: (banner: OnlineBanner) -> Unit,
) {
    when {
        !targetPlaylistId.isNullOrBlank() -> onOpenPlaylist(targetPlaylistId, title)
        !targetAlbumId.isNullOrBlank() -> onOpenAlbum(targetAlbumId, title)
        !targetTrackId.isNullOrBlank() -> onTrackClick(this)
    }
}
