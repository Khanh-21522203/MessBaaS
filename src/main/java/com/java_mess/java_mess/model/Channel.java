package com.java_mess.java_mess.model;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel {
    private String id;
    
    private String name;

    private String clientReferenceId;

    private Instant createdAt;
}
