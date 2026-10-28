export type InsuranceType = "PRIVATE" | "STATUTORY";

export type Language =
  | "GERMAN"
  | "ENGLISH"
  | "SPANISH"
  | "FRENCH"
  | "TURKISH"
  | "ITALIAN"
  | "ARABIC"
  | "RUSSIAN"
  | "PERSIAN";

export type DayOfWeek =
  | "MONDAY"
  | "TUESDAY"
  | "WEDNESDAY"
  | "THURSDAY"
  | "FRIDAY"
  | "SATURDAY"
  | "SUNDAY";

export type AppointmentType = "INITIAL_CONSULTATION" | "FOLLOW_UP" | "VACCINATION";

export type AppointmentStatus = "PENDING" | "CONFIRMED" | "CANCELLED";

// Why a booked appointment no longer fits the doctor's availability; absent or null when it fits.
export type ConflictReason = "OUTSIDE_OPENING_HOURS" | "DOCTOR_VACATION";

export interface RegisterRequest {
  firstName: string;
  lastName: string;
  username: string;
  password: string;
  dateOfBirth: string;
  email: string;
  insuranceType: InsuranceType;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
  expiresIn: number;
  username: string;
}

export interface SpecialtyDto {
  id: string;
  name: string;
}

export interface OpeningHoursDto {
  dayOfWeek: DayOfWeek;
  startTime: string;
  endTime: string;
}

export interface AddressDto {
  street: string;
  houseNumber: string;
  postalCode: string;
  city: string;
  state?: string;
  country: string;
}

export interface DoctorSummaryResponse {
  id: string;
  firstName: string;
  lastName: string;
  specialties: SpecialtyDto[];
  languages: Language[];
  postalCode: string;
  city: string;
}

export interface DoctorResponse {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  address: AddressDto;
  specialties: SpecialtyDto[];
  openingHours: OpeningHoursDto[];
  languages: Language[];
  registeredAt: string;
}

export interface DoctorRegistrationRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phoneNumber: string;
  address: AddressDto;
  specialtyIds: string[];
  openingHours: OpeningHoursDto[];
  languages: Language[];
}

export interface DoctorLoginRequest {
  email: string;
  password: string;
}

export interface DoctorAuthResponse {
  id: string;
  token: string;
  expiresIn: number;
  email: string;
}

export interface AvailableSlotsResponse {
  doctorId: string;
  date: string;
  availableSlots: string[];
}

export interface AppointmentRequest {
  doctorId: string;
  dateTime: string;
  type: AppointmentType;
  notes?: string;
}

export interface AppointmentUpdateRequest {
  type?: AppointmentType;
  dateTime?: string;
  notes?: string;
}

export interface AppointmentResponse {
  id: string;
  patientId: string;
  doctorId: string;
  dateTime: string;
  duration: number;
  type: AppointmentType;
  status: AppointmentStatus;
  conflictReason?: ConflictReason | null;
  notes?: string;
  createdAt: string;
}

export interface DoctorAppointmentResponse {
  id: string;
  patientId: string;
  patientName?: string | null;
  patientEmail?: string | null;
  doctorId: string;
  dateTime: string;
  duration: number;
  type: AppointmentType;
  status: AppointmentStatus;
  conflictReason?: ConflictReason | null;
  notes?: string;
  createdAt: string;
}

// A doctor changes the type and/or the time, never the patient's notes.
export interface DoctorAppointmentUpdateRequest {
  type?: AppointmentType;
  dateTime?: string;
}

export interface VacationDto {
  id: string;
  startDate: string;
  endDate: string;
}

export interface VacationRequest {
  startDate: string;
  endDate: string;
}

export interface AvailabilityResponse {
  openingHours: OpeningHoursDto[];
  vacations: VacationDto[];
}

export interface OpeningHoursUpdateRequest {
  openingHours: OpeningHoursDto[];
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Record<string, string>;
}
