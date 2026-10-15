package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.io.AbstractIoWriteFuture;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

class PendingCloseBoundTest {
  private static final class Peer extends ServerSessionImpl {
    final AtomicBoolean hold = new AtomicBoolean();
    final CountDownLatch proposalHeld = new CountDownLatch(1);
    volatile byte[] proposal;
    volatile byte[] confirmation;
    volatile long expectedRecipient;
    final CountDownLatch openReplyHeld = new CountDownLatch(1);
    final AtomicBoolean holdOpen = new AtomicBoolean(true);
    volatile AbstractIoWriteFuture heldOpenFuture;
    final AtomicInteger closes = new AtomicInteger();
    final AtomicInteger wrongRecipient = new AtomicInteger();
    final CountDownLatch followingWrite = new CountDownLatch(1);

    Peer(SshServer s, IoSession io) throws Exception {
      super(s, io);
    }

    public IoWriteFuture writePacket(Buffer b) throws IOException {
      if ((b.rawByte(b.rpos()) & 255) == SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION
          && holdOpen.compareAndSet(true, false)) {
        confirmation = b.getCompactData();
        Buffer parsed = new ByteArrayBuffer(confirmation);
        parsed.getByte();
        parsed.getUInt();
        expectedRecipient = parsed.getUInt();
        heldOpenFuture = new AbstractIoWriteFuture(this, "review-held-open") {};
        openReplyHeld.countDown();
        return heldOpenFuture;
      }
      return super.writePacket(b);
    }

    protected void doHandleMessage(Buffer buffer) throws Exception {
      int message = buffer.rawByte(buffer.rpos()) & 255;
      if (message == SshConstants.SSH_MSG_KEXINIT && hold.compareAndSet(true, false)) {
        proposal = buffer.getCompactData();
        proposalHeld.countDown();
        return;
      }
      if (message == SshConstants.SSH_MSG_CHANNEL_CLOSE) {
        int pos = buffer.rpos();
        buffer.getByte();
        long id = buffer.getUInt();
        buffer.rpos(pos);
        closes.incrementAndGet();
        if (id != expectedRecipient)
          wrongRecipient.incrementAndGet();
        return;
      }
      if (message == SshConstants.SSH_MSG_GLOBAL_REQUEST)
        followingWrite.countDown();
      super.doHandleMessage(buffer);
    }

    void resumeProposal() throws Exception {
      // Resume under the same guard and handler context as normal peer decoding.
      synchronized (decodeLock) {
        super.handleMessage(new ByteArrayBuffer(proposal));
      }
    }
  }

