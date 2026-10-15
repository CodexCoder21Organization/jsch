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
                }
                // Neither the first nor the second open receives its own confirmation.
                return AbstractIoWriteFuture.fulfilled(this, true);
              }
              return super.writePacket(buffer);
            }
          };
        }
      });
      Session session = null;
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
        // Serial calls impose abandonment before a retry uses the same session.
        for (int attempt = 0; attempt < 2; attempt++) {
          Channel channel = session.openChannel("exec");
          JSchException failure = assertThrows(JSchException.class, () -> channel.connect(1000));
          assertEquals("com.jcraft.jsch.JSchException: channel is not opened.", failure.toString());
          assertEquals(-1, channel.getExitStatus(), "The old reply must not supply a retry reason");
          channel.disconnect();
        }
        assertTrue(lateReplyRead.await(5, TimeUnit.SECONDS), "Reader must process the late reply before verdict");
        assertEquals(2, opens.get());
        assertTrue(session.isConnected());
      } finally {
        if (session != null) session.disconnect();
        server.stop(true);
      }
    }
  }
}
