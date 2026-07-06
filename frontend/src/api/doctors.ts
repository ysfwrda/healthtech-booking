import { request } from "./client";
import type { DoctorResponse, DoctorSummaryResponse, Language, SpecialtyDto } from "./types";

export function searchDoctors(params: { specialty?: string; language?: Language }): Promise<DoctorSummaryResponse[]> {
  const query = new URLSearchParams();
  if (params.specialty) query.set("specialty", params.specialty);
  if (params.language) query.set("language", params.language);
  const qs = query.toString();
  return request<DoctorSummaryResponse[]>(`/api/doctors${qs ? `?${qs}` : ""}`);
}

export function getDoctor(id: string): Promise<DoctorResponse> {
  return request<DoctorResponse>(`/api/doctors/${id}`);
}

export function getSpecialties(): Promise<SpecialtyDto[]> {
  return request<SpecialtyDto[]>("/api/specialties");
}
