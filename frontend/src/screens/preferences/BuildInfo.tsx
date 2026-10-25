import { useQuery } from "@tanstack/react-query";
import { getHealth } from "../../api/health";

interface FrontendVersion {
  build: string;
}

/** Lets you confirm which commit frontend/backend are actually running, without reaching for
 * curl or devtools - same build identifiers deploy-frontend.sh's version.json and GET /healthz
 * already expose. Both calls are best-effort: a 404 (version.json predates this on an
 * un-invalidated CDN edge) or a network hiccup just leaves that half blank rather than erroring. */
export function BuildInfo() {
  const { data: frontend } = useQuery({
    queryKey: ["build-info", "frontend"],
    queryFn: () => fetch("/version.json").then((r) => r.json() as Promise<FrontendVersion>),
    retry: false,
  });
  const { data: backend } = useQuery({
    queryKey: ["build-info", "backend"],
    queryFn: getHealth,
    retry: false,
  });

  if (!frontend && !backend) {
    return null;
  }

  return (
    <div className="mono" style={{ fontSize: 10.5, color: "var(--ink3)", marginTop: 16, lineHeight: 1.6 }}>
      {frontend && <div>frontend {frontend.build}</div>}
      {backend && <div>backend {backend.build}</div>}
    </div>
  );
}
