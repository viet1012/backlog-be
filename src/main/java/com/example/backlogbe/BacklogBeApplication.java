package com.example.backlogbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class BacklogBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                BacklogBeApplication.class,
                args
        );
    }
}