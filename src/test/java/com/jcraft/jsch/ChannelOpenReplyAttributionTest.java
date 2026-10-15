package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.io.AbstractIoWriteFuture;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

class ChannelOpenReplyAttributionTest {
  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void lateRepliesForAbandonedOpenDoNotCompleteTheRetry() throws Exception {
    // Separate real sessions cover both possible replies to the abandoned open.
    for (boolean decline : new boolean[] {true, false}) {
      AtomicInteger opens = new AtomicInteger();
      AtomicReference<Long> abandoned = new AtomicReference<>();
      AtomicReference<byte[]> firstReply = new AtomicReference<>();
      CountDownLatch retryReceived = new CountDownLatch(1);
      CountDownLatch releaseLateReply = new CountDownLatch(1);
      CountDownLatch releaseRetryReply = new CountDownLatch(1);
      CountDownLatch retryCompleted = new CountDownLatch(1);
      AtomicReference<Throwable> retryFailure = new AtomicReference<>();
      CountDownLatch lateReplyRead = new CountDownLatch(1);
      SshServer server = SshServer.setUpDefaultServer();
      server.setHost("127.0.0.1");
      server.setPort(0);
      SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
      keys.setKeySpec(new ECGenParameterSpec("secp256r1"));
      keys.loadKeys(null);
      server.setKeyPairProvider(keys);
      server.setPasswordAuthenticator((u, p, s) -> true);
      server.setSessionFactory(new SessionFactory(server) {
        protected ServerSessionImpl doCreateSession(IoSession io) throws Exception {
          return new ServerSessionImpl(server, io) {
            public IoWriteFuture writePacket(Buffer buffer) throws IOException {
              int message = buffer.rawByte(buffer.rpos()) & 255;
              if (message == SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION) {
                int position = buffer.rpos();
                buffer.rpos(position + 1);
                long recipient = buffer.getUInt();
                buffer.rpos(position);
                if (opens.incrementAndGet() == 1) {
                  abandoned.set(recipient);
                  firstReply.set(buffer.getCompactData());
                } else {
                  assertNotEquals(abandoned.get().longValue(), recipient);
                  retryReceived.countDown();
                  try {
                    if (!releaseLateReply.await(5, TimeUnit.SECONDS))
                      throw new IOException("Late reply was not released while retry was pending");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted before late reply release", e);
                  }
                  Buffer late = createBuffer(decline
                      ? SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE
                      : SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION, 64);
                  late.putUInt(abandoned.get());
                  if (decline) {
                    late.putInt(3);
                    late.putString("Open declined");
                    late.putString("");
                  } else {
                    byte[] original = firstReply.get();
                    late.wpos(late.wpos() - 4);
                    late.putRawBytes(original, 1, original.length - 1);
                  }
                  super.writePacket(late);
                  Buffer marker = createBuffer(SshConstants.SSH_MSG_UNIMPLEMENTED, 8);
                  marker.putInt(23456);
                  super.writePacket(marker);
                  try {
                    if (!releaseRetryReply.await(5, TimeUnit.SECONDS))
                      throw new IOException("Retry reply was not released after the reader marker");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted before retry reply release", e);
                  }
                  Buffer own = createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE, 64);
                  own.putUInt(recipient);
                  own.putInt(4);
                  own.putString("Retry declined");
                  own.putString("");
                  super.writePacket(own);
                }
                // Automatic confirmations are replaced by the staged replies above.
                return AbstractIoWriteFuture.fulfilled(this, true);
              }
              return super.writePacket(buffer);
            }
          };
        }
      });
      Session session = null;
      Channel retry = null;
      Thread connector = null;
      try {
        server.start();
        session = new JSch().getSession("test", "127.0.0.1", server.getPort());
        session.setPassword("test".getBytes(StandardCharsets.UTF_8));
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("kex", "ecdh-sha2-nistp256");
        session.setLogger(new Logger() {
          public boolean isEnabled(int level) { return true; }
          public void log(int level, String message) {
            if (message.equals("Received SSH_MSG_UNIMPLEMENTED for 23456"))
              lateReplyRead.countDown();
          }
        });
        session.connect(10000);
        // The first caller must finish before another open uses this same session.
        Channel abandonedChannel = session.openChannel("exec");
        JSchException missing = assertThrows(JSchException.class,
            () -> abandonedChannel.connect(1000));
        assertEquals("com.jcraft.jsch.JSchException: channel is not opened.", missing.toString());
        assertEquals(-1, abandonedChannel.getExitStatus());
        abandonedChannel.disconnect();

        retry = session.openChannel("exec");
        Channel pending = retry;
        connector = new Thread(() -> {
          try {
            // Explicit peer replies and finally cleanup bound this pending call.
            pending.connect(0);
          } catch (Throwable failure) {
            retryFailure.set(failure);
          } finally {
            retryCompleted.countDown();
          }
        }, "open-attribution-retry");
        connector.setDaemon(true);
        connector.start();
        assertTrue(retryReceived.await(5, TimeUnit.SECONDS), "Peer must receive the retry first");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean parked = false;
        while (System.nanoTime() < deadline && retryCompleted.getCount() != 0) {
          if (connector.getState() == Thread.State.TIMED_WAITING) {
            for (StackTraceElement frame : connector.getStackTrace()) {
              if (frame.getClassName().equals("com.jcraft.jsch.Channel")
                  && frame.getMethodName().equals("sendChannelOpen")) {
                parked = true;
                break;
              }
            }
          }
          if (parked) break;
          Thread.yield();
        }
        assertTrue(parked, "Retry must be parked in its open wait before releasing the old reply");
        assertEquals(1, retryCompleted.getCount(), "Retry must still be pending before release");
        assertFalse(pending.isClosed());
        releaseLateReply.countDown();
        assertTrue(lateReplyRead.await(5, TimeUnit.SECONDS),
            "Reader must process the late reply before the retry's own reply");
        assertEquals(1, retryCompleted.getCount(), "Old reply must not complete the retry");
        assertFalse(pending.isClosed(), "Old reply must not close the retry");
        assertFalse(pending.isConnected(), "Old reply must not open the retry");
        assertEquals(-1, pending.getExitStatus(), "Old reply must not supply the retry's reason");
        assertTrue(abandonedChannel.isClosed());
        assertFalse(abandonedChannel.isConnected());
        assertEquals(-1, abandonedChannel.getExitStatus(),
            "The late reply must not change the abandoned caller's outcome");
        releaseRetryReply.countDown();
        assertTrue(retryCompleted.await(5, TimeUnit.SECONDS), "Retry must receive its own reply");
        JSchChannelOpenException own = assertInstanceOf(JSchChannelOpenException.class,
            retryFailure.get());
        assertEquals("com.jcraft.jsch.JSchChannelOpenException: channel is not opened.",
            own.toString());
        assertEquals(4, own.getReasonCode(), "Only the retry's own reply may set its outcome");
        assertEquals(4, pending.getExitStatus());
        assertTrue(pending.isClosed());
        assertFalse(pending.isConnected());
        assertEquals(-1, abandonedChannel.getExitStatus());
        assertEquals(2, opens.get());
        assertTrue(session.isConnected());
      } finally {
        releaseLateReply.countDown();
        releaseRetryReply.countDown();
        try {
          if (retry != null) retry.disconnect();
          if (session != null) session.disconnect();
        } finally {
          try {
            server.stop(true);
          } finally {
            if (connector != null) {
              connector.join(5000);
              assertFalse(connector.isAlive(), "Retry caller must stop during cleanup");
            }
          }
        }
      }
    }
  }
}
