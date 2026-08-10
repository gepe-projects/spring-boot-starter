package com.gepe.app;

import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulith;

@Modulith(sharedModules = "platform")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
