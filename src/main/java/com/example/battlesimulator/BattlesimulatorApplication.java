package com.example.battlesimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching // We'll need this later when we start hitting PokeAPI
public class BattlesimulatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(BattlesimulatorApplication.class, args);
	}

}