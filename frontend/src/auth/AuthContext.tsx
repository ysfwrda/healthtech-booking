import { createContext, useContext, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";
import { clearSession, getUsername, setSession } from "../api/client";
import type { LoginRequest, RegisterRequest } from "../api/types";

interface AuthContextValue {
  username: string | null;
  isAuthenticated: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(getUsername());

  async function login(data: LoginRequest): Promise<void> {
    const response = await authApi.login(data);
    setSession(response.token, response.username);
    setUsername(response.username);
  }

  async function register(data: RegisterRequest): Promise<void> {
    const response = await authApi.register(data);
    setSession(response.token, response.username);
    setUsername(response.username);
  }

  function logout(): void {
    clearSession();
    setUsername(null);
  }

  const value: AuthContextValue = {
    username,
    isAuthenticated: username !== null,
    login,
    register,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
