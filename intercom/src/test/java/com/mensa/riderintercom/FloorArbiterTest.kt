package com.mensa.riderintercom

import com.mensa.riderintercom.floor.FloorActions
import com.mensa.riderintercom.floor.FloorArbiter
import com.mensa.riderintercom.floor.FloorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorArbiterTest {

    private class Recorder : FloorActions {
        val taken = mutableListOf<Long>()
        var releases = 0
        var denials = 0
        var beeps = 0
        val states = mutableListOf<FloorState>()

        override fun sendFloorTaken(startedAtMicros: Long) { taken += startedAtMicros }
        override fun sendFloorRelease() { releases++ }
        override fun sendFloorDenied() { denials++ }
        override fun beep() { beeps++ }
        override fun onStateChanged(state: FloorState) { states += state }
    }

    private fun arbiter(sessionId: Long = 100L): Pair<FloorArbiter, Recorder> {
        val r = Recorder()
        return FloorArbiter(sessionId, r) to r
    }

    @Test
    fun `first speaker takes the floor and streams immediately`() {
        val (a, r) = arbiter()
        val t = 1_000_000L
        assertTrue("local speaker may stream at once", a.onVadOpen(t))
        assertEquals(FloorState.Talking, a.state.value)
        // Announced in the same breath, not after a grant round trip.
        assertEquals(listOf(t), r.taken)
    }

    @Test
    fun `a remote turn mutes this phone`() {
        val (a, _) = arbiter()
        a.onRemoteFloorTaken(5_000L, 200L, "Buddy")
        assertEquals(FloorState.Muted, a.state.value)
        a.onRemoteFloorRelease()
        assertEquals(FloorState.Idle, a.state.value)
    }

    @Test
    fun `talking while muted beeps locally and sends nothing`() {
        val (a, r) = arbiter()
        a.onRemoteFloorTaken(5_000L, 200L, "Buddy")
        assertFalse("muted mic must not stream", a.onVadOpen(6_000L))
        assertEquals(1, r.beeps)
        assertTrue("the beep is local only", r.taken.isEmpty())
        assertEquals(FloorState.Muted, a.state.value)
    }

    @Test
    fun `tie-break gives the floor to whoever started earlier`() {
        val (a, r) = arbiter()
        a.onVadOpen(1_000_000L)
        assertEquals(FloorState.Talking, a.state.value)

        // The buddy started 100 ms later: this phone keeps the floor and says so.
        a.onRemoteFloorTaken(1_100_000L, 200L, "Buddy")
        assertEquals(FloorState.Talking, a.state.value)
        assertEquals(1, r.denials)
    }

    @Test
    fun `tie-break yields when the other rider started earlier`() {
        val (a, _) = arbiter()
        a.onVadOpen(1_100_000L)
        assertEquals(FloorState.Talking, a.state.value)

        a.onRemoteFloorTaken(1_000_000L, 200L, "Buddy")
        assertEquals("earlier speaker wins", FloorState.Muted, a.state.value)
    }

    @Test
    fun `a dead heat is broken by the lower session id`() {
        // Local id 100 against remote 200: local wins a dead heat.
        val (low, lowRec) = arbiter(sessionId = 100L)
        low.onVadOpen(1_000_000L)
        low.onRemoteFloorTaken(1_000_005L, 200L, "Buddy") // 5 us apart
        assertEquals(FloorState.Talking, low.state.value)
        assertEquals(1, lowRec.denials)

        // Local id 300 against remote 200: local yields.
        val (high, _) = arbiter(sessionId = 300L)
        high.onVadOpen(1_000_000L)
        high.onRemoteFloorTaken(1_000_005L, 200L, "Buddy")
        assertEquals(FloorState.Muted, high.state.value)
    }

    @Test
    fun `the detector closing releases the floor`() {
        val (a, r) = arbiter()
        a.onVadOpen(1_000L)
        a.onVadClose()
        assertEquals(FloorState.Idle, a.state.value)
        assertEquals(1, r.releases)
    }

    @Test
    fun `push to talk overrides the detector and beats a remote turn`() {
        val (a, r) = arbiter()
        a.setPushToTalk(true, "Buddy")
        assertEquals(FloorState.Talking, a.state.value)
        assertTrue(a.pushToTalk)

        // A buddy whose detector opened earlier does not take a deliberate press away.
        a.onRemoteFloorTaken(1L, 200L, "Buddy")
        assertEquals(FloorState.Talking, a.state.value)
        assertEquals(1, r.denials)

        a.setPushToTalk(false, "Buddy")
        assertEquals(FloorState.Idle, a.state.value)
    }

    @Test
    fun `push to talk ignores the detector closing`() {
        val (a, r) = arbiter()
        a.setPushToTalk(true, null)
        a.onVadClose()
        assertEquals("a held button keeps the floor", FloorState.Talking, a.state.value)
        assertEquals(0, r.releases)
    }

    @Test
    fun `losing the tie-break stops this phone streaming`() {
        val (a, _) = arbiter()
        a.onVadOpen(1_000_000L)
        a.onRemoteFloorDenied("Buddy")
        assertEquals(FloorState.Muted, a.state.value)
    }

    @Test
    fun `a stuck detector cannot hold the channel for ever`() {
        val (a, r) = arbiter()
        a.onVadOpen(1_000L)
        assertEquals(FloorState.Talking, a.state.value)
        // Simulate the hold passing the 30 second safety valve.
        Thread.sleep(5)
        setHolderSince(a, System.currentTimeMillis() - FloorArbiter.MAX_HOLD_MS - 1)
        a.tick()
        assertEquals(FloorState.Idle, a.state.value)
        assertEquals(1, r.releases)
    }

    @Test
    fun `a holder whose link dropped mid-sentence frees the floor`() {
        val (a, _) = arbiter()
        a.onRemoteFloorTaken(1_000L, 200L, "Buddy")
        assertEquals(FloorState.Muted, a.state.value)
        setLastHolderAudio(a, System.currentTimeMillis() - FloorArbiter.HOLDER_STALE_MS - 1)
        a.tick()
        assertEquals("a silent holder must not block the channel", FloorState.Idle, a.state.value)
    }

    @Test
    fun `losing the link clears the floor`() {
        val (a, _) = arbiter()
        a.onRemoteFloorTaken(1_000L, 200L, "Buddy")
        a.onLinkLost()
        assertEquals(FloorState.Idle, a.state.value)
    }

    // The two watchdogs are driven by wall-clock fields; reach in rather than sleep 30 s.
    private fun setHolderSince(a: FloorArbiter, value: Long) =
        setField(a, "holderSince", value)

    private fun setLastHolderAudio(a: FloorArbiter, value: Long) =
        setField(a, "lastHolderAudioAt", value)

    private fun setField(target: Any, name: String, value: Long) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.setLong(target, value)
    }
}
