package com.puppy.model;

import java.util.List;

public record StoredConversation(String id, String title, long updatedAt, String titleState,
                                 List<StoredMessage> messages, List<DocumentAttachment> attachments) {
    public StoredConversation {
        messages = List.copyOf(messages);
        attachments = List.copyOf(attachments);
    }
}
