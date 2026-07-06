import { request } from "./client";
import type { AppointmentRequest, AppointmentResponse } from "./types";

export function bookAppointment(data: AppointmentRequest): Promise<AppointmentResponse> {
  return request<AppointmentResponse>("/api/appointments", { method: "POST", body: data, auth: true });
}

export function getMyAppointments(): Promise<AppointmentResponse[]> {
  return request<AppointmentResponse[]>("/api/appointments", { auth: true });
}

export function cancelAppointment(id: string): Promise<AppointmentResponse> {
  return request<AppointmentResponse>(`/api/appointments/${id}/cancel`, { method: "PUT", auth: true });
}
