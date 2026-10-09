import { request } from "./client";
import type { AppointmentRequest, AppointmentResponse, AppointmentUpdateRequest } from "./types";

export function bookAppointment(data: AppointmentRequest): Promise<AppointmentResponse> {
  return request<AppointmentResponse>("/api/appointments", { method: "POST", body: data, auth: true });
}

export function getMyAppointments(): Promise<AppointmentResponse[]> {
  return request<AppointmentResponse[]>("/api/appointments", { auth: true });
}

export function updateAppointment(id: string, data: AppointmentUpdateRequest): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/${id}`, { method: "PATCH", body: data, auth: true });
}

export function cancelAppointment(id: string): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/${id}/cancel`, { method: "PUT", auth: true });
}
