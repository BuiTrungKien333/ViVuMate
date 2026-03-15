package com.vivumate.coreapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ViVuMateApplication {

	public static void main(String[] args) {
		System.out.println("Hello World");
		SpringApplication.run(ViVuMateApplication.class, args);
	}

}
