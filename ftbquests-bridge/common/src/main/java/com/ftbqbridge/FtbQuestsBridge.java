package com.ftbqbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FtbQuestsBridge {
    public static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private FtbQuestsBridge() {}

    /** Called by each loader once the common mod loads. Wiring added in Task 13. */
    public static void init() {
        LOG.info("[ftbquests-bridge] common init (protocol v{})", Protocol.PROTOCOL_VERSION);
    }
}
