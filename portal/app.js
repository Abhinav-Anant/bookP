// Shell: Supabase client, session, routing, and the pages every account sees.
import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.117.2/+esm";
import { SUPABASE_URL, SUPABASE_ANON_KEY } from "./config.js";
import { escapeHtml } from "./lib.js";

export const sb = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

/** The signed-in user and their profile row, or null. */
let me = null;
export const current = () => me;
export const isAdmin = () => me?.profile.role === "admin";
export const isAuthor = () => ["author", "admin"].includes(me?.profile.role);

const routes = [];
/** Registers a page for hashes matching `pattern`; `allow` decides who may open it. */
export function route(pattern, render, allow = () => true) {
  routes.push({ pattern, render, allow });
}

/** HTML template: values are escaped unless wrapped in raw(). Arrays are joined. */
export const raw = (html) => ({ raw: html });
export function h(strings, ...values) {
  const out = (v) => v == null || v === false ? "" : Array.isArray(v) ? v.map(out).join("")
    : typeof v === "object" && "raw" in v ? v.raw : escapeHtml(v);
  return raw(strings.reduce((acc, s, i) => acc + s + (i < values.length ? out(values[i]) : ""), ""));
}
export const show = (el, tpl) => { el.innerHTML = tpl.raw; };

export function toast(message, isError = false) {
  const t = document.getElementById("toast");
  t.textContent = message;
  t.className = isError ? "show error" : "show";
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.className = ""), isError ? 6000 : 3000);
}

/** Unwraps a Supabase result, throwing its error. */
export function must({ data, error }) {
  if (error) throw error;
  return data;
}

/** Runs an action from a button: disables it meanwhile and shows any error. */
export async function act(button, fn) {
  if (button) button.disabled = true;
  try {
    await fn();
  } catch (e) {
    console.error(e);
    toast(e.message ?? String(e), true);
  } finally {
    if (button) button.disabled = false;
  }
}

export const go = (hash) => {
  if (location.hash === hash) render();
  else location.hash = hash;
};

function renderNav() {
  const links = !me ? [] : [
    ["#/", "Home"],
    isAuthor() && ["#/books", "My books"],
    isAdmin() && ["#/admin", "Review"],
    isAdmin() && ["#/admin/books", "All books"],
    isAdmin() && ["#/admin/authors", "Authors"],
    isAdmin() && ["#/admin/users", "Users"],
    isAdmin() && ["#/admin/stats", "Analytics"],
  ].filter(Boolean);
  show(document.getElementById("nav"), h`
    ${links.map(([href, label]) => h`<a href="${href}">${label}</a>`)}
    ${me && h`<button class="link" id="sign-out" title="${me.user.email}">Sign out</button>`}`);
  document.getElementById("sign-out")?.addEventListener("click", () => sb.auth.signOut());
}

export async function render() {
  const view = document.getElementById("view");
  const { data: { session } } = await sb.auth.getSession();
  if (!session) {
    me = null;
    renderNav();
    return signIn(view);
  }
  if (me?.user.id !== session.user.id) {
    const profile = must(await sb.from("profiles").select("*").eq("id", session.user.id).single());
    me = { user: session.user, profile };
  }
  renderNav();
  if (me.profile.blocked) {
    return show(view, h`<section class="card"><h1>Account blocked</h1>
      <p>This account has been blocked. Contact Anant Publication if you think this is a mistake.</p></section>`);
  }
  // A magic-link sign-in lands with its tokens in the hash; that is the home page.
  const hash = location.hash.slice(1);
  const path = !hash || /^(access_token|error)=|&access_token=/.test(hash) ? "/" : hash;
  for (const r of routes) {
    const m = path.match(r.pattern);
    if (!m) continue;
    if (!r.allow()) return show(view, h`<p class="muted">You don't have access to this page.</p>`);
    try {
      return await r.render(view, ...m.slice(1));
    } catch (e) {
      console.error(e);
      return show(view, h`<p class="error">${e.message ?? e}</p>`);
    }
  }
  show(view, h`<p class="muted">Page not found.</p>`);
}

