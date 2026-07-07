import { request } from "./client";
import type { AvailableSlotsResponse } from "./types";

export function getAvailability(doctorId: string, date: string): Promise<AvailableSlotsResponse> {
  const query = new URLSearchParams({ doctorId, date });
  return request<AvailableSlotsResponse>(`/api/availability?${query.toString()}`);
}
