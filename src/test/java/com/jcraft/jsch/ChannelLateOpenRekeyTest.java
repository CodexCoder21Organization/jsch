package com.jcraft.jsch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.AbstractIoWriteFuture;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.io.FilterInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
class ChannelLateOpenRekeyTest {
  private static final int OPEN_TIMEOUT_MILLIS = 1_000;
  private static final String OPEN_TIMEOUT_MESSAGE = "channel is not opened.";
  private static final byte[] FRESH_RESPONSE = "fresh-ok\n".getBytes(StandardCharsets.UTF_8);

  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void inFlightLateConfirmationMustNotBlockRekey() throws Exception {
    HeldReplyPeer peer = new HeldReplyPeer();
    SshServer server = peer.server();
    GateSocketFactory sockets = new GateSocketFactory();
    Session session = null;
    try {
      server.start();
      session = new JSch().getSession("test", "127.0.0.1", server.getPort());
      session.setSocketFactory(sockets);
      session.setDaemonThread(true);
      session.setPassword("test".getBytes(StandardCharsets.UTF_8));
      session.setConfig("StrictHostKeyChecking", "no");
      session.setConfig("kex", "ecdh-sha2-nistp256");
      session.setConfig("CheckKexes", "ecdh-sha2-nistp256");
      session.connect(10_000);
      ChannelExec channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand("late");
      assertEquals(OPEN_TIMEOUT_MESSAGE,
          assertThrows(JSchException.class, () -> channel.connect(OPEN_TIMEOUT_MILLIS)).getMessage());
      assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS));
      sockets.armed.set(true);
      peer.releaseHeldOpenReply();
      assertTrue(sockets.bytesHeld.await(5, TimeUnit.SECONDS), "Client did not hold reply bytes");
      session.rekey();
      System.out.println("TRACE: late confirmation written before client rekey; client rekey returned; releasing confirmation bytes");
      sockets.release.countDown();
      CountDownLatch keepAliveDone = new CountDownLatch(1);
      AtomicReference<Throwable> keepAliveFailure = new AtomicReference<>();
      Session current = session;
      Thread keepAlive = new Thread(() -> {
        try { current.sendKeepAliveMsg(); }
        catch (Throwable failure) { keepAliveFailure.set(failure); }
        finally { keepAliveDone.countDown(); }
      }, "late-open-keepalive");
      keepAlive.setDaemon(true);
      keepAlive.start();
      boolean progressed = keepAliveDone.await(5, TimeUnit.SECONDS);
      boolean closed = peer.firstStreamClosed.getCount() == 0;
      System.out.println("TRACE: rekeyWriterCompleted=" + progressed + " peerClosed=" + closed + " sessionConnected=" + session.isConnected());
      assertTrue(progressed, "Late confirmation stopped rekey and the next transport write");
      assertNull(keepAliveFailure.get(), "Keepalive write failed");
      assertTrue(session.isConnected(), "Rekey terminated the session");
    } finally {
      sockets.release.countDown();
      if (session != null) session.disconnect();
      server.stop(true);
    }
  }

  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void lateOpenFailureSendsNoClose() throws Exception {
    HeldReplyPeer peer = new HeldReplyPeer();
    peer.rejectFirst = true;
    peer.holdCommandOpen = true;
    SshServer server = peer.server();
    Session session = null;
    ChannelExec fresh = null;
    try {
      server.start();
      session = connect(server);
      ChannelExec rejected = (ChannelExec) session.openChannel("exec");
      rejected.setCommand("late");
      assertEquals(OPEN_TIMEOUT_MESSAGE,
          assertThrows(JSchException.class, () -> rejected.connect(OPEN_TIMEOUT_MILLIS)).getMessage());
      assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS));
      peer.releaseHeldOpenReply();
      fresh = (ChannelExec) session.openChannel("exec");
      fresh.setCommand("fresh");
      InputStream output = fresh.getInputStream();
      fresh.connect(OPEN_TIMEOUT_MILLIS);
      byte[] response = new byte[FRESH_RESPONSE.length];
      int received = 0;
      while (received < response.length) {
        int count = output.read(response, received, response.length - received);
        assertTrue(count > 0, "Fresh command ended before its complete response");
        received += count;
      }
      assertArrayEquals(FRESH_RESPONSE, response);
      assertEquals(0, peer.receivedCloseCount.get(), "Client sent CLOSE after late OPEN_FAILURE");
      assertTrue(session.isConnected());
      System.out.println("TRACE: late OPEN_FAILURE processed; fresh exec complete; client CLOSE count=" + peer.receivedCloseCount.get());
    } finally {
      if (fresh != null) fresh.disconnect();
      if (session != null) session.disconnect();
      server.stop(true);
    }
  }

  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void deferredCloseIsSentOnceAcrossConsecutiveRenegotiations() throws Exception {
    HeldReplyPeer peer = new HeldReplyPeer();
    SshServer server = peer.server();
    GateSocketFactory sockets = new GateSocketFactory();
    ExecutorService writer = Executors.newSingleThreadExecutor();
    Session session = null;
    try {
      server.start();
      session = connect(server, sockets);
      // These rounds are ordered: the second exchange must observe the first drain.
      for (int round = 0; round < 2; round++) {
        peer.prepareReply();
        ChannelExec late = (ChannelExec) session.openChannel("exec");
        late.setCommand("late");
        assertEquals(OPEN_TIMEOUT_MESSAGE,
            assertThrows(JSchException.class, () -> late.connect(OPEN_TIMEOUT_MILLIS)).getMessage());
        assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS), "Peer did not hold confirmation");
        long recipient = peer.latestChannel.get().getChannelId();
        sockets.prepare();
        peer.releaseHeldOpenReply();
        assertTrue(sockets.bytesHeld.await(5, TimeUnit.SECONDS), "Client did not hold confirmation");
        peer.holdRenegotiation.set(true);
        session.rekey();
        assertTrue(peer.renegotiationHeld.await(5, TimeUnit.SECONDS), "Peer did not hold renegotiation");
        CountDownLatch readerProgress = observeReaderProgress(session);
        sockets.release.countDown();
        peer.sendReaderMarker();
        assertTrue(readerProgress.await(5, TimeUnit.SECONDS),
            "Reader stopped after the late confirmation during renegotiation");
        assertEquals(round, peer.receivedCloseCount.get(), "Close arrived before renegotiation completed");
        peer.releaseRenegotiation();
        Session current = session;
        Future<?> write = writer.submit(() -> {
          current.sendKeepAliveMsg();
          return null;
        });
        write.get(5, TimeUnit.SECONDS);
        assertTrue(peer.keepAliveReceived.await(5, TimeUnit.SECONDS), "Peer did not receive next write");
        assertEquals(round + 1, peer.receivedCloseCount.get(), "Deferred close count changed");
        assertEquals(1, java.util.Collections.frequency(peer.closeRecipients, recipient),
            "Peer recipient did not receive exactly one close");
        assertFalse(late.isConnected(), "Late confirmation reopened the local channel");
        assertTrue(session.isConnected(), "Renegotiation ended the connection");
      }
      assertEquals(2, peer.closeRecipients.size(), "A deferred close was repeated across exchanges");
    } finally {
      sockets.release.countDown();
      if (session != null) session.disconnect();
      writer.shutdownNow();
      assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS), "Transport writer did not stop");
      server.stop(true);
    }
  }

  @RepeatedTest(3)
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void connectionEndDiscardsDeferredClose() throws Exception {
    HeldReplyPeer peer = new HeldReplyPeer();
    SshServer server = peer.server();
    GateSocketFactory sockets = new GateSocketFactory();
    Session session = null;
    try {
      server.start();
      session = connect(server, sockets);
      ChannelExec late = (ChannelExec) session.openChannel("exec");
      late.setCommand("late");
      assertEquals(OPEN_TIMEOUT_MESSAGE,
          assertThrows(JSchException.class, () -> late.connect(OPEN_TIMEOUT_MILLIS)).getMessage());
      assertTrue(peer.openReplyHeld.await(5, TimeUnit.SECONDS), "Peer did not hold confirmation");
      sockets.prepare();
      peer.releaseHeldOpenReply();
      assertTrue(sockets.bytesHeld.await(5, TimeUnit.SECONDS), "Client did not hold confirmation");
      peer.holdRenegotiation.set(true);
      session.rekey();
      assertTrue(peer.renegotiationHeld.await(5, TimeUnit.SECONDS), "Peer did not hold renegotiation");
      CountDownLatch readerProgress = observeReaderProgress(session);
      sockets.release.countDown();
      peer.sendReaderMarker();
      assertTrue(readerProgress.await(5, TimeUnit.SECONDS),
          "Reader stopped before the connection could discard its deferred close");
      assertEquals(0, peer.receivedCloseCount.get(), "Close arrived while renegotiation was pending");
      session.disconnect();
      assertTrue(peer.connectionClosed.await(5, TimeUnit.SECONDS), "Peer connection did not end");
      assertTrue(peer.firstStreamClosed.await(5, TimeUnit.SECONDS), "Peer allocation remained open");
      assertEquals(0, peer.receivedCloseCount.get(), "Connection end wrote the deferred close");
      assertFalse(late.isConnected(), "Late confirmation reopened the local channel");
      assertFalse(session.isConnected(), "Connection remained active after disconnect");
      assertEquals(0, peer.allocatedStreams.get(), "Peer retained a channel after connection end");
    } finally {
      sockets.release.countDown();
      if (session != null) session.disconnect();
      server.stop(true);
    }
  }

  private static CountDownLatch observeReaderProgress(Session session) {
    CountDownLatch progress = new CountDownLatch(1);
    session.setLogger(new Logger() {
      public boolean isEnabled(int level) { return true; }
      public void log(int level, String message) {
        if ("Received SSH_MSG_UNIMPLEMENTED for 12345".equals(message)) progress.countDown();
      }
    });
    return progress;
  }

  private static Session connect(SshServer server, GateSocketFactory sockets) throws Exception {
    Session session = new JSch().getSession("test", "127.0.0.1", server.getPort());
    session.setSocketFactory(sockets);
    session.setDaemonThread(true);
    session.setPassword("test".getBytes(StandardCharsets.UTF_8));
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("kex", "ecdh-sha2-nistp256");
    session.setConfig("CheckKexes", "ecdh-sha2-nistp256");
    session.connect(10_000);
    return session;
  }

  private static final class GateSocketFactory implements SocketFactory {
    final AtomicBoolean armed = new AtomicBoolean();
    CountDownLatch bytesHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    void prepare() {
      bytesHeld = new CountDownLatch(1);
      release = new CountDownLatch(1);
      armed.set(true);
    }
    public Socket createSocket(String host, int port) throws IOException { return new Socket(host, port); }
    public OutputStream getOutputStream(Socket socket) throws IOException { return socket.getOutputStream(); }
    public InputStream getInputStream(Socket socket) throws IOException {
      return new FilterInputStream(socket.getInputStream()) {
        public int read(byte[] data, int offset, int length) throws IOException {
          int count = super.read(data, offset, length);
          if (count > 0 && armed.compareAndSet(true, false)) {
            bytesHeld.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Reply-byte gate was not released");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
          }
          return count;
        }
      };
    }
  }

  private static Session connect(SshServer server) throws Exception {
    Session session = new JSch().getSession("test", "127.0.0.1", server.getPort());
    session.setPassword("test".getBytes(StandardCharsets.UTF_8));
    session.setConfig("StrictHostKeyChecking", "no");
    session.setConfig("kex", "ecdh-sha2-nistp256");
    session.setConfig("CheckKexes", "ecdh-sha2-nistp256");
    session.connect(10_000);
    return session;
  }

  private static byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int count;
    while ((count = input.read(buffer)) >= 0) {
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static final class HeldReplyPeer {
    private boolean rejectFirst;
    private boolean holdCommandOpen;
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final AtomicBoolean holdRenegotiation = new AtomicBoolean();
    private CountDownLatch renegotiationHeld = new CountDownLatch(1);
    private CountDownLatch keepAliveReceived = new CountDownLatch(1);
    private final AtomicReference<PendingWriteFuture> heldRenegotiation = new AtomicReference<>();
    private final AtomicReference<byte[]> renegotiationPacket = new AtomicReference<>();
    private final AtomicReference<Channel> latestChannel = new AtomicReference<>();
    private final List<Long> closeRecipients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rejectionUsed = new AtomicBoolean();
    private final AtomicInteger receivedCloseCount = new AtomicInteger();
    private CountDownLatch openReplyHeld = new CountDownLatch(1);
    private final CountDownLatch firstStreamCreated = new CountDownLatch(1);
    private final CountDownLatch firstStreamClosed = new CountDownLatch(1);
    private final AtomicInteger allocatedStreams = new AtomicInteger();
    private final AtomicReference<Channel> firstChannel = new AtomicReference<>();
    private final AtomicReference<ServerSessionImpl> serverSession = new AtomicReference<>();
    private final AtomicReference<PendingWriteFuture> heldReply = new AtomicReference<>();
    private final AtomicReference<byte[]> heldPacket = new AtomicReference<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> commandFailure = new AtomicReference<>();

    private SshServer server() throws Exception {
      SshServer server = SshServer.setUpDefaultServer();
      server.setHost("127.0.0.1");
      server.setPort(0);
      SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
      keys.setKeySpec(new ECGenParameterSpec("secp256r1"));
      keys.loadKeys(null);
      server.setKeyPairProvider(keys);
      server.setPasswordAuthenticator((username, password, session) -> true);
      installSingleStreamFactory(server);
      installHeldReplySessionFactory(server);
      server.setCommandFactory(new CommandFactory() {
        @Override
        public Command createCommand(ChannelSession channel, String command) {
          commands.add(command);
          return new ResponseCommand(commandFailure, holdCommandOpen);
        }
      });
      return server;
    }

    private void installSingleStreamFactory(SshServer server) {
      List<ChannelFactory> factories = new ArrayList<>();
      int sessionFactories = 0;
      for (ChannelFactory delegate : server.getChannelFactories()) {
        if (!"session".equals(delegate.getName())) {
          factories.add(delegate);
          continue;
        }
        sessionFactories++;
        factories.add(new ChannelFactory() {
          @Override
          public String getName() {
            return delegate.getName();
          }

          @Override
          public Channel createChannel(org.apache.sshd.common.session.Session session)
              throws IOException {
            if (rejectFirst && rejectionUsed.compareAndSet(false, true)) return null;
            if (!allocatedStreams.compareAndSet(0, 1)) {
              return null;
            }
            Channel channel;
            try {
              channel = delegate.createChannel(session);
            } catch (IOException failure) {
              allocatedStreams.decrementAndGet();
              throw failure;
            }
            if (channel == null) {
              allocatedStreams.decrementAndGet();
              return null;
            }
            latestChannel.set(channel);
            if (firstChannel.compareAndSet(null, channel)) {
              firstStreamCreated.countDown();
            }
            channel.addChannelListener(new ChannelListener() {
              @Override
              public void channelClosed(Channel closed, Throwable reason) {
                allocatedStreams.decrementAndGet();
                if (closed == firstChannel.get()) {
                  firstStreamClosed.countDown();
                }
              }
            });
            return channel;
          }
        });
      }
      if (sessionFactories != 1) {
        throw new IllegalStateException("Expected one session channel factory");
      }
      server.setChannelFactories(factories);
    }

    private void installHeldReplySessionFactory(SshServer server) {
      server.setSessionFactory(new SessionFactory(server) {
        @Override
        protected ServerSessionImpl doCreateSession(IoSession ioSession) throws Exception {
          ServerSessionImpl created = new ServerSessionImpl(server, ioSession) {
            @Override
            protected void doHandleMessage(Buffer buffer) throws Exception {
              int message = buffer.rawByte(buffer.rpos()) & 0xff;
              if (message == (SshConstants.SSH_MSG_CHANNEL_CLOSE & 0xff)) {
                int position = buffer.rpos();
                buffer.getByte();
                closeRecipients.add(buffer.getUInt());
                buffer.rpos(position);
                receivedCloseCount.incrementAndGet();
              }
              if (message == (SshConstants.SSH_MSG_GLOBAL_REQUEST & 0xff))
                keepAliveReceived.countDown();
              super.doHandleMessage(buffer);
            }
            @Override
            public IoWriteFuture writePacket(Buffer buffer) throws IOException {
              int message = buffer.rawByte(buffer.rpos()) & 0xff;
              if (message == (SshConstants.SSH_MSG_KEXINIT & 0xff)
                  && holdRenegotiation.compareAndSet(true, false)) {
                PendingWriteFuture pending = new PendingWriteFuture(this);
                heldRenegotiation.set(pending);
                renegotiationPacket.set(buffer.getCompactData());
                renegotiationHeld.countDown();
                return pending;
              }
              if (message == ((rejectFirst ? SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE : SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION) & 0xff)
                  && heldReply.get() == null) {
                PendingWriteFuture candidate = new PendingWriteFuture(this);
                if (heldReply.compareAndSet(null, candidate)) {
                  heldPacket.set(buffer.getCompactData());
                  openReplyHeld.countDown();
                  return candidate;
                }
              }
              return super.writePacket(buffer);
            }
          };
          created.addCloseFutureListener(closed -> connectionClosed.countDown());
          serverSession.compareAndSet(null, created);
          return created;
        }
      });
    }

    private void prepareReply() {
      heldReply.set(null);
      heldPacket.set(null);
      openReplyHeld = new CountDownLatch(1);
      renegotiationHeld = new CountDownLatch(1);
      keepAliveReceived = new CountDownLatch(1);
    }

    private void sendReaderMarker() throws Exception {
      ServerSessionImpl current = serverSession.get();
      Buffer marker = current.createBuffer(SshConstants.SSH_MSG_UNIMPLEMENTED, 4);
      marker.putInt(12345);
      assertTrue(current.writePacket(marker).await(5, TimeUnit.SECONDS), "Reader marker write did not finish");
    }

    private void releaseRenegotiation() throws Exception {
      ServerSessionImpl current = serverSession.get();
      byte[] packet = renegotiationPacket.get();
      Buffer replay = current.createBuffer(packet[0], packet.length);
      replay.putRawBytes(packet, 1, packet.length - 1);
      IoWriteFuture actual = current.writePacket(replay);
      actual.addListener((SshFutureListener<IoWriteFuture>) completed ->
          heldRenegotiation.get().setValue(completed.isWritten() ? Boolean.TRUE : completed.getException()));
      assertTrue(actual.await(5, TimeUnit.SECONDS), "Peer renegotiation reply did not finish");
      assertTrue(actual.isWritten(), "Peer renegotiation reply was not written");
    }

    private void releaseHeldOpenReply() throws Exception {
      ServerSessionImpl currentSession = serverSession.get();
      byte[] packet = heldPacket.get();
      PendingWriteFuture pending = heldReply.get();
      if (currentSession == null || packet == null || pending == null) {
        throw new IllegalStateException("The peer did not retain the open acknowledgement");
      }
      Buffer replay = currentSession.createBuffer(packet[0], packet.length);
      replay.putRawBytes(packet, 1, packet.length - 1);
      IoWriteFuture actualWrite = currentSession.writePacket(replay);
      CountDownLatch writeCompleted = new CountDownLatch(1);
      actualWrite.addListener((SshFutureListener<IoWriteFuture>) completed -> {
        pending.setValue(completed.isWritten() ? Boolean.TRUE
            : completed.getException() == null
                ? new IOException("The delayed open acknowledgement was not written")
                : completed.getException());
        writeCompleted.countDown();
      });
      if (!writeCompleted.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("The delayed open acknowledgement write did not complete");
      }
      assertTrue(actualWrite.isWritten(), "The delayed open acknowledgement did not reach the peer socket");
      assertTrue(pending.isWritten(), "The held open acknowledgement did not complete after replay");
    }
  }

  private static final class PendingWriteFuture extends AbstractIoWriteFuture {
    private PendingWriteFuture(Object owner) {
      super(owner, "held-open-acknowledgement");
    }
  }

  private static final class ResponseCommand implements Command {
    private final AtomicReference<Throwable> commandFailure;
    private OutputStream output;
    private ExitCallback exitCallback;

    private final boolean holdOpen;

    private ResponseCommand(AtomicReference<Throwable> commandFailure, boolean holdOpen) {
      this.commandFailure = commandFailure;
      this.holdOpen = holdOpen;
    }

    @Override
    public void setInputStream(InputStream input) {}

    @Override
    public void setOutputStream(OutputStream stream) {
      output = stream;
    }

    @Override
    public void setErrorStream(OutputStream stream) {}

    @Override
    public void setExitCallback(ExitCallback callback) {
      exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, org.apache.sshd.server.Environment environment) {
      try {
        output.write(FRESH_RESPONSE);
        output.flush();
        if (!holdOpen) exitCallback.onExit(0);
      } catch (IOException failure) {
        commandFailure.compareAndSet(null, failure);
        exitCallback.onExit(1, "The peer could not write the test response");
      }
    }

    @Override
    public void destroy(ChannelSession channel) {}
  }
}
