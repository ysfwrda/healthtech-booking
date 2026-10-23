import { request } from "./client";
import type {
  AvailabilityResponse,
  OpeningHoursDto,
  OpeningHoursUpdateRequest,
  VacationDto,
  VacationRequest,
} from "./types";

export function getMyAvailability(): Promise<AvailabilityResponse> {
  return request<AvailabilityResponse>("/api/doctors/me/availability", { asDoctor: true });
}

export function replaceOpeningHours(openingHours: OpeningHoursDto[]): Promise<AvailabilityResponse> {
  const body: OpeningHoursUpdateRequest = { openingHours };
  return request<AvailabilityResponse>("/api/doctors/me/availability/opening-hours", {
    method: "PUT",
    body,
    asDoctor: true,
  });
}

export function addVacation(data: VacationRequest): Promise<VacationDto> {
  return request<VacationDto>("/api/doctors/me/availability/vacations", { method: "POST", body: data, asDoctor: true });
}

export function removeVacation(id: string): Promise<void> {
  return request<void>(`/api/doctors/me/availability/vacations/${id}`, { method: "DELETE", asDoctor: true });
}
