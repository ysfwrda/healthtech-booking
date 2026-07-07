package com.healthtech.doctor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// RsaKeyProperties is enabled on JwtDecoderConfig, not here, so it is only bound
// when that configuration class is actually loaded (see JwtDecoderConfig for why).
@SpringBootApplication
public class DoctorServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DoctorServiceApplication.class, args);
	}

}
