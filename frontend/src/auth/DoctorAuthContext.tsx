import { createContext, useContext, useState, type ReactNode } from "react";
import * as doctorAuthApi from "../api/doctorAuth";
import { clearDoctorSession, getDoctorEmail, setDoctorSession } from "../api/client";
import type { DoctorLoginRequest, DoctorRegistrationRequest } from "../api/types";

interface DoctorAuthContextValue {
  doctorEmail: string | null;
  isDoctorAuthenticated: boolean;
  registerDoctor: (data: DoctorRegistrationRequest) => Promise<void>;
  loginDoctor: (data: DoctorLoginRequest) => Promise<void>;
  logoutDoctor: () => void;
}

const DoctorAuthContext = createContext<DoctorAuthContextValue | undefined>(undefined);

export function DoctorAuthProvider({ children }: { children: ReactNode }) {
  const [doctorEmail, setDoctorEmail] = useState<string | null>(getDoctorEmail());

  async function registerDoctor(data: DoctorRegistrationRequest): Promise<void> {
    const response = await doctorAuthApi.registerDoctor(data);
    setDoctorSession(response.token, response.email);
    setDoctorEmail(response.email);
  }

  async function loginDoctor(data: DoctorLoginRequest): Promise<void> {
    const response = await doctorAuthApi.loginDoctor(data);
    setDoctorSession(response.token, response.email);
    setDoctorEmail(response.email);
  }

  function logoutDoctor(): void {
    clearDoctorSession();
    setDoctorEmail(null);
  }

  const value: DoctorAuthContextValue = {
    doctorEmail,
    isDoctorAuthenticated: doctorEmail !== null,
    registerDoctor,
    loginDoctor,
    logoutDoctor,
  };

  return <DoctorAuthContext.Provider value={value}>{children}</DoctorAuthContext.Provider>;
}

export function useDoctorAuth(): DoctorAuthContextValue {
  const ctx = useContext(DoctorAuthContext);
  if (!ctx) {
    throw new Error("useDoctorAuth must be used within DoctorAuthProvider");
  }
  return ctx;
}
