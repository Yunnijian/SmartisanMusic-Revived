package com.smartisan.music.listentogether

import com.smartisan.music.data.online.NeteaseAccountProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListenTogetherStore] 的会话级回归测试：入房必须由用户确认、退出/补拉等主线行为真的跑起来。
 *
 * 这一层守的是「把修复改回缺陷，测试必须变红」：
 * - 深链静默入房（旧 joinRoomFromUrl 行为）→ [offerJoinFromUrlRegistersPendingInviteWithoutJoining] 红；
 * - 深链入口里顺手 confirm 一次 → [deepLinkDispatchOnlyRegistersPendingInvite] 红；
 * - 入房崩溃/不补 selfId → [joinBackfillsSelfUserIdOnLaterStatusRefreshWhenProfileFails] 红；
 * - 退出后轮询不停 → [leavingRoomEndsServerRoomAndStopsPolling] 红；
 * - 取消被吞掉 → [cancellationFromPollingIsRethrownAndStopsPolling] 红。
 */
class ListenTogetherStoreJoinTest {

    /** 邀请链接用生产构造器生成，避免测试里手写字符串与真实格式漂移。 */
    private val inviteUrl = buildListenTogetherInviteUrl(roomId = "room-1", inviterId = "42")

    /** 假接口与 Store 共用同一个虚拟时钟：时间只由测试拨动。 */
    private fun joinFixture(): Pair<FakeListenTogetherApi, ListenTogetherStore> {
        val clock = VirtualClock()
        val api = FakeListenTogetherApi(clock)
        return api to listenTogetherStore(api, clock)
    }

    @Test
    fun offerJoinFromUrlRegistersPendingInviteWithoutJoining() = runBlocking {
        val (api, store) = joinFixture()

        store.offerJoinFromUrl(inviteUrl)

        assertEquals(
            "有效邀请链接应挂成待确认邀请",
            ListenTogetherInvite(roomId = "room-1", inviterId = "42"),
            store.pendingInvite.value,
        )
        assertNull("只登记邀请时还没入房，roomId 必须为空", store.state.value.roomId)
        assertEquals(
            ListenTogetherConnectionState.Disconnected,
            store.state.value.connectionState,
        )
        assertTrue(
            "登记邀请不得发起任何入房请求：深链任意网页可拉起，静默入房等于交出播放控制权",
            api.joinCalls.isEmpty(),
        )
        assertEquals("登记邀请不该建房", 0, api.createCount.get())
    }

    @Test
    fun confirmPendingInviteJoinsOfferedRoomExactlyOnce() = runBlocking {
        val (api, store) = joinFixture()
        store.offerJoinFromUrl(inviteUrl)

        store.confirmPendingInvite()

        assertEquals(
            "确认后应恰好入房一次，并把邀请里的 roomId/inviterId 原样带上",
            listOf("room-1" to "42"),
            api.joinCalls,
        )
        assertEquals("room-1", store.state.value.roomId)
        assertEquals(ListenTogetherConnectionState.Connected, store.state.value.connectionState)
        assertNull("确认后待确认邀请应清空", store.pendingInvite.value)

        store.confirmPendingInvite()
        assertEquals("没有待确认邀请时再点确认不得重复入房", 1, api.joinCalls.size)
    }

    @Test
    fun dismissPendingInviteNeverJoins() = runBlocking {
        val (api, store) = joinFixture()
        store.offerJoinFromUrl(inviteUrl)

        store.dismissPendingInvite()

        assertNull("忽略邀请后待确认邀请应清空", store.pendingInvite.value)
        assertTrue("忽略邀请不得发起入房请求", api.joinCalls.isEmpty())

        store.confirmPendingInvite()
        assertTrue("忽略之后即使再收到确认也不得入房", api.joinCalls.isEmpty())
        assertNull(store.state.value.roomId)
    }

    @Test
    fun deepLinkDispatchOnlyRegistersPendingInvite() = runBlocking {
        val (api, store) = joinFixture()

        dispatchListenTogetherInviteDeepLink(store, inviteUrl)

        assertEquals(
            "深链入口只能挂起待确认邀请（MainActivity 走的就是这个函数）",
            ListenTogetherInvite(roomId = "room-1", inviterId = "42"),
            store.pendingInvite.value,
        )
        assertTrue("深链入口绝不能自己入房", api.joinCalls.isEmpty())
        assertNull(store.state.value.roomId)
    }

    @Test
    fun cancellationFromPollingIsRethrownAndStopsPolling() = runBlocking {
        val (api, store) = joinFixture()
        api.syncFailure = CancellationException("scope cancelled")
        store.offerJoinFromUrl(inviteUrl)

        store.confirmPendingInvite()

        // 入房那一轮 sync 就吃到取消：取消信号必须原样上抛（不能降级成「失败结果」），
        // 否则轮询协程不会结束，退出/取消之后还在继续发请求。
        awaitUntil("入房那一轮轮询应已发出并吃到取消") { api.syncCount.get() >= 1 }
        val syncsAfterCancellation = api.syncCount.get()
        Thread.sleep(100L)
        assertEquals(
            "取消被吞掉的话轮询会继续跑；取消后不应再有任何 sync（取消时 $syncsAfterCancellation 次）",
            syncsAfterCancellation,
            api.syncCount.get(),
        )
    }

    @Test
    fun joinBackfillsSelfUserIdOnLaterStatusRefreshWhenProfileFails() = runBlocking {
        val (api, store) = joinFixture()
        // 入房与首轮状态刷新各失败一次：selfId 先空着，靠后续状态刷新补回来。
        api.profileFailuresBeforeSuccess = 2
        api.profile = NeteaseAccountProfile(userId = 42L, nickname = "自己", avatarUrl = null)
        api.joinedRoom = listenTogetherRoom(creatorId = 99L, memberIds = listOf(99L, 42L, 7L))
        store.offerJoinFromUrl(inviteUrl)

        store.confirmPendingInvite()

        assertEquals("前置条件：应已入房", "room-1", store.state.value.roomId)
        assertNull(
            "资料接口失败 + 三人房认不出自己：selfId 先空着，回声交给上报窗口兜底",
            store.state.value.selfUserId,
        )

        awaitUntil("状态刷新应通过补拉把 selfId 补回来") {
            store.state.value.selfUserId == 42L
        }

        assertFalse("房主是 99 不是自己", store.state.value.isHost)
        assertEquals("补回 selfId 后应能认出对面成员", 99L, store.state.value.otherMember?.userId)

        awaitUntil("补回 selfId 后应能拉到双方累计时长") { api.statisticsCalls.isNotEmpty() }
        assertEquals(
            "不是房主时房间双方顺序是「对方在前」",
            listOf(99L, 42L),
            api.statisticsCalls.first(),
        )
    }

    @Test
    fun leavingRoomEndsServerRoomAndStopsPolling() = runBlocking {
        val (api, store) = joinFixture()
        store.offerJoinFromUrl(inviteUrl)
        store.confirmPendingInvite()

        store.leaveRoom()

        assertEquals("退出应向服务端结束房间一次", listOf("room-1"), api.endCalls)
        assertNull(store.state.value.roomId)
        assertEquals(
            ListenTogetherConnectionState.Disconnected,
            store.state.value.connectionState,
        )

        // 快照在退出之后取：退出前已经启动的那一轮 sync 不算「退出后还在轮询」。
        val syncsAfterLeave = api.syncCount.get()
        Thread.sleep(100L)
        assertEquals(
            "退出后轮询必须停：不再有 sync 请求（退出时 $syncsAfterLeave 次）",
            syncsAfterLeave,
            api.syncCount.get(),
        )
    }
}
