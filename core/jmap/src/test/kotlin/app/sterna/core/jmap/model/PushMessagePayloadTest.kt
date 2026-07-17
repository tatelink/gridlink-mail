package app.sterna.core.jmap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessagePayloadTest {

    @Test fun parsesPushVerification() {
        val payload = PushMessagePayload.parse(
            """{"@type":"PushVerification","pushSubscriptionId":"ps1","verificationCode":"c42"}""",
        )
        val verification = payload as PushMessagePayload.Verification
        assertEquals("ps1", verification.pushSubscriptionId)
        assertEquals("c42", verification.verificationCode)
    }

    @Test fun parsesStateChange() {
        val payload = PushMessagePayload.parse(
            """{"@type":"StateChange","changed":{"acc1":{"Email":"s1","Mailbox":"s2"}}}""",
        )
        val change = payload as PushMessagePayload.Change
        assertTrue(change.stateChange.emailChanged("acc1"))
        assertEquals("s1", change.stateChange.changed["acc1"]?.get("Email"))
    }

    @Test fun unknownTypeGarbageOrPartial_isNull() {
        assertNull(PushMessagePayload.parse("""{"@type":"SomethingElse"}"""))
        assertNull(PushMessagePayload.parse("not json at all"))
        assertNull(PushMessagePayload.parse("""{"@type":"PushVerification","pushSubscriptionId":"ps1"}"""))
        assertNull(PushMessagePayload.parse("""{"no":"type"}"""))
    }
}
