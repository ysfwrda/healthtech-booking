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

export type AppointmentType = "INITIAL_CONSULTATION" | "FOLLOW_UP" | "VACCINATION";

export type AppointmentStatus = "PENDING" | "CONFIRMED" | "CANCELLED";

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
  dayOfWeek: string;
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

export interface AppointmentResponse {
  id: string;
  patientId: string;
  doctorId: string;
  dateTime: string;
  duration: number;
  type: AppointmentType;
  status: AppointmentStatus;
  notes?: string;
  createdAt: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}
