import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ToastProvider } from "./ui/Toast";

/*
  app.css is READ from where the legacy studio lives, never copied and never
  edited -- static/{index.html,app.js,app.css} stay byte-for-byte untouched
  forever; / keeps serving the original UI, this bundle is a permanent
  parallel interface at /ui/, not a migration that eventually deletes the
  old one. Two drifting copies of 957 lines would be worse than an import
  that reaches outside this folder.

  theme.css loads AFTER app.css on purpose: it only adds native-element
  resets for classes app.css already styles (a React <button> where app.js
  used a <span> + click listener) and a small set of new :focus-visible
  rules -- the "dar modernizasyon" scope. It never overrides an app.css
  rule outright, but load order is the contract that keeps it additive.
*/
import "../../resources/static/app.css";
import "./theme.css";
import "./root.css";

const container = document.getElementById("root");
if (!container) throw new Error("#root bulunamadı");

/*
  StrictMode is on deliberately, and it will hurt in step 3: it double-invokes
  effects in development, so a polling hook that does not clean up after itself
  starts two timer chains and shows it immediately. That is the bug we most
  want to find early -- the run poll is the one piece of machinery that already
  had a lifecycle bug in app.js. It has no effect on the production build.
*/
createRoot(container).render(
  <StrictMode>
    <ToastProvider>
      <App />
    </ToastProvider>
  </StrictMode>,
);
