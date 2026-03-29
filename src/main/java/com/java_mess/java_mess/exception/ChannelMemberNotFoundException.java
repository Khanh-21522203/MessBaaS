package com.java_mess.java_mess.exception;

public class ChannelMemberNotFoundException extends RuntimeException {
    public ChannelMemberNotFoundException() {
        super("Channel member not found");
    }
}
