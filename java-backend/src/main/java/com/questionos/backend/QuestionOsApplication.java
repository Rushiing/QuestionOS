package com.questionos.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuestionOsApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuestionOsApplication.class, args);
    }
}
