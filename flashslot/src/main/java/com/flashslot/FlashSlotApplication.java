package com.flashslot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FlashSlotApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.stream(args).anyMatch(arg -> arg.equals("--bootstrap-admin") || arg.startsWith("--bootstrap-admin="))) {
            System.exit(com.flashslot.auth.bootstrap.AdminBootstrap.run(
                    args, System.getenv(), System.console(), System.out, System.err));
            return;
        }
        SpringApplication.run(FlashSlotApplication.class, args);
    }
}
