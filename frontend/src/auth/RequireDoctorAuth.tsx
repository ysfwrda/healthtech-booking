import { Navigate, Outlet } from "react-router-dom";
import { useDoctorAuth } from "./DoctorAuthContext";

export function RequireDoctorAuth() {
  const { isDoctorAuthenticated } = useDoctorAuth();

  if (!isDoctorAuthenticated) {
    return <Navigate to="/doctors/login" replace />;
  }

  return <Outlet />;
}
