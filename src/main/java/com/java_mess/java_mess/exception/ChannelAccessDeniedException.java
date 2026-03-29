package com.java_mess.java_mess.exception;

public class ChannelAccessDeniedException extends RuntimeException {
    public ChannelAccessDeniedException() {
        super("User is not a member of channel");
    }
}
