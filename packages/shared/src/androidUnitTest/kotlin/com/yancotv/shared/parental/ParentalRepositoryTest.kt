package com.yancotv.shared.parental

import com.yancotv.shared.sources.testDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentalRepositoryTest {
    @Test fun hashAndVerifyRoundtrips() =
        runTest {
            val h: PinHasher = AndroidPinHasher()
            val encoded = h.hash("1234")
            assertTrue(encoded.startsWith("pbkdf2:"))
            assertTrue(h.verify("1234", encoded).ok)
            assertFalse(h.verify("1235", encoded).ok)
        }

    @Test fun differentPinsProduceDifferentHashes() =
        runTest {
            val h: PinHasher = AndroidPinHasher()
            val a = h.hash("1234")
            val b = h.hash("1234") // same PIN, fresh salt
            // Different salts → different encoded strings even for the same PIN.
            assert(a != b)
            // Both must still verify.
            assertTrue(h.verify("1234", a).ok)
            assertTrue(h.verify("1234", b).ok)
        }

    @Test fun malformedHashFailsSafely() =
        runTest {
            val h: PinHasher = AndroidPinHasher()
            assertFalse(h.verify("1234", "").ok)
            assertFalse(h.verify("1234", "garbage").ok)
            assertFalse(h.verify("1234", "pbkdf2:garbage").ok)
            assertFalse(h.verify("1234", "pbkdf2:100:deadbeef:notahex").ok)
            assertFalse(h.verify("1234", "scrypt:deadbeef:deadbeef").ok)
        }

    @Test fun setPinPersistsAndVerifies() =
        runTest {
            val repo = ParentalRepository(testDb(), AndroidPinHasher(), clock = { 0L })
            repo.setPin("1234")
            assertTrue(repo.settings.value.pinSet)
            assertTrue(repo.settings.value.pinEnabled)
            assertTrue(repo.verifyPin("1234"))
            assertFalse(repo.verifyPin("0000"))
        }

    @Test fun removePinClearsState() =
        runTest {
            val repo = ParentalRepository(testDb(), AndroidPinHasher(), clock = { 0L })
            repo.setPin("1234")
            repo.removePin()
            assertFalse(repo.settings.value.pinSet)
            assertFalse(repo.settings.value.pinEnabled)
            assertFalse(repo.verifyPin("1234"))
        }

    @Test fun lockoutTriggersAfterFiveMisses() =
        runTest {
            var now = 1_000_000L
            val repo = ParentalRepository(testDb(), AndroidPinHasher(), clock = { now })
            repo.setPin("1234")
            // Five wrong attempts
            repeat(5) { assertFalse(repo.verifyPin("9999")) }
            // 6th should be refused regardless of correctness (lockout active)
            assertTrue(repo.lockoutRemainingMs() > 0L)
            assertFalse(repo.verifyPin("1234"), "correct PIN must be refused during lockout")
            // Jump past the cooldown
            now += ParentalRepository.LOCKOUT_BASE_MS + 1_000L
            assertTrue(repo.verifyPin("1234"))
        }

    @Test fun lockChannelFlowsThroughStateFlow() =
        runTest {
            val repo = ParentalRepository(testDb(), AndroidPinHasher(), clock = { 0L })
            assertEquals(emptySet(), repo.lockedIds.value)
            repo.lockChannel("ch-1")
            assertEquals(setOf("ch-1"), repo.lockedIds.value)
            assertTrue(repo.isChannelLocked("ch-1"))
            repo.unlockChannel("ch-1")
            assertEquals(emptySet(), repo.lockedIds.value)
            assertFalse(repo.isChannelLocked("ch-1"))
        }

    @Test fun toggleHideAdultPersists() =
        runTest {
            val repo = ParentalRepository(testDb(), AndroidPinHasher(), clock = { 0L })
            assertFalse(repo.settings.value.hideAdultContent)
            repo.setHideAdultContent(true)
            assertTrue(repo.settings.value.hideAdultContent)
            repo.setHideAdultContent(false)
            assertFalse(repo.settings.value.hideAdultContent)
        }
}
