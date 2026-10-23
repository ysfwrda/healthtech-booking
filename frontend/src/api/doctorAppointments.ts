import { request } from "./client";
import type {
  AppointmentResponse,
  AppointmentStatus,
  DoctorAppointmentResponse,
  DoctorAppointmentUpdateRequest,
} from "./types";

export interface DoctorAppointmentFilters {
  // Whole days, both inclusive (YYYY-MM-DD); empty means no limit.
  from?: string;
  to?: string;
  // Empty means every status.
  statuses?: AppointmentStatus[];
}

export function getDoctorAppointments(filters: DoctorAppointmentFilters = {}): Promise<DoctorAppointmentResponse[]> {
  const query = new URLSearchParams();
  if (filters.from) {
    query.set("from", filters.from);
  }
  if (filters.to) {
    query.set("to", filters.to);
  }
  filters.statuses?.forEach((status) => query.append("status", status));
  const suffix = query.size > 0 ? `?${query.toString()}` : "";
  return request<DoctorAppointmentResponse[]>(`/api/appointments/doctor${suffix}`, { asDoctor: true });
}

export function confirmAppointment(id: string): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/doctor/${id}/confirm`, { method: "PUT", asDoctor: true });
}

export function cancelAppointmentAsDoctor(id: string): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/doctor/${id}/cancel`, { method: "PUT", asDoctor: true });
}

export function updateAppointmentAsDoctor(id: string, data: DoctorAppointmentUpdateRequest): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/doctor/${id}`, { method: "PATCH", body: data, asDoctor: true });
}
