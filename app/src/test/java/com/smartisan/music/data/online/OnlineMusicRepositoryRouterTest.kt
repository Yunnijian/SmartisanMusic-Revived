package com.smartisan.music.data.online

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnlineMusicRepositoryRouter] 账号数据面（喜欢状态、我喜欢列表、歌单增删）的分派与状态透传单测。
 *
 * 注入点是 Router 主构造函数的 [OnlineAccountRepository]：账号域是唯一直接改用户账号数据的部分，
 * 真实实现必须登录联网才有结果，因此它在 Router 里单独抽成接口（其余方法仍依赖
 * [NeteaseOnlineMusicRepository] 具体类）。假实现记录调用参数并回放预设状态。
 *
 * 覆盖边界：
 * - 「未登录 / 凭据缺失」用真实仓库（`authStore = null`）覆盖：这条路径在仓库内本地短路，不发网络请求；
 * - [OnlineMusicRepositoryRouter.accountLikedTrackMediaItems] 的 MediaItem 映射依赖 `android.os.Bundle`，
 *   在 JVM 单测里是 AGP 的桩实现（调用即抛），所以只覆盖「失败 / 无内容一律降级为空列表」的语义，
 *   成功映射出的媒体项由设备端路径覆盖；
 * - Router 未暴露 `createAccountPlaylist`：「创建歌单」由云音乐页经
 *   [OnlineMusicRepositoryRouter.repositoryFor] 直连 [OnlineMusicProviderRepository] 调用，
 *   本文件只覆盖路由器自己实现的状态映射。
 */
class OnlineMusicRepositoryRouterTest {

    // ---------------------------------------------------------------- setTrackLiked

    @Test
    fun setTrackLikedForwardsTrackIdAndLikedFlag() = runBlocking {
        val account = FakeAccountRepository()
        val router = routerWith(account)

        val result = router.setTrackLiked(identity("2001"), liked = true)

        assertEquals(
            "应把 trackId 与目标状态原样交给账号仓库",
            listOf("2001" to true),
            account.setTrackLikedCalls,
        )
        assertEquals("成功状态应透传", NeteaseAccountActionStatus.Success, result.status)
    }

