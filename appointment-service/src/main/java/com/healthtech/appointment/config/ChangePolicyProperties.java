package com.healthtech.appointment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// How long before its start time a patient can still change an appointment.
@ConfigurationProperties(prefix = "appointment.change")
public record ChangePolicyProperties(@DefaultValue("48") int minNoticeHours) {
}
