package com.sadad.app;

import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A slow server and an unreachable one get different words; anything else keeps its own message. */
public class NetworkFailureMessageTest {
    @Test public void timeoutSaysTheServerIsSlowNotUnreachable() {
        String slow = LedgerClient.networkFailureMessage(new SocketTimeoutException("Read timed out"));
        String unreachable = LedgerClient.networkFailureMessage(new ConnectException("refused"));
        assertTrue(slow.contains("بطيء"));
        assertNotEquals(unreachable, slow);
    }

    @Test public void connectAndDnsFailuresSayUnreachable() {
        String unreachable = LedgerClient.networkFailureMessage(new ConnectException("refused"));
        assertTrue(unreachable.startsWith("تعذر الوصول إلى الخادم"));
        assertEquals(unreachable, LedgerClient.networkFailureMessage(new UnknownHostException("no dns")));
    }

    @Test public void otherErrorsAreNotNetworkFailures() {
        assertNull(LedgerClient.networkFailureMessage(new IllegalStateException("كلمة المرور غير صحيحة")));
    }
}
