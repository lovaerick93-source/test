export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/static/downloads/")) {
      return Response.redirect("https://github.com/lovaerick93-source/test/releases/latest", 302);
    }
    if (url.pathname.startsWith("/static/")) {
      url.pathname = url.pathname.slice("/static".length);
      return env.ASSETS.fetch(url.toString());
    }
    return env.ASSETS.fetch(request);
  },
};