export function start() {
  window.addEventListener("hashchange", render);
  // Supabase must not be called inside this callback, hence the timeout.
  sb.auth.onAuthStateChange((event) => {
    if (event === "SIGNED_IN" || event === "SIGNED_OUT") {
      me = null;
      setTimeout(render);
    }
  });
  render();
}

// Sign in ---------------------------------------------------------------------

function signIn(view) {
  show(view, h`<section class="card narrow">
    <h1>Sign in</h1>
    <p class="muted">Use the same account as the APA app.</p>
    <form id="email-form">
      <label>Email <input type="email" name="email" required autocomplete="email"></label>
      <button class="primary">Send code</button>
    </form>
    <form id="code-form" hidden>
      <label>Code from the email <input name="code" inputmode="numeric" pattern="[0-9]{6,10}" required autocomplete="one-time-code"></label>
      <button class="primary">Sign in</button>
    </form>
    <div class="or">or</div>
    <button id="google">Continue with Google</button>
  </section>`);
  const linkError = new URLSearchParams(location.hash.slice(1)).get("error_description");
  if (linkError) toast(`${linkError}. Ask for a new code.`, true);
  const emailForm = view.querySelector("#email-form");
  const codeForm = view.querySelector("#code-form");
  let email = "";
  emailForm.addEventListener("submit", (e) => {
    e.preventDefault();
    act(e.submitter, async () => {
      email = emailForm.email.value.trim();
      must(await sb.auth.signInWithOtp({ email }));
      emailForm.hidden = true;
      codeForm.hidden = false;
      codeForm.code.focus();
      toast(`Code sent to ${email}`);
    });
  });
  codeForm.addEventListener("submit", (e) => {
    e.preventDefault();
    act(e.submitter, async () => {
      must(await sb.auth.verifyOtp({ email, token: codeForm.code.value.trim(), type: "email" }));
    });
  });
  view.querySelector("#google").addEventListener("click", (e) => act(e.target, async () => {
    must(await sb.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: location.origin + location.pathname },
    }));
  }));
}

// Home: authors and admins get shortcuts, readers get the application ---------

route(/^\/$/, async (view) => {
  if (isAuthor()) {
    return show(view, h`<section class="card">
      <h1>Welcome, ${me.profile.display_name ?? me.user.email}</h1>
      <p><a class="button primary" href="#/book/new">Add a book</a> <a class="button" href="#/books">My books</a></p>
      ${isAdmin() && h`<p><a href="#/admin">Open the review queue</a></p>`}
    </section>`);
  }
  const app = must(await sb.from("author_applications").select("*").eq("user_id", me.user.id).maybeSingle());
  if (app?.status === "pending") {
    return show(view, h`<section class="card"><h1>Application received</h1>
      <p>Thanks, ${app.display_name}. We'll review your application to publish on Anant soon.</p></section>`);
  }
  show(view, h`<section class="card narrow">
    <h1>Publish on Anant</h1>
    ${app?.status === "rejected" && h`<p class="error">Your last application was not approved:
      ${app.rejection_reason ?? "no reason given"}. You can apply again.</p>`}
    <p class="muted">Tell us who you are. Once approved you can upload books for review.</p>
    <form id="apply">
      <label>Author or publisher name <input name="display_name" required maxlength="120"
        value="${app?.display_name ?? me.profile.display_name ?? ""}"></label>
      <label>About you <textarea name="bio" rows="5" maxlength="2000">${app?.bio ?? ""}</textarea></label>
      <button class="primary">Apply</button>
    </form>
  </section>`);
  const form = view.querySelector("#apply");
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    act(e.submitter, async () => {
      const row = {
        user_id: me.user.id,
        display_name: form.display_name.value.trim(),
        bio: form.bio.value.trim() || null,
        status: "pending",
      };
      must(app
        ? await sb.from("author_applications").update(row).eq("user_id", me.user.id)
        : await sb.from("author_applications").insert(row));
      render();
    });
  });
});
