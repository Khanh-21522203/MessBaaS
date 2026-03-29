package com.java_mess.java_mess.exception;

public class ClientUserIdExistedException extends RuntimeException {
    public ClientUserIdExistedException() {
        super("clientUserId already exists");
    }
}
