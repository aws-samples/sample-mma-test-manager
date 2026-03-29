package com.mma.testmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MmaTestManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MmaTestManagerApplication.class, args);
    }
}
