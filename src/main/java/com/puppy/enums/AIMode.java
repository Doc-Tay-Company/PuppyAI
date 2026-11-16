package com.puppy.enums;

/**
 * Where Puppy AI should get its model responses from.
 *
 * <p>Local Ollama chat is the only connected provider today. This enum is
 * reserved for cloud/offline/auto routing in a later milestone.</p>
 */
public enum AIMode {
    ONLINE,
    OFFLINE,
    AUTO
}
