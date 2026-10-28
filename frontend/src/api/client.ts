import { goTo } from "./navigation";
import type {ProblemDetail} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE as string;

const TOKEN_KEY = "healthtech.token";
const USERNAME_KEY = "healthtech.username";

const DOCTOR_TOKEN_KEY = "healthtech.doctor.token";
const DOCTOR_EMAIL_KEY = "healthtech.doctor.email";

type SessionRole = "patient" | "doctor";
const sessionClearedListeners: Record<SessionRole, Set<() => void>> = { patient: new Set(), doctor: new Set() };

// The auth contexts hold the logged-in user in state; they listen here so that a session cleared by a request
// (an expired token) also logs the user out of the UI, not only out of sessionStorage.
export function onSessionCleared(role: SessionRole, listener: () => void): () => void {
    sessionClearedListeners[role].add(listener);
    return () => {
        sessionClearedListeners[role].delete(listener);
    };
}

export function getToken(): string | null {
    return sessionStorage.getItem(TOKEN_KEY);
}


export function getUsername(): string | null {
    return sessionStorage.getItem(USERNAME_KEY);
}

export function setSession(token: string, username: string): void {
    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(USERNAME_KEY, username);
}

export function clearSession(): void {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USERNAME_KEY);
    sessionClearedListeners.patient.forEach((listener) => listener());
}

export function getDoctorToken(): string | null {
    return sessionStorage.getItem(DOCTOR_TOKEN_KEY);
}

export function getDoctorEmail(): string | null {
    return sessionStorage.getItem(DOCTOR_EMAIL_KEY);
}

export function setDoctorSession(token: string, email: string): void {
    sessionStorage.setItem(DOCTOR_TOKEN_KEY, token);
    sessionStorage.setItem(DOCTOR_EMAIL_KEY, email);
}

export function clearDoctorSession(): void {
    sessionStorage.removeItem(DOCTOR_TOKEN_KEY);
    sessionStorage.removeItem(DOCTOR_EMAIL_KEY);
    sessionClearedListeners.doctor.forEach((listener) => listener());
}

export class ApiError extends Error {
    status: number;
    title: string;
    errors?: Record<string, string>;

    constructor(status: number, title: string, detail: string, errors?: Record<string, string>) {
        super(detail || title);
        this.status = status;
        this.title = title;
        this.errors = errors;
    }
}

interface RequestOptions {
    method?: string;
    body?: unknown;
    // Send the patient token.
    auth?: boolean;
    // Send the doctor token instead.
    asDoctor?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const {method = "GET", body, auth = false, asDoctor = false} = options;
    const authenticated = auth || asDoctor;
    const headers: Record<string, string> = {};

    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
    }

    if (authenticated) {
        const token = asDoctor ? getDoctorToken() : getToken();
        if (!token) {
            if (asDoctor) {
                clearDoctorSession();
            } else {
                clearSession();
            }
            throw new ApiError(401, "Not Authenticated", "Please log in to continue.");
        }
        headers["Authorization"] = `Bearer ${token}`;
    }

    const response = await fetch(`${API_BASE}${path}`, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401 && authenticated) {
        if (asDoctor) {
            clearDoctorSession();
            goTo("/doctors/login");
        } else {
            clearSession();
            goTo("/login");
        }
        throw new ApiError(401, "Session Expired", "Your session has expired. Please log in again.");
    }

    if (!response.ok) {
        const problem = await parseProblemDetail(response);
        throw new ApiError(
            response.status,
            problem.title ?? "Request Failed",
            problem.detail ?? "Something went wrong. Please try again.",
            problem.errors,
        );
    }

    if (response.status === 204) {
        return undefined as T;
    }

    return (await response.json()) as T;
}

async function parseProblemDetail(response: Response): Promise<ProblemDetail> {
    try {
        return (await response.json()) as ProblemDetail;
    } catch {
        return {};
    }
}
