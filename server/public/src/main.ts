import "./styles.css";
import { createApi } from "./api";
import { Controller } from "./controller";
import { supabaseSession } from "./session";

const root = document.getElementById("app") as HTMLElement;

// GitHub Pages cannot send frame-ancestors (docs/adr/003-dashboard-hosting.md), so a framed copy
// refuses to run instead of offering its buttons to a clickjacking page.
if (window.top !== window.self) {
  root.textContent = "افتح لوحة سدد في نافذة مستقلة.";
} else {
  const session = supabaseSession();
  let controller: Controller | undefined;
  const api = createApi({
    jwt: async () => (await session.current())?.jwt ?? null,
    onUnauthorized: () => controller?.unauthorized(),
  });
  controller = new Controller(root, session, api);
  void controller.start();
}
