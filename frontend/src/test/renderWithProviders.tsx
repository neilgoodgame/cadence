import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";

/** Wraps a component under test with the two providers every screen/card in this app needs
 * (QueryClientProvider, a router). `path`/`route` are only needed when the component itself
 * reads route params via useParams - e.g. "/thresholds/:field" / "/thresholds/ftp" for
 * ThresholdHistoryScreen. A fresh QueryClient per call keeps tests isolated from each other's
 * cache. retry: false keeps a mocked rejection from retrying and slowing the test down. */
export function renderWithProviders(ui: ReactElement, options: { route?: string; path?: string } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { route = "/", path } = options;
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        {path ? (
          <Routes>
            <Route path={path} element={ui} />
          </Routes>
        ) : (
          ui
        )}
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
