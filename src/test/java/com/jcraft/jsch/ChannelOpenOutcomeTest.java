package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.io.AbstractIoWriteFuture;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

class ChannelOpenOutcomeTest {
  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void silenceAndEveryDeclineReasonRemainDistinctAfterLocalClose() throws Exception {
    AtomicInteger mode = new AtomicInteger();
    AtomicInteger withheld = new AtomicInteger();
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
    keys.setKeySpec(new ECGenParameterSpec("secp256r1"));
    keys.loadKeys(null);
    server.setKeyPairProvider(keys);
    server.setPasswordAuthenticator((u, p, s) -> true);
    server.setChannelFactories(java.util.Collections.singletonList(new ChannelFactory() {
      public String getName() { return "session"; }
      public org.apache.sshd.common.channel.Channel createChannel(
          org.apache.sshd.common.session.Session session) {
        return mode.get() == 0 ? new ChannelSession() : null;
      }
    }));
    server.setSessionFactory(new SessionFactory(server) {
      protected ServerSessionImpl doCreateSession(IoSession io) throws Exception {
        return new ServerSessionImpl(server, io) {
          public IoWriteFuture writePacket(Buffer buffer) throws IOException {
            int message = buffer.rawByte(buffer.rpos()) & 255;
            if (message == SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION) {
              withheld.incrementAndGet();
              return AbstractIoWriteFuture.fulfilled(this, true);
            }
            if (message == SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE && mode.get() == 2) {
              int end = buffer.wpos();
              buffer.wpos(buffer.rpos() + 5);
              buffer.putUInt(0xffff_ffffL);
              buffer.wpos(end);
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
      session.connect(10_000);
      // Serial opens check the result retained by one live session across retries.
      for (int scenario = 0; scenario < 3; scenario++) {
        mode.set(scenario);
        for (int attempt = 0; attempt < 2; attempt++) {
          Channel channel = session.openChannel("exec");
          JSchException failure = assertThrows(JSchException.class, () -> channel.connect(1_000));
          assertTrue(channel.isClosed(), "Every unsuccessful open must be terminal locally");
          assertFalse(channel.isConnected());
          assertEquals(scenario == 0
              ? "com.jcraft.jsch.JSchException: channel is not opened."
              : "com.jcraft.jsch.JSchChannelOpenException: channel is not opened.",
              failure.toString(), "Local close must not erase whether the server declined the open");
          if (scenario != 0) {
            JSchChannelOpenException declined = assertInstanceOf(JSchChannelOpenException.class, failure);
            assertEquals(scenario == 1 ? 3 : -1, declined.getReasonCode());
          }
          assertEquals(scenario == 1 ? 3 : -1, channel.getExitStatus());
          assertTrue(session.isConnected());
          channel.disconnect();
        }
      }
      assertEquals(2, withheld.get());
    } finally {
      if (session != null) session.disconnect();
      server.stop(true);
    }
  }
}
