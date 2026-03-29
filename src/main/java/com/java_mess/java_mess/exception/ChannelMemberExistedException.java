package com.java_mess.java_mess.exception;

public class ChannelMemberExistedException extends RuntimeException {
    public ChannelMemberExistedException() {
        super("Channel member already exists");
    }
}
