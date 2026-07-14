import { request } from "./client";
import type { DoctorAuthResponse, DoctorLoginRequest, DoctorRegistrationRequest } from "./types";

export function registerDoctor(data: DoctorRegistrationRequest): Promise<DoctorAuthResponse> {
  return request<DoctorAuthResponse>("/api/doctors/register", { method: "POST", body: data });
}

export function loginDoctor(data: DoctorLoginRequest): Promise<DoctorAuthResponse> {
  return request<DoctorAuthResponse>("/api/doctors/login", { method: "POST", body: data });
}
