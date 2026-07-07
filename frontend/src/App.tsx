import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { Layout } from "./components/Layout";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { DoctorSearchPage } from "./pages/DoctorSearchPage";
import { DoctorDetailPage } from "./pages/DoctorDetailPage";
import { MyAppointmentsPage } from "./pages/MyAppointmentsPage";
import { useNavigate } from "react-router-dom";
import { useEffect } from "react";
import { setNavigate } from "./api/navigation";

function RouterBridge() {
  const navigate = useNavigate();
  useEffect(() => {
    setNavigate((path) => navigate(path));
  }, [navigate]);
  return null;
}

function App() {
  return (
    <BrowserRouter>
      <RouterBridge />
      <AuthProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Navigate to="/doctors" replace />} />
            <Route path="login" element={<LoginPage />} />
            <Route path="register" element={<RegisterPage />} />
            <Route path="doctors" element={<DoctorSearchPage />} />
            <Route path="doctors/:id" element={<DoctorDetailPage />} />
            <Route element={<RequireAuth />}>
              <Route path="appointments" element={<MyAppointmentsPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/doctors" replace />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
