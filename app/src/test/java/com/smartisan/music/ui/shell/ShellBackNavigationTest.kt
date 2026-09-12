package com.smartisan.music.ui.shell

import com.smartisan.music.ui.navigation.MusicDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBackNavigationTest {

    // ── 一级 tab ──

    @Test
    fun backOnPinnedTabDoesNotConsume() {
        MusicDestination.entries.forEach { destination ->
            val state = ShellBackNavigationState(destination = destination)
            assertEquals(
                "从底栏进入 $destination 后返回不应消费",
                ShellBackOwner.None,
                state.resolveOwner(),
            )
        }
    }

    @Test
    fun switchingBetweenPinnedTabsKeepsRootBehavior() {
        // 底栏切 tab 只改 destination，返回仍然落到「不消费」。
        listOf(
            MusicDestination.Playlist,
            MusicDestination.Artist,
            MusicDestination.Album,
            MusicDestination.More,
        ).forEach { destination ->
                assertEquals(
                    ShellBackOwner.None,
                    ShellBackNavigationState(destination = destination).resolveOwner(),
                )
            }
    }

    // ── 「更多」→ 子页 ──

    @Test
    fun backFromOverflowSubPageReturnsToMore() {
        assertEquals(
            ShellBackOwner.ReturnToMore,
            ShellBackNavigationState(
                destination = MusicDestination.Songs,
                presentedFromMore = true,
            ).resolveOwner(),
        )
        assertEquals(
            ShellBackOwner.ReturnToMore,
            ShellBackNavigationState(
                destination = MusicDestination.Album,
                presentedFromMore = true,
            ).resolveOwner(),
        )
        assertEquals(
            ShellBackOwner.ReturnToMore,
            ShellBackNavigationState(
                destination = MusicDestination.Artist,
                presentedFromMore = true,
            ).resolveOwner(),
        )
    }

    @Test
    fun backFromOverflowLandingPageIsOwnedByThatPage() {
        // 「更多」落地页与各二级页自己处理返回（整页设置、歌单、文件夹、流派、云音乐、我喜欢），
        // 主壳不插手，与迁移前 `else -> false` 一致。
        listOf(
            MusicDestination.More,
            MusicDestination.Playlist,
            MusicDestination.Folder,
            MusicDestination.Genre,
            MusicDestination.Cloud,
            MusicDestination.LovedSongs,
        ).forEach { destination ->
            assertEquals(
                ShellBackOwner.None,
                ShellBackNavigationState(
                    destination = destination,
                    presentedFromMore = true,
                ).resolveOwner(),
            )
        }
    }

    // ── 编辑态 ──

    @Test
    fun editModesYieldToTheirOwnExitHandlers() {
        // 歌曲/专辑编辑态下主壳不消费返回，与 HEAD 一致。注意这两个页面并没有自己的 BackHandler，
        // 此时返回落到系统默认行为（退出 Activity）；本轮只保持等价，未新增「返回先退出编辑」的处理。
        assertEquals(
            ShellBackOwner.None,
            ShellBackNavigationState(
                destination = MusicDestination.Songs,
                presentedFromMore = true,
                songsEditMode = true,
            ).resolveOwner(),
        )
        assertEquals(
            ShellBackOwner.None,
            ShellBackNavigationState(
                destination = MusicDestination.Album,
                presentedFromMore = true,
                albumEditMode = true,
            ).resolveOwner(),
        )
        // 「我喜欢」等页面的编辑态由页面自己的 BackHandler 消费，主壳同样不消费。
        assertEquals(
            ShellBackOwner.None,
            ShellBackNavigationState(
                destination = MusicDestination.LovedSongs,
                presentedFromMore = true,
            ).resolveOwner(),
        )
    }

    @Test
    fun albumDetailOutranksAlbumEditMode() {
        // 专辑详情打开时先关详情，编辑态标志不参与该分支。
        assertEquals(
            ShellBackOwner.CloseAlbumDetail,
            ShellBackNavigationState(
                destination = MusicDestination.Album,
                presentedFromMore = true,
                albumEditMode = true,
                albumDetailOpen = true,
            ).resolveOwner(),
        )
    }

    // ── 详情页 ──

    @Test
    fun albumDetailBackClosesDetail() {
        assertEquals(
            ShellBackOwner.CloseAlbumDetail,
            ShellBackNavigationState(
                destination = MusicDestination.Album,
                albumDetailOpen = true,
            ).resolveOwner(),
        )
    }

    @Test
    fun artistDetailBackClosesDetailOnEveryLevel() {
        assertEquals(
            ShellBackOwner.CloseArtistDetail,
            ShellBackNavigationState(
                destination = MusicDestination.Artist,
                artistDetailOpen = true,
                artistDetailHasParent = false,
            ).resolveOwner(),
        )
        assertEquals(
            ShellBackOwner.CloseArtistDetail,
            ShellBackNavigationState(
                destination = MusicDestination.Artist,
                presentedFromMore = true,
                artistDetailOpen = true,
                artistDetailHasParent = true,
            ).resolveOwner(),
        )
    }

    // ── 覆盖层优先 ──

    @Test
    fun visibleOverlayOwnsBackBeforeAnyShellBranch() {
        ShellBackOverlay.entries.filter { it != ShellBackOverlay.None }.forEach { overlay ->
            shellBranchStates().forEach { branchState ->
                assertEquals(
                    "$overlay 可见时应由覆盖层消费",
                    ShellBackOwner.VisibleOverlay,
                    branchState.copy(visibleOverlay = overlay).resolveOwner(),
                )
            }
        }
    }

    @Test
    fun overlayMappingTakesTheLastComposedOverlay() {
        assertEquals(
            ShellBackOverlay.NavigationEditor,
            shellBackOverlay(
                playbackVisible = true,
                searchVisible = true,
                playlistPickerVisible = true,
                navigationEditorVisible = true,
            ),
        )
        assertEquals(
            ShellBackOverlay.PlaylistPicker,
            shellBackOverlay(
                playbackVisible = true,
                searchVisible = true,
                playlistPickerVisible = true,
                navigationEditorVisible = false,
            ),
        )
        assertEquals(
            ShellBackOverlay.Search,
            shellBackOverlay(
                playbackVisible = true,
                searchVisible = true,
                playlistPickerVisible = false,
                navigationEditorVisible = false,
            ),
        )
        assertEquals(
            ShellBackOverlay.Playback,
            shellBackOverlay(
                playbackVisible = true,
                searchVisible = false,
                playlistPickerVisible = false,
                navigationEditorVisible = false,
            ),
        )
        assertEquals(
            ShellBackOverlay.None,
            shellBackOverlay(
                playbackVisible = false,
                searchVisible = false,
                playlistPickerVisible = false,
                navigationEditorVisible = false,
            ),
        )
    }

    // ── 与迁移前 4 个并列 BackHandler 的逐条等价 ──

    @Test
    fun shellBranchesArePairwiseDisjoint() {
        forEachState { state ->
            assertTrue(
                "分支应当两两互斥，实际 $state -> ${state.enabledBranches}",
                state.enabledBranches.size <= 1,
            )
        }
    }

    @Test
    fun stateMachineMatchesTheFourLegacyBackHandlers() {
        forEachState { state ->
            if (state.visibleOverlay != ShellBackOverlay.None) {
                assertEquals(
                    "覆盖层可见时主壳让位：$state",
                    ShellBackOwner.VisibleOverlay,
                    state.resolveOwner(),
                )
                return@forEachState
            }
            val expected = when (legacyHighestPriorityEnabled(state)) {
                LegacyBranch.AlbumDetail -> ShellBackOwner.CloseAlbumDetail
                LegacyBranch.RootArtistDetail -> ShellBackOwner.CloseArtistDetail
                LegacyBranch.NestedArtistDetail -> ShellBackOwner.CloseArtistDetail
                LegacyBranch.ReturnToMore -> ShellBackOwner.ReturnToMore
                null -> ShellBackOwner.None
            }
            assertEquals("状态 $state 与迁移前不一致", expected, state.resolveOwner())
        }
    }

    private enum class LegacyBranch {
        AlbumDetail,
        RootArtistDetail,
        ReturnToMore,
        NestedArtistDetail,
    }

    /**
     * 直接照抄迁移前 4 个 BackHandler 的 enabled 条件，按注册顺序取最后一个 enabled，
     * 也就是 `OnBackPressedDispatcher` 实际会选中的那一个。
     */
    private fun legacyHighestPriorityEnabled(state: ShellBackNavigationState): LegacyBranch? {
        val selectedAlbumId: String? = if (state.albumDetailOpen) "album" else null
        val selectedArtistTarget: String? = if (state.artistDetailOpen) "artist" else null
        val selectedArtistParentTarget: String? =
            if (state.artistDetailOpen && state.artistDetailHasParent) "parent" else null
        return listOfNotNull(
            LegacyBranch.AlbumDetail.takeIf {
                state.destination == MusicDestination.Album && selectedAlbumId != null
            },
            LegacyBranch.RootArtistDetail.takeIf {
                state.destination == MusicDestination.Artist &&
                    selectedArtistTarget != null &&
                    selectedArtistParentTarget == null
            },
            LegacyBranch.ReturnToMore.takeIf {
                state.presentedFromMore &&
                    when (state.destination) {
                        MusicDestination.Songs -> !state.songsEditMode
                        MusicDestination.Album ->
                            selectedAlbumId == null && !state.albumEditMode
                        MusicDestination.Artist -> selectedArtistTarget == null
                        else -> false
                    }
            },
            LegacyBranch.NestedArtistDetail.takeIf {
                state.destination == MusicDestination.Artist &&
                    selectedArtistTarget != null &&
                    selectedArtistParentTarget != null
            },
        ).lastOrNull()
    }

    private fun shellBranchStates(): List<ShellBackNavigationState> {
        return MusicDestination.entries.flatMap { destination ->
            combinations().map { (presentedFromMore, flags) ->
                ShellBackNavigationState(
                    destination = destination,
                    presentedFromMore = presentedFromMore,
                    songsEditMode = flags[0],
                    albumEditMode = flags[1],
                    albumDetailOpen = flags[2],
                    artistDetailOpen = flags[3],
                    artistDetailHasParent = flags[4],
                )
            }
        }
    }

    private fun forEachState(block: (ShellBackNavigationState) -> Unit) {
        ShellBackOverlay.entries.forEach { overlay ->
            shellBranchStates().forEach { state ->
                block(state.copy(visibleOverlay = overlay))
            }
        }
    }

    /** 5 个布尔标志的全部组合。 */
    private fun combinations(): List<Pair<Boolean, BooleanArray>> {
        return listOf(false, true).flatMap { presentedFromMore ->
            (0 until 32).map { mask ->
                presentedFromMore to BooleanArray(5) { index -> (mask shr index) and 1 == 1 }
            }
        }
    }
}
