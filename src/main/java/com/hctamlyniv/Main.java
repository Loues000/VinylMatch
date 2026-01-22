package com.hctamlyniv;

import Server.ApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            // Start API server (hosted multi-user OAuth)
            ApiServer.start();

        } catch (Exception e) {
            log.error("Application startup failed", e);
        }
    }
}
