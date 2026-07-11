package com.healthtech.doctor.config;

import com.healthtech.doctor.domain.Address;
import com.healthtech.doctor.domain.Doctor;
import com.healthtech.doctor.domain.Language;
import com.healthtech.doctor.domain.OpeningHours;
import com.healthtech.doctor.domain.Specialty;
import com.healthtech.doctor.event.DoctorRegistered;
import com.healthtech.doctor.event.OpeningHoursData;
import com.healthtech.doctor.repository.DoctorRepository;
import com.healthtech.doctor.repository.SpecialtyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Demo doctors carry the SAME password (documented in README) so a reviewer can log in as
// any of them; each publishes doctor.registered on creation because appointment-service's
// booking/availability paths hard-require a ValidDoctor read-model row (see AppointmentService),
// so a seeded doctor that skipped the event would 404 on every booking attempt.
@Component
@Order(2)
@RequiredArgsConstructor
public class DoctorSeeder implements CommandLineRunner {

    public static final String DEMO_PASSWORD = "demo12345";

    private final DoctorRepository doctorRepository;
    private final SpecialtyRepository specialtyRepository;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, DoctorRegistered> kafkaTemplate;

    private record DemoDoctor(String firstName, String lastName, String email, String city,
                               List<String> specialtyNames, Set<Language> languages,
                               Set<DayOfWeek> openDays) {
    }

    @Override
    public void run(String... args) {
        List<DemoDoctor> demoDoctors = List.of(
                new DemoDoctor("Anna", "Weber", "anna.weber@demo.healthtech.com", "Berlin",
                        List.of("General Practice", "Cardiology"),
                        Set.of(Language.GERMAN, Language.ENGLISH),
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)),
                new DemoDoctor("Mehmet", "Yilmaz", "mehmet.yilmaz@demo.healthtech.com", "Cologne",
                        List.of("Cardiology", "Orthopedics"),
                        Set.of(Language.TURKISH, Language.GERMAN),
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
                new DemoDoctor("Sophie", "Dubois", "sophie.dubois@demo.healthtech.com", "Munich",
                        List.of("Dermatology", "Gynecology"),
                        Set.of(Language.FRENCH, Language.ENGLISH),
                        Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)),
                new DemoDoctor("Elena", "Rossi", "elena.rossi@demo.healthtech.com", "Hamburg",
                        List.of("Pediatrics", "Dermatology", "Gynecology"),
                        Set.of(Language.ITALIAN, Language.SPANISH),
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
                new DemoDoctor("Omar", "Haddad", "omar.haddad@demo.healthtech.com", "Frankfurt",
                        List.of("Neurology", "General Practice"),
                        Set.of(Language.ARABIC, Language.PERSIAN, Language.ENGLISH),
                        Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
                new DemoDoctor("Katarina", "Petrov", "katarina.petrov@demo.healthtech.com", "Stuttgart",
                        List.of("Orthopedics", "Neurology", "Pediatrics"),
                        Set.of(Language.RUSSIAN, Language.ENGLISH),
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        );

        demoDoctors.forEach(this::seedIfAbsent);
    }

    private void seedIfAbsent(DemoDoctor demo) {
        if (doctorRepository.findByEmail(demo.email()).isPresent()) {
            return;
        }

        Set<Specialty> specialties = demo.specialtyNames().stream()
                .map(name -> specialtyRepository.findByName(name)
                        .orElseThrow(() -> new IllegalStateException(
                                "DoctorSeeder requires specialty '" + name + "' to already exist; SpecialtySeeder must run first")))
                .collect(Collectors.toSet());

        Set<OpeningHours> openingHours = demo.openDays().stream()
                .map(day -> OpeningHours.builder()
                        .dayOfWeek(day)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build())
                .collect(Collectors.toSet());

        Doctor doctor = Doctor.builder()
                .firstName(demo.firstName())
                .lastName(demo.lastName())
                .email(demo.email())
                .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                .phoneNumber("+49 30 5550100")
                .address(Address.builder()
                        .street("Hauptstrasse")
                        .houseNumber("1")
                        .postalCode("10115")
                        .city(demo.city())
                        .country("Germany")
                        .build())
                .specialties(specialties)
                .openingHours(openingHours)
                .languages(demo.languages())
                .build();

        Doctor saved = doctorRepository.saveAndFlush(doctor);

        Set<OpeningHoursData> openingHoursData = saved.getOpeningHours().stream()
                .map(oh -> OpeningHoursData.builder()
                        .dayOfWeek(oh.getDayOfWeek())
                        .startTime(oh.getStartTime())
                        .endTime(oh.getEndTime())
                        .build())
                .collect(Collectors.toSet());

        DoctorRegistered event = DoctorRegistered.builder()
                .eventId(UUID.randomUUID())
                .doctorId(saved.getId())
                .firstName(saved.getFirstName())
                .lastName(saved.getLastName())
                .openingHours(openingHoursData)
                .registeredAt(saved.getRegisteredAt())
                .build();
        kafkaTemplate.send("doctor.registered", event);
    }
}
