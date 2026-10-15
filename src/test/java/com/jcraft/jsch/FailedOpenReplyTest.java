package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.RepeatedTest;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.junit.jupiter.api.Timeout;

public class FailedOpenReplyTest {
  @RepeatedTest(3)
  @Timeout(30)
  public void failedWritesMustNotLeaveExpectedReplies() throws Exception {
    checkFailedWrite(0, 64);
  }

  @RepeatedTest(3)
  @Timeout(30)
  public void partialWritesMustNotLeaveExpectedReplies() throws Exception {
    checkFailedWrite(1, 1);
  }

  @RepeatedTest(3)
  @Timeout(30)
  public void flushFailuresMustNotLeaveExpectedReplies() throws Exception {
    checkFailedWrite(2, 1);
  }

  private static void checkFailedWrite(int mode, int count) throws Exception {
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    SimpleGeneratorHostKeyProvider provider = new SimpleGeneratorHostKeyProvider();
    provider.setKeySpec(new ECGenParameterSpec("secp256r1"));
    provider.loadKeys(null);
    server.setKeyPairProvider(provider);
    server.setPasswordAuthenticator((u, p, s) -> true);
    server.setSessionFactory(new SessionFactory(server) {
      protected ServerSessionImpl doCreateSession(IoSession io) throws Exception {
        return new ServerSessionImpl(server, io) {
          protected void doHandleMessage(Buffer message) throws Exception {
            if ((message.rawByte(message.rpos()) & 255) == SshConstants.SSH_MSG_CHANNEL_OPEN)
              return; // The test supplies the reply after the caller observes the write failure.
            super.doHandleMessage(message);
          }
        };
      }
    });
    Session session = null;
    AtomicBoolean rejectWrites = new AtomicBoolean();
    List<String> diagnostics = new CopyOnWriteArrayList<>();
    CountDownLatch markerRead = new CountDownLatch(1);
    List<Integer> failedIds = new ArrayList<>();
    try {
      server.start();
      session = new JSch().getSession("review", "127.0.0.1", server.getPort());
      session.setDaemonThread(true);
      session.setPassword("review".getBytes(StandardCharsets.UTF_8));
      session.setConfig("StrictHostKeyChecking", "no");
      session.setSocketFactory(new SocketFactory() {
        public Socket createSocket(String host, int port) throws IOException {
          return new Socket(host, port);
        }
        public InputStream getInputStream(Socket socket) throws IOException {
          return socket.getInputStream();
        }
        public OutputStream getOutputStream(Socket socket) throws IOException {
          return new FilterOutputStream(socket.getOutputStream()) {
            public void write(byte[] bytes, int offset, int length) throws IOException {
              if (rejectWrites.get() && mode != 2) {
                if (mode == 1) out.write(bytes, offset, 1);
                throw new IOException("test outgoing open write failed in mode " + mode);
              }
              out.write(bytes, offset, length);
            }
            public void flush() throws IOException {
              out.flush();
              if (rejectWrites.get() && mode == 2)
                throw new IOException("test outgoing open write failed in mode " + mode);
            }
            public void write(int value) throws IOException {
              if (rejectWrites.get()) throw new IOException("test outgoing open write failed in mode " + mode);
              out.write(value);
            }
          };
        }
      });
      session.setLogger(new Logger() {
        public boolean isEnabled(int level) { return true; }
        public void log(int level, String message) {
          diagnostics.add(message);
          if (message.equals("Received SSH_MSG_UNIMPLEMENTED for 22222")) markerRead.countDown();
        }
      });
      session.connect(10000);
      rejectWrites.set(true);
      // Serial calls prove accumulation on one live connection before any peer reply.
      for (int i = 0; i < count; i++) {
        Channel channel = session.openChannel("exec");
        failedIds.add(channel.getId());
        JSchException failure = assertThrows(JSchException.class, () -> channel.connect(1000));
        assertEquals("java.io.IOException: test outgoing open write failed in mode " + mode, failure.getMessage());
        assertTrue(channel.isClosed());
        assertTrue(session.isConnected());
      }
      ServerSession peer = (ServerSession) server.getActiveSessions().get(0);
      List<Integer> replyIds = new ArrayList<>(failedIds);
      replyIds.add(Integer.MAX_VALUE);
      for (int id : replyIds) {
        Buffer reply = peer.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE, 64);
        reply.putInt(id);
        reply.putInt(1);
        reply.putString("Open declined");
        reply.putString("");
        IoWriteFuture sent = peer.writePacket(reply);
        assertTrue(sent.await(5, TimeUnit.SECONDS));
        assertTrue(sent.isWritten());
      }
      Buffer marker = peer.createBuffer(SshConstants.SSH_MSG_UNIMPLEMENTED, 4);
      marker.putInt(22222);
      assertTrue(peer.writePacket(marker).await(5, TimeUnit.SECONDS));
      assertTrue(markerRead.await(5, TimeUnit.SECONDS));
      assertTrue(session.isConnected());
      String suffix = ": no pending open reply";
      assertTrue(diagnostics.contains("Ignoring SSH_MSG_CHANNEL_OPEN_FAILURE for channel " + Integer.MAX_VALUE + suffix));
      long droppedFailed = failedIds.stream().filter(id -> diagnostics.contains(
          "Ignoring SSH_MSG_CHANNEL_OPEN_FAILURE for channel " + id + suffix)).count();
      System.out.println("OBSERVED failed writes=" + failedIds.size() + " still connected=" + session.isConnected()
          + " failed-open replies dropped=" + droppedFailed + " unknown-id control dropped=true");
      assertEquals(count, droppedFailed, "A failed outgoing open must not remain an expected reply");
    } finally {
      if (session != null) session.disconnect();
      server.stop(true);
    }
  }
}
