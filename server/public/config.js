// Dashboard endpoint configuration.
// Kept in its own file so admin.html carries no inline script and can ship a strict
// Content-Security-Policy (script-src 'self' without 'unsafe-inline').
// Must be loaded before admin.js.
window.SADAD_API_BASE_URL = document.querySelector('meta[name="sadad-api-base-url"]')?.content || '';
window.SADAD_SUPABASE_PUBLISHABLE_KEY = document.querySelector('meta[name="sadad-publishable-key"]')?.content || '';
