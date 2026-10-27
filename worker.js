const RELEASE_BASE = "https://github.com/lovaerick93-source/test/releases/latest/download/";

const ASSET_MAP = {
  "remote-control-agent-release.apk": "android-agent-release-unsigned.apk",
  "remote-control-desktop.jar": "remote-control-desktop.jar",
  "remote-control-desktop.exe": "remote-control-desktop.exe",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/static\/downloads\/(.+)$/);
    if (m) {
      const name = m[1];
      const asset = ASSET_MAP[name];
      if (asset) {
        const upstream = await fetch(RELEASE_BASE + asset, { redirect: "follow" });
        const headers = new Headers(upstream.headers);
        headers.set("Content-Disposition", `attachment; filename="${name}"`);
        return new Response(upstream.body, { status: upstream.status, headers });
      }
      return new Response("Not found", { status: 404 });
    }
    if (url.pathname.startsWith("/static/")) {
      url.pathname = url.pathname.slice("/static".length);
      return env.ASSETS.fetch(url.toString());
    }
    return env.ASSETS.fetch(request);
  },
};
