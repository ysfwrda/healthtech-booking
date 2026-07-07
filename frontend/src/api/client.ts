import { goTo } from "./navigation";
import type {ProblemDetail} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE as string;

const TOKEN_KEY = "healthtech.token";
const USERNAME_KEY = "healthtech.username";

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
}

export class ApiError extends Error {
    status: number;
    title: string;

    constructor(status: number, title: string, detail: string) {
        super(detail || title);
        this.status = status;
        this.title = title;
    }
}

interface RequestOptions {
    method?: string;
    body?: unknown;
    auth?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const {method = "GET", body, auth = false} = options;
    const headers: Record<string, string> = {};

    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
    }

    if (auth) {
        const token = getToken();
        if (!token) {
            clearSession();
            throw new ApiError(401, "Not Authenticated", "Please log in to continue.");
        }
        headers["Authorization"] = `Bearer ${token}`;
    }

    const response = await fetch(`${API_BASE}${path}`, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401 && auth) {
        clearSession();
        goTo("/login");
        throw new ApiError(401, "Session Expired", "Your session has expired. Please log in again.");
    }

    if (!response.ok) {
        const problem = await parseProblemDetail(response);
        throw new ApiError(
            response.status,
            problem.title ?? "Request Failed",
            problem.detail ?? "Something went wrong. Please try again.",
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
