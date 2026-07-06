import { request } from "./client";
import type { AuthResponse, LoginRequest, RegisterRequest } from "./types";

export function register(data: RegisterRequest): Promise<AuthResponse> {
  return request<AuthResponse>("/api/auth/register", { method: "POST", body: data });
}

export function login(data: LoginRequest): Promise<AuthResponse> {
  return request<AuthResponse>("/api/auth/login", { method: "POST", body: data });
}