  @RepeatedTest(3)
  @Timeout(30)
  void repeatedLateConfirmationsProduceOneCloseAfterExchange() throws Exception {
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
    keys.setKeySpec(new ECGenParameterSpec("secp256r1"));
    keys.loadKeys(null);
    server.setKeyPairProvider(keys);
    server.setPasswordAuthenticator((u, p, s) -> true);
    AtomicReference<Peer> peerRef = new AtomicReference<>();
    server.setSessionFactory(new SessionFactory(server) {
      protected ServerSessionImpl doCreateSession(IoSession io) throws Exception {
        Peer p = new Peer(server, io);
        peerRef.set(p);
        return p;
      }
    });
    Session session = null;
    ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "review-bound-writer");
      t.setDaemon(true);
      return t;
    });
    try {
      server.start();
      session = new JSch().getSession("test", "127.0.0.1", server.getPort());
      session.setDaemonThread(true);
      session.setPassword("test".getBytes(StandardCharsets.UTF_8));
      session.setConfig("StrictHostKeyChecking", "no");
      session.setConfig("kex", "ecdh-sha2-nistp256");
      session.setConfig("CheckKexes", "ecdh-sha2-nistp256");
      session.connect(10000);
      assertEquals(0, session.getTimeout());
      CountDownLatch[] markers = {new CountDownLatch(1), new CountDownLatch(1)};
      session.setLogger(new Logger() {
        public boolean isEnabled(int l) {
          return true;
        }

        public void log(int l, String m) {
          if (m.equals("Received SSH_MSG_UNIMPLEMENTED for 11111"))
            markers[0].countDown();
          if (m.equals("Received SSH_MSG_UNIMPLEMENTED for 11112"))
            markers[1].countDown();
        }
      });
      Peer peer = peerRef.get();
      ChannelExec late = (ChannelExec) session.openChannel("exec");
      late.setCommand("late");
      assertEquals("channel is not opened.",
          assertThrows(JSchException.class, () -> late.connect(1000)).getMessage());
      assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS));
      peer.hold.set(true);
      session.rekey();
      assertTrue(peer.proposalHeld.await(5, TimeUnit.SECONDS));
      int count = 0;
      for (int batch = 0; batch < 2; batch++) {
        for (int i = 0; i < 256; i++) {
          byte[] data = peer.confirmation;
          Buffer reply = peer.createBuffer(data[0], data.length);
          reply.putRawBytes(data, 1, data.length - 1);
          IoWriteFuture actual = peer.writePacket(reply);
          assertTrue(actual.await(5, TimeUnit.SECONDS));
          assertTrue(actual.isWritten());
          if (count == 0)
            peer.heldOpenFuture.setValue(Boolean.TRUE);
          count++;
        }
        Buffer marker = peer.createBuffer(SshConstants.SSH_MSG_UNIMPLEMENTED, 4);
        marker.putInt(11111 + batch);
        assertTrue(peer.writePacket(marker).await(5, TimeUnit.SECONDS));
        assertTrue(markers[batch].await(5, TimeUnit.SECONDS), "Reader stopped before batch marker");
        assertTrue(session.isConnected());
        assertEquals(0, peer.closes.get());
        System.out.println(
            "OBSERVED pending late replies processed without completing exchange=" + count);
      }
      Buffer unknown = peer.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION, 16);
      unknown.putInt(Integer.MAX_VALUE);
      unknown.putInt((int) peer.expectedRecipient);
      unknown.putInt(1048576);
      unknown.putInt(32768);
      assertTrue(peer.writePacket(unknown).await(5, TimeUnit.SECONDS));
      peer.resumeProposal();
      Session current = session;
      Future<?> f = writer.submit(() -> {
        current.sendKeepAliveMsg();
        return null;
      });
      f.get(5, TimeUnit.SECONDS);
      assertTrue(peer.followingWrite.await(5, TimeUnit.SECONDS));
      System.out.println("OBSERVED close notices for one abandoned recipient="
          + peer.expectedRecipient + " count=" + peer.closes.get());
      assertEquals(0, peer.wrongRecipient.get());
      assertEquals(1, peer.closes.get(),
          "Duplicate late replies must not retain and emit duplicate close notices");
    } finally {
      if (session != null)
        session.disconnect();
      writer.shutdownNow();
      try {
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
      } finally {
        server.stop(true);
      }
    }
  }

  @RepeatedTest(3)
  @Timeout(30)
  void lateFailureConsumesExpectedReplyBeforeConfirmation() throws Exception {
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
    keys.setKeySpec(new ECGenParameterSpec("secp256r1"));
    keys.loadKeys(null);
    server.setKeyPairProvider(keys);
    server.setPasswordAuthenticator((u, p, s) -> true);
    AtomicReference<Peer> peerRef = new AtomicReference<>();
    server.setSessionFactory(new SessionFactory(server) {
      protected ServerSessionImpl doCreateSession(IoSession io) throws Exception {
        Peer p = new Peer(server, io);
        peerRef.set(p);
        return p;
      }
    });
    Session session = null;
    ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "review-bound-writer");
      t.setDaemon(true);
      return t;
    });
    try {
      server.start();
      session = new JSch().getSession("test", "127.0.0.1", server.getPort());
      session.setDaemonThread(true);
      session.setPassword("test".getBytes(StandardCharsets.UTF_8));
      session.setConfig("StrictHostKeyChecking", "no");
      session.setConfig("kex", "ecdh-sha2-nistp256");
      session.setConfig("CheckKexes", "ecdh-sha2-nistp256");
      session.connect(10000);
      assertEquals(0, session.getTimeout());
      CountDownLatch markerRead = new CountDownLatch(1);
      session.setLogger(new Logger() {
        public boolean isEnabled(int level) {
          return true;
        }

        public void log(int level, String message) {
          if (message.equals("Received SSH_MSG_UNIMPLEMENTED for 11113"))
            markerRead.countDown();
        }
      });
      Peer peer = peerRef.get();
      ChannelExec late = (ChannelExec) session.openChannel("exec");
      late.setCommand("late");
      assertEquals("channel is not opened.",
          assertThrows(JSchException.class, () -> late.connect(1000)).getMessage());
      assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS));
      Buffer original = new ByteArrayBuffer(peer.confirmation);
      original.getByte();
      long localId = original.getUInt();
      Buffer failure = peer.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE, 32);
      failure.putInt(localId);
      failure.putInt(1);
      failure.putString("Open declined");
      failure.putString("");
      IoWriteFuture failedReply = peer.writePacket(failure);
      assertTrue(failedReply.await(5, TimeUnit.SECONDS));
      assertTrue(failedReply.isWritten());
      byte[] data = peer.confirmation;
      Buffer confirmation = peer.createBuffer(data[0], data.length);
      confirmation.putRawBytes(data, 1, data.length - 1);
      IoWriteFuture confirmedReply = peer.writePacket(confirmation);
      assertTrue(confirmedReply.await(5, TimeUnit.SECONDS));
      assertTrue(confirmedReply.isWritten());
      peer.heldOpenFuture.setValue(Boolean.TRUE);
      Buffer marker = peer.createBuffer(SshConstants.SSH_MSG_UNIMPLEMENTED, 4);
      marker.putInt(11113);
      IoWriteFuture markedReply = peer.writePacket(marker);
      assertTrue(markedReply.await(5, TimeUnit.SECONDS));
      assertTrue(markedReply.isWritten());
      assertTrue(markerRead.await(5, TimeUnit.SECONDS),
          "Reader stopped after a reply following the late failure");
      Session current = session;
      Future<?> following = writer.submit(() -> {
        current.sendKeepAliveMsg();
        return null;
      });
      following.get(5, TimeUnit.SECONDS);
      assertTrue(peer.followingWrite.await(5, TimeUnit.SECONDS));
      assertTrue(session.isConnected());
      assertEquals(0, peer.closes.get(),
          "A reply after an open failure must not produce a close notice");
    } finally {
      if (session != null)
        session.disconnect();
      writer.shutdownNow();
      try {
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
      } finally {
        server.stop(true);
      }
    }
  }
}
