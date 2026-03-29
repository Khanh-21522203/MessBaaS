package com.java_mess.java_mess.exception;

public class ChannelNotFoundException extends RuntimeException{
    public ChannelNotFoundException() {
        super("Channel not found");
    }
}
