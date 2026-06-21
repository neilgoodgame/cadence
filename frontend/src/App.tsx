import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { AppShell } from "./layout/AppShell";
import { ActivitiesScreen } from "./screens/ActivitiesScreen";
import { ActivityAnalysisScreen } from "./screens/ActivityAnalysisScreen";
import { DashboardScreen } from "./screens/DashboardScreen";
import { LoginScreen } from "./screens/LoginScreen";
import { PreferencesScreen } from "./screens/PreferencesScreen";

const queryClient = new QueryClient();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginScreen />} />
            <Route element={<RequireAuth />}>
              <Route element={<AppShell />}>
                <Route path="/" element={<DashboardScreen />} />
                <Route path="/activities" element={<ActivitiesScreen />} />
                <Route path="/activities/:id" element={<ActivityAnalysisScreen />} />
                <Route path="/preferences" element={<PreferencesScreen />} />
              </Route>
            </Route>
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
