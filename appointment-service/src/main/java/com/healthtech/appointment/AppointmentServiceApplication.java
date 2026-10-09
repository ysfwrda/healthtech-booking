package com.healthtech.appointment;

import com.healthtech.appointment.config.ChangePolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// RsaKeyProperties is enabled on JwtDecoderConfig, not here, so it is only bound
// when that configuration class is actually loaded (see JwtDecoderConfig for why).
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ChangePolicyProperties.class)
public class AppointmentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppointmentServiceApplication.class, args);
	}

}
