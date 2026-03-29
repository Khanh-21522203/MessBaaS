package com.java_mess.java_mess.exception;

public class ChannelExistedException extends RuntimeException{
    public ChannelExistedException() {
        super("clientReferenceId already exists");
    }
}