    @Test
    fun setTrackLikedPassesEveryRepositoryStatusThrough() = runBlocking {
        val repositoryResults = listOf(
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Success),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 400),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301),
        )

        repositoryResults.forEach { repositoryResult ->
            val account = FakeAccountRepository().apply { setTrackLikedResult = repositoryResult }

            val result = routerWith(account).setTrackLiked(identity("2001"), liked = false)

            assertEquals("仓库状态应原样透传（$repositoryResult）", repositoryResult, result)
        }
    }

    @Test
    fun setTrackLikedForOtherSourceFailsWithoutCallingAccountRepository() = runBlocking {
        val account = FakeAccountRepository()
        val router = routerWith(account)

        val result = router.setTrackLiked(identity("9001", source = "qq"), liked = true)

        assertEquals("非网易云来源应直接判失败", NeteaseAccountActionStatus.Failed, result.status)
        assertTrue("不应把其他来源的 trackId 交给账号仓库", account.setTrackLikedCalls.isEmpty())
    }

    @Test
    fun setTrackLikedPropagatesRepositoryException() {
        val account = FakeAccountRepository().apply { failure = IOException("network down") }
        val router = routerWith(account)

        assertThrows(
            "写操作异常应上抛给调用方决定提示，路由层不得静默吞掉",
            IOException::class.java,
        ) {
            runBlocking { router.setTrackLiked(identity("2001"), liked = true) }
        }
    }

    @Test
    fun setTrackLikedRethrowsCancellation() {
        val account = FakeAccountRepository().apply { failure = CancellationException("cancelled") }
        val router = routerWith(account)

        assertThrows(CancellationException::class.java) {
            runBlocking { router.setTrackLiked(identity("2001"), liked = true) }
        }
    }

    // ------------------------------------------------------- accountLikedTrackIds

    @Test
    fun accountLikedTrackIdsForwardsRepositoryValue() = runBlocking {
        val account = FakeAccountRepository().apply { likedTrackIds = setOf("2001", "2002") }

        val ids = routerWith(account).accountLikedTrackIds()

        assertEquals("应原样返回仓库给出的 id 集合", setOf("2001", "2002"), ids)
        assertEquals("应只向仓库取一次", 1, account.likedTrackIdCalls)
    }

    @Test
    fun accountLikedTrackIdsReturnsNullWhenRepositoryIsNotLoggedIn() = runBlocking {
        val account = FakeAccountRepository().apply { likedTrackIds = null }

        val ids = routerWith(account).accountLikedTrackIds()

        assertNull("未登录时仓库返回 null，路由层不得改写成空集合", ids)
    }

    @Test
    fun accountLikedTrackIdsReturnsNullWhenRepositoryThrows() = runBlocking {
        val account = FakeAccountRepository().apply { failure = IOException("network down") }

        val ids = routerWith(account).accountLikedTrackIds()

        assertNull("拉取失败应按「无云端我喜欢」降级为 null", ids)
        assertEquals("失败也要先向仓库发起读取", 1, account.likedTrackIdCalls)
    }

    @Test
    fun accountLikedTrackIdsRethrowsCancellation() {
        val account = FakeAccountRepository().apply { failure = CancellationException("cancelled") }

        assertThrows(
            "取消信号不能降级成 null，否则协程取消会被吞掉",
            CancellationException::class.java,
        ) {
            runBlocking { routerWith(account).accountLikedTrackIds() }
        }
    }

    // ------------------------------------------------- accountLikedTrackMediaItems

    @Test
    fun accountLikedTrackMediaItemsReturnsEmptyListWhenRepositoryHasNoLikedTracks() = runBlocking {
        val account = FakeAccountRepository().apply { likedTracks = null }

        val items = routerWith(account).accountLikedTrackMediaItems()

        assertTrue("未登录 / 无「我喜欢」歌单时应返回空列表", items.isEmpty())
        assertEquals("应只向仓库取一次", 1, account.likedTrackCalls)
    }

    @Test
    fun accountLikedTrackMediaItemsReturnsEmptyListWhenRepositoryHasEmptyTracks() = runBlocking {
        val account = FakeAccountRepository().apply { likedTracks = emptyList() }

        assertTrue(routerWith(account).accountLikedTrackMediaItems().isEmpty())
    }

    @Test
    fun accountLikedTrackMediaItemsReturnsEmptyListWhenRepositoryThrows() = runBlocking {
        val account = FakeAccountRepository().apply { failure = IOException("network down") }

        val items = routerWith(account).accountLikedTrackMediaItems()

        assertTrue("拉取失败应降级为空列表（与「确实没有内容」不可区分）", items.isEmpty())
    }

    @Test
    fun accountLikedTrackMediaItemsDegradesToEmptyListWhenMappingFails() = runBlocking {
        // 非空结果在 JVM 单测里必然映射失败：OnlineTrack.toMediaItem() 第一句就要构造 android.os.Bundle，
        // 而 AGP 单测用的是桩 android.jar（调用即抛）。这里断言的是 Router 的隔离契约：
        // 映射期异常不得外泄，而是按文档降级为空列表。
        val account = FakeAccountRepository().apply { likedTracks = listOf(sampleTrack("2001")) }

        val items = routerWith(account).accountLikedTrackMediaItems()

        assertTrue("映射失败应降级为空列表而不是抛异常", items.isEmpty())
        assertEquals("降级前应确实向仓库取过我喜欢列表", 1, account.likedTrackCalls)
    }

    @Test
    fun accountLikedTrackMediaItemsRethrowsCancellation() {
        val account = FakeAccountRepository().apply { failure = CancellationException("cancelled") }

        assertThrows(CancellationException::class.java) {
            runBlocking { routerWith(account).accountLikedTrackMediaItems() }
        }
    }

    // ------------------------------------------------------- 歌单增删（账号歌单）

    @Test
    fun addTracksToAccountPlaylistForwardsOnlyMatchingTrackIds() = runBlocking {
        val account = FakeAccountRepository()
        val target = playlist("playlist-1")

        val result = routerWith(account).addTracksToAccountPlaylist(
            playlist = target,
            identities = listOf(
                identity("2001"),
                identity("9001", source = "qq"),
                identity("2001"),
                identity("   "),
                identity("2002"),
            ),
        )

        assertEquals("成功状态应透传", NeteaseAccountActionStatus.Success, result.status)
        assertEquals(
            "应只转发同来源、非空白、去重且保持顺序的 trackId",
            listOf(target to listOf("2001", "2002")),
            account.addCalls,
        )
    }

    @Test
    fun addTracksToAccountPlaylistFailsWithoutRepositoryCallWhenNoTrackIdMatches() = runBlocking {
        val account = FakeAccountRepository()

        val result = routerWith(account).addTracksToAccountPlaylist(
            playlist = playlist("playlist-1"),
            identities = listOf(identity("9001", source = "qq"), identity("   ")),
        )

        assertEquals("没有可写入的曲目应直接判失败", NeteaseAccountActionStatus.Failed, result.status)
        assertTrue("空 trackId 列表不应发起账号写入", account.addCalls.isEmpty())
    }

    @Test
    fun addTracksToAccountPlaylistPassesEveryRepositoryStatusThrough() = runBlocking {
        listOf(
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Success),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 403),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301),
        ).forEach { repositoryResult ->
            val account = FakeAccountRepository().apply { addResult = repositoryResult }

            val result = routerWith(account).addTracksToAccountPlaylist(
                playlist = playlist("playlist-1"),
                identities = listOf(identity("2001")),
            )

            assertEquals("仓库状态应原样透传（$repositoryResult）", repositoryResult, result)
        }
    }

    @Test
    fun removeTracksFromAccountPlaylistForwardsOnlyMatchingTrackIds() = runBlocking {
        val account = FakeAccountRepository()
        val target = playlist("playlist-1")

        val result = routerWith(account).removeTracksFromAccountPlaylist(
            playlist = target,
            identities = listOf(
                identity("2002"),
                identity("2001", source = "qq"),
                identity("2002"),
                identity("  "),
                identity("2003"),
            ),
        )

        assertEquals("成功状态应透传", NeteaseAccountActionStatus.Success, result.status)
        assertEquals(
            "应只转发同来源、非空白、去重且保持顺序的 trackId",
            listOf(target to listOf("2002", "2003")),
            account.removeCalls,
        )
    }

    @Test
    fun removeTracksFromAccountPlaylistFailsWithoutRepositoryCallWhenNoTrackIdMatches() = runBlocking {
        val account = FakeAccountRepository()

        val result = routerWith(account).removeTracksFromAccountPlaylist(
            playlist = playlist("playlist-1"),
            identities = emptyList(),
        )

        assertEquals("没有可移除的曲目应直接判失败", NeteaseAccountActionStatus.Failed, result.status)
        assertTrue("空 trackId 列表不应发起账号写入", account.removeCalls.isEmpty())
    }

    @Test
    fun removeTracksFromAccountPlaylistPassesEveryRepositoryStatusThrough() = runBlocking {
        listOf(
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Success),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 403),
            NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301),
        ).forEach { repositoryResult ->
            val account = FakeAccountRepository().apply { removeResult = repositoryResult }

            val result = routerWith(account).removeTracksFromAccountPlaylist(
                playlist = playlist("playlist-1"),
                identities = listOf(identity("2001")),
            )

            assertEquals("仓库状态应原样透传（$repositoryResult）", repositoryResult, result)
        }
    }

    @Test
    fun deleteAccountPlaylistForwardsPlaylistAndStatus() = runBlocking {
        val account = FakeAccountRepository().apply {
            deleteResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success, code = 200)
        }
        val target = playlist("playlist-9")

        val result = routerWith(account).deleteAccountPlaylist(target)

        assertEquals("应把目标歌单原样交给账号仓库", listOf(target), account.deleteCalls)
        assertEquals(
            "仓库状态应原样透传",
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Success, code = 200),
            result,
        )
    }

    @Test
    fun deleteAccountPlaylistPassesFailureStatusThrough() = runBlocking {
        val account = FakeAccountRepository().apply {
            deleteResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 403)
        }

        val result = routerWith(account).deleteAccountPlaylist(playlist("playlist-9"))

        assertEquals(
            "删除失败的状态与业务码都应原样透传",
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 403),
            result,
        )
    }

    // ------------------------------------------------------------ 未登录（凭据缺失）

    @Test
    fun unauthenticatedSetTrackLikedReturnsRequiresLogin() = runBlocking {
        val router = unauthenticatedRouter()

        val result = router.setTrackLiked(identity("2001"), liked = true)

        assertEquals(
            "未登录时写喜欢应返回需登录",
            NeteaseAccountActionStatus.RequiresLogin,
            result.status,
        )
        assertEquals("需登录必须带 301 业务码（调用方据此拉起登录页）", 301, result.code)
    }

    @Test
    fun unauthenticatedAccountLikedReadsDegradeWithoutNetwork() = runBlocking {
        val router = unauthenticatedRouter()

        assertNull("未登录时「我喜欢」id 集合为 null", router.accountLikedTrackIds())
        assertTrue("未登录时「我喜欢」媒体项为空列表", router.accountLikedTrackMediaItems().isEmpty())
    }

    @Test
    fun unauthenticatedEditablePlaylistWritesReturnRequiresLogin() = runBlocking {
        val router = unauthenticatedRouter()
        val editable = playlist("playlist-1", isEditable = true)
        val identities = listOf(identity("2001"))

        assertEquals(
            "未登录时添加歌曲应返回需登录",
            NeteaseAccountActionStatus.RequiresLogin,
            router.addTracksToAccountPlaylist(editable, identities).status,
        )
        assertEquals(
            "未登录时移除歌曲应返回需登录",
            NeteaseAccountActionStatus.RequiresLogin,
            router.removeTracksFromAccountPlaylist(editable, identities).status,
        )
        assertEquals(
            "未登录时删除歌单应返回需登录",
            NeteaseAccountActionStatus.RequiresLogin,
            router.deleteAccountPlaylist(editable).status,
        )
    }

    @Test
    fun nonEditablePlaylistWritesFailOnProviderGuard() = runBlocking {
        val router = unauthenticatedRouter()
        val readOnly = playlist("playlist-2", isEditable = false)
        val identities = listOf(identity("2001"))

        assertEquals(
            "不可编辑歌单应直接判失败，而不是提示去登录",
            NeteaseAccountActionStatus.Failed,
            router.addTracksToAccountPlaylist(readOnly, identities).status,
        )
        assertEquals(
            NeteaseAccountActionStatus.Failed,
            router.removeTracksFromAccountPlaylist(readOnly, identities).status,
        )
        assertEquals(
            NeteaseAccountActionStatus.Failed,
            router.deleteAccountPlaylist(readOnly).status,
        )
    }

    @Test
    fun repositoryForReturnsInjectedNeteaseRepository() {
        val repository = NeteaseOnlineMusicRepository()
        val router = OnlineMusicRepositoryRouter(neteaseRepository = repository)

        assertSame(
            "网易云来源应解析到注入的仓库实例",
            repository,
            router.repositoryFor(OnlineMusicProvider.Netease),
        )
    }

    // --------------------------------------------------------------------- 工具

    private fun routerWith(account: OnlineAccountRepository): OnlineMusicRepositoryRouter {
        return OnlineMusicRepositoryRouter(
            neteaseRepository = NeteaseOnlineMusicRepository(),
            accountRepository = account,
        )
    }

    /** 未登录路由器：仓库无 [NeteaseAuthStore]（凭据缺失），账号域一律在本地短路，不发网络请求。 */
    private fun unauthenticatedRouter(): OnlineMusicRepositoryRouter {
        return OnlineMusicRepositoryRouter(neteaseRepository = NeteaseOnlineMusicRepository())
    }

    private fun identity(
        trackId: String,
        source: String = OnlineMusicProvider.Netease.sourceId,
    ): OnlineTrackIdentity {
        return OnlineTrackIdentity(source = source, trackId = trackId)
    }

    private fun playlist(
        playlistId: String,
        isEditable: Boolean = true,
    ): OnlineAccountPlaylist {
        return OnlineAccountPlaylist(
            provider = OnlineMusicProvider.Netease,
            playlistId = playlistId,
            title = "测试歌单",
            trackCount = 0,
            isEditable = isEditable,
        )
    }

    private fun sampleTrack(trackId: String): OnlineTrack {
        return OnlineTrack(
            source = OnlineMusicProvider.Netease.sourceId,
            trackId = trackId,
            title = "歌曲 $trackId",
            artist = "艺术家",
            album = "专辑",
            durationMs = 180_000L,
            artworkUrl = null,
        )
    }
}

