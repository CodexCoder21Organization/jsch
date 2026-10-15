package com.jcraft.jsch;

/** An explicit SSH_MSG_CHANNEL_OPEN_FAILURE reply from the peer. */
public class JSchChannelOpenException extends JSchException {
  private static final long serialVersionUID = 1L;
  private final int reasonCode;

  public JSchChannelOpenException(int reasonCode) {
    super("channel is not opened.");
    this.reasonCode = reasonCode;
  }

  /** Returns the peer's uint32 reason code as its signed Java int representation. */
  public int getReasonCode() {
    return reasonCode;
  }
}
