package com.java_mess.java_mess.exception;

public class ClientMessageConflictException extends RuntimeException {
    public ClientMessageConflictException() {
        super("clientMessageId already exists with different payload");
    }
}