/**
 * 手写假实现：记录调用参数，回放预设状态，可配置一次失败（含 [CancellationException]）。
 *
 * 先记录再抛：失败用例也要能断言「路由器确实把请求转发出去了」。
 */
private class FakeAccountRepository : OnlineAccountRepository {

    val setTrackLikedCalls = mutableListOf<Pair<String, Boolean>>()
    val addCalls = mutableListOf<Pair<OnlineAccountPlaylist, List<String>>>()
    val removeCalls = mutableListOf<Pair<OnlineAccountPlaylist, List<String>>>()
    val deleteCalls = mutableListOf<OnlineAccountPlaylist>()
    var likedTrackIdCalls = 0
    var likedTrackCalls = 0

    var setTrackLikedResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    var likedTrackIds: Set<String>? = emptySet()
    var likedTracks: List<OnlineTrack>? = emptyList()
    var addResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    var removeResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    var deleteResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    var failure: Throwable? = null

    override suspend fun setTrackLiked(trackId: String, liked: Boolean): NeteaseAccountActionResult {
        setTrackLikedCalls += trackId to liked
        failIfConfigured()
        return setTrackLikedResult
    }

    override suspend fun accountLikedTrackIds(): Set<String>? {
        likedTrackIdCalls += 1
        failIfConfigured()
        return likedTrackIds
    }

    override suspend fun currentUserLikedTracks(): List<OnlineTrack>? {
        likedTrackCalls += 1
        failIfConfigured()
        return likedTracks
    }

    override suspend fun addTracksToAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        addCalls += playlist to trackIds
        failIfConfigured()
        return addResult
    }

    override suspend fun removeTracksFromAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        removeCalls += playlist to trackIds
        failIfConfigured()
        return removeResult
    }

    override suspend fun deleteAccountPlaylist(
        playlist: OnlineAccountPlaylist,
    ): NeteaseAccountActionResult {
        deleteCalls += playlist
        failIfConfigured()
        return deleteResult
    }

    private fun failIfConfigured() {
        failure?.let { throw it }
    }
}
