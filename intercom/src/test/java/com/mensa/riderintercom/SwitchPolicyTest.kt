package com.mensa.riderintercom

import com.mensa.riderintercom.transport.SwitchPolicy
import com.mensa.riderintercom.transport.SwitchReason
import com.mensa.riderintercom.transport.Transport
import com.mensa.riderintercom.util.TransportPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that decide when the app changes radio behind the rider's back. These matter
 * more than most logic here: a wrong decision either strands a rider on a dying link or
 * flaps between radios all ride.
 */
class SwitchPolicyTest {

    private fun policy(pref: TransportPreference = TransportPreference.Auto) = SwitchPolicy(pref)

    private fun started(pref: TransportPreference = TransportPreference.Auto): SwitchPolicy {
        val p = policy(pref)
        p.onTransportStarted(0, p.initialTransport())
        return p
    }

    // ------------------------------------------------------------- starting point

    @Test
    fun `auto and bluetooth start on bluetooth, range starts on wifi direct`() {
        assertEquals(Transport.Bluetooth, policy(TransportPreference.Auto).initialTransport())
        assertEquals(Transport.Bluetooth, policy(TransportPreference.Bluetooth).initialTransport())
        assertEquals(Transport.WifiDirect, policy(TransportPreference.Range).initialTransport())
    }

    // ------------------------------------------------------------------ the loss rule

    @Test
    fun `a brief loss spike does not move the rider`() {
        val p = started()
        p.onConnected(0)
        // Five seconds of bad loss, then it clears. Half the window: stay put.
        for (t in 1000L..5000L step 1000) {
            assertNull(p.evaluate(t, Transport.Bluetooth, connected = true, lossPercent = 12.0))
        }
        assertNull(p.evaluate(6000, Transport.Bluetooth, connected = true, lossPercent = 0.5))
        // And the window restarts, so the next spike needs its own ten seconds.
        for (t in 7000L..15000L step 1000) {
            assertNull(p.evaluate(t, Transport.Bluetooth, connected = true, lossPercent = 12.0))
        }
    }

    @Test
    fun `sustained loss moves to wifi direct`() {
        val p = started()
        p.onConnected(0)
        var decision = p.evaluate(1000, Transport.Bluetooth, true, 9.0)
        assertNull("not yet - the window has not elapsed", decision)
        for (t in 2000L..11_000L step 1000) {
            decision = p.evaluate(t, Transport.Bluetooth, true, 9.0)
        }
        assertNotNull("ten seconds of high loss must move the rider", decision)
        assertEquals(Transport.WifiDirect, decision!!.target)
        assertEquals(SwitchReason.Loss, decision.reason)
    }

    @Test
    fun `loss just under the threshold is tolerated indefinitely`() {
        val p = started()
        p.onConnected(0)
        var decision = p.evaluate(0, Transport.Bluetooth, true, 4.9)
        for (t in 1000L..60_000L step 1000) {
            decision = p.evaluate(t, Transport.Bluetooth, true, 4.9)
        }
        assertNull("4.9% is under the 5% bar; a minute of it is still fine", decision)
    }

    // ------------------------------------------------------------ the dead-link rule

    @Test
    fun `a link that stays down moves to the other radio`() {
        val p = started()
        p.onConnected(0)
        p.onDisconnected(1000)
        assertNull(p.evaluate(10_000, Transport.Bluetooth, connected = false, lossPercent = 0.0))
        val decision = p.evaluate(22_000, Transport.Bluetooth, connected = false, lossPercent = 0.0)
        assertNotNull("20 s of reconnect backoff is enough", decision)
        assertEquals(Transport.WifiDirect, decision!!.target)
        assertEquals(SwitchReason.Unreachable, decision.reason)
    }

    @Test
    fun `a brief dropout reconnects rather than switching`() {
        val p = started()
        p.onConnected(0)
        p.onDisconnected(1000)
        assertNull(p.evaluate(5000, Transport.Bluetooth, false, 0.0))
        p.onConnected(6000)
        // Back up; the clock must have been cleared, so a later check is quiet.
        assertNull(p.evaluate(30_000, Transport.Bluetooth, true, 0.0))
    }

    // --------------------------------------------------------- wifi direct fallback

    @Test
    fun `wifi direct that never forms a group falls back to bluetooth`() {
        val p = SwitchPolicy(TransportPreference.Range)
        p.onTransportStarted(0, Transport.WifiDirect)
        assertNull(p.evaluate(20_000, Transport.WifiDirect, connected = false, lossPercent = 0.0))
        val decision = p.evaluate(31_000, Transport.WifiDirect, connected = false, lossPercent = 0.0)
        assertNotNull("30 s without a group is the documented giving-up point", decision)
        assertEquals(Transport.Bluetooth, decision!!.target)
        assertEquals(SwitchReason.GroupTimeout, decision.reason)
    }

    @Test
    fun `wifi direct that did connect once is not blamed for a later drop`() {
        val p = SwitchPolicy(TransportPreference.Range)
        p.onTransportStarted(0, Transport.WifiDirect)
        p.onConnected(5_000)
        p.onDisconnected(6_000)
        val decision = p.evaluate(31_000, Transport.WifiDirect, false, 0.0)
        // It is Unreachable, not GroupTimeout: the group did form, the link died later.
        assertEquals(SwitchReason.Unreachable, decision?.reason)
    }

    // ------------------------------------------------------------------- the guards

    @Test
    fun `a pinned bluetooth preference is never overridden`() {
        val p = SwitchPolicy(TransportPreference.Bluetooth)
        p.onTransportStarted(0, Transport.Bluetooth)
        p.onConnected(0)
        var decision = p.evaluate(0, Transport.Bluetooth, true, 50.0)
        for (t in 1000L..60_000L step 1000) {
            decision = p.evaluate(t, Transport.Bluetooth, true, 50.0)
        }
        assertNull("the rider chose Bluetooth; respect it", decision)
    }

    @Test
    fun `it will not flap - a switch is followed by a dwell period`() {
        val p = started()
        p.onConnected(0)
        var decision: Any? = null
        for (t in 1000L..11_000L step 1000) decision = p.evaluate(t, Transport.Bluetooth, true, 20.0)
        assertNotNull(decision)
        p.onSwitched(11_000, Transport.WifiDirect)
        p.onConnected(11_500)

        // Immediately bad again on the new radio, but the dwell timer holds it.
        var again: Any? = null
        for (t in 12_000L..25_000L step 1000) {
            again = p.evaluate(t, Transport.WifiDirect, true, 20.0)
        }
        assertNull("must not bounce straight back", again)
    }

    @Test
    fun `switching is capped so a bad ride cannot thrash all day`() {
        val p = started()
        var at = 0L
        var transport = Transport.Bluetooth
        var moves = 0
        p.onConnected(at)
        // Two hours of relentlessly bad loss on whichever radio is in use.
        repeat(7200) {
            at += 1000
            val d = p.evaluate(at, transport, connected = true, lossPercent = 30.0)
            if (d != null) {
                transport = d.target
                p.onSwitched(at, transport)
                p.onConnected(at)
                moves++
            }
        }
        assertEquals("the cap must stop it at six", 6, moves)
    }
}
