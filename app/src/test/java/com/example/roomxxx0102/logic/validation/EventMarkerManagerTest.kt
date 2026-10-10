package com.example.roomxxx0102.logic.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不需要播放器、房间算法或 Android UI：验证事件真值与选择状态的独立生命周期。
 * Manager 不调用 init()，因此不会读写磁盘，也不会调用 Android 框架方法。
 */
class EventMarkerManagerTest {
    @Test fun sameExternalVideoAcceptsDocumentAndMediaStoreUris() {
        val manager = EventMarkerManager()
        val document = "uri:content://com.android.providers.media.documents/document/video%3A1000012482"
        assertTrue(manager.isSameVideoKey(document, "uri:content://media/external/video/media/1000012482"))
        assertTrue(manager.isSameVideoKey(document, "uri:content://media/external_primary/video/media/1000012482"))
        assertFalse(manager.isSameVideoKey(document, "uri:content://media/external/video/media/1000012483"))
        assertFalse(manager.isSameVideoKey(document, "uri:content://media/internal/video/media/1000012482"))
        assertFalse(manager.isSameVideoKey("file:/one/video.mp4", "file:/two/video.mp4"))
    }

    @Test fun selectionKeepsEventIdentityAndSupportsReplaceAndClear() {
        val manager = EventMarkerManager()
        manager.bindVideo("file:/tests/a/video.mp4")
        assertEquals(EventMarkerManager.AddResult.ADDED, manager.addEvent(EventType.ENTER, 20, 1000))
        assertEquals(EventMarkerManager.AddResult.ADDED, manager.addEvent(EventType.EXIT, 40, 2000))
        assertEquals(EventMarkerManager.AddResult.ADDED, manager.addEvent(EventType.ENTER, 60, 3000))

        val event = manager.getEvents()[1]
        MarkedEventPortalBinding.select(event)
        assertEquals(1, MarkedEventPortalBinding.selectedIndex())
        assertEquals("room-a", MarkedEventPortalBinding.bindSelectedPortal("room-a")?.portalRoomId)
        assertEquals(1, MarkedEventPortalBinding.selectedIndex())
        assertEquals("room-b", MarkedEventPortalBinding.bindSelectedPortal("room-b")?.portalRoomId)
        assertNull(MarkedEventPortalBinding.bindSelectedPortal(null)?.portalRoomId)
        assertNull(manager.getEvents()[1].portalRoomId)
        assertEquals(1, MarkedEventPortalBinding.selectedIndex())
        MarkedEventPortalBinding.clearSelection()
        assertNull(MarkedEventPortalBinding.selectedEvent())
        assertNull(MarkedEventPortalBinding.selectedIndex())
    }

    @Test fun deletingSelectedEventInvalidatesSelection() {
        val manager = EventMarkerManager()
        manager.bindVideo("file:/tests/b/video.mp4")
        manager.addEvent(EventType.ENTER, 20, 1000)
        MarkedEventPortalBinding.select(manager.getEvents()[0])
        assertEquals(0, MarkedEventPortalBinding.selectedIndex())
        assertEquals(1, manager.removeEventsNearFrame(20, toleranceFrames = 0).size)
        assertNull(MarkedEventPortalBinding.selectedEvent())
    }

    @Test fun differentPathsWithSameFilenameNeverShareMarkerStorage() {
        val manager = EventMarkerManager()
        val a = manager.eventStorageName("file:/storage/one/test_video.mp4")
        val b = manager.eventStorageName("file:/storage/two/test_video.mp4")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("test_video.mp4."))
        assertTrue(b.startsWith("test_video.mp4."))
        assertTrue(a.endsWith(".events.json"))
    }

    @Test fun webCanChangeDirectionAndUndoViaExactEventMethods() {
        val manager = EventMarkerManager()
        manager.bindVideo("file:/tests/web/video.mp4")
        manager.addEvent(EventType.ENTER, 15, 500)
        val original = manager.getEvents().single()
        val outbound = manager.changeExactEventType(original, EventType.EXIT)
        assertEquals(EventType.EXIT, outbound?.type)
        assertEquals(EventType.EXIT, MarkedEventPortalBinding.selectedEvent()?.type)
        assertEquals(EventType.ENTER, manager.changeExactEventType(outbound!!, EventType.ENTER)?.type)
    }

    @Test fun webDeletesOnlyOneEventAndCanRestoreIt() {
        val manager = EventMarkerManager()
        manager.bindVideo("file:/tests/web2/video.mp4")
        manager.addEvent(EventType.ENTER, 25, 1000)
        manager.addEvent(EventType.EXIT, 25, 1000)
        val before = manager.getEvents()
        val target = before.first { it.type == EventType.ENTER }
        assertEquals(target, manager.removeExactEvent(target))
        assertEquals(listOf(EventType.EXIT), manager.getEvents().map { it.type })
        assertTrue(manager.restoreExactEvent(target))
        assertEquals(2, manager.getEvents().size)
    }

    @Test fun switchingVideoClearsActiveSelection() {
        val manager = EventMarkerManager()
        manager.bindVideo("file:/tests/first/video.mp4")
        manager.addEvent(EventType.ENTER, 1, 1234)
        MarkedEventPortalBinding.select(manager.getEvents()[0])
        manager.bindVideo("file:/tests/second/video.mp4")
        assertNull(MarkedEventPortalBinding.selectedEvent())
    }
}
