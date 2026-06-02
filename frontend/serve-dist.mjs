import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.join(__dirname, 'dist');
const host = process.env.HOST || '127.0.0.1';
const port = Number(process.env.PORT || 5174);
const apiTarget = new URL(process.env.API_TARGET || 'http://127.0.0.1:8090');

const mimeTypes = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'],
  ['.ico', 'image/x-icon'],
  ['.mp4', 'video/mp4'],
  ['.map', 'application/json; charset=utf-8']
]);

function send(res, statusCode, body, headers = {}) {
  res.writeHead(statusCode, headers);
  res.end(body);
}

function serveFile(req, res, pathname) {
  const rawPath = pathname === '/' ? '/index.html' : pathname;
  const normalized = path.normalize(decodeURIComponent(rawPath)).replace(/^(\.\.[/\\])+/, '');
  const absolutePath = path.join(distDir, normalized);

  if (!absolutePath.startsWith(distDir)) {
    send(res, 403, 'Forbidden');
    return;
  }

  const filePath = fs.existsSync(absolutePath) && fs.statSync(absolutePath).isFile()
    ? absolutePath
    : path.join(distDir, 'index.html');

  fs.stat(filePath, (statError, stat) => {
    if (statError) {
      send(res, 404, 'Not found');
      return;
    }

    const ext = path.extname(filePath);
    const headers = {
      'Content-Type': mimeTypes.get(ext) || 'application/octet-stream',
      'Content-Length': stat.size
    };
    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  });
}

function proxyApi(req, res) {
  const targetUrl = new URL(req.url || '/', apiTarget);
  const proxyReq = http.request(
    {
      protocol: apiTarget.protocol,
      hostname: apiTarget.hostname,
      port: apiTarget.port,
      method: req.method,
      path: `${targetUrl.pathname}${targetUrl.search}`,
      headers: {
        ...req.headers,
        host: apiTarget.host
      }
    },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
      proxyRes.pipe(res);
    }
  );

  proxyReq.on('error', (error) => {
    send(res, 502, `Proxy error: ${error.message}`, { 'Content-Type': 'text/plain; charset=utf-8' });
  });

  req.pipe(proxyReq);
}

if (!fs.existsSync(path.join(distDir, 'index.html'))) {
  console.error('dist/index.html not found. Run npm run build first.');
  process.exit(1);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || `${host}:${port}`}`);
  if (url.pathname.startsWith('/api/')) {
    proxyApi(req, res);
    return;
  }
  serveFile(req, res, url.pathname);
});

server.listen(port, host, () => {
  console.log(`Local app server: http://${host}:${port}/`);
  console.log(`Proxying /api to ${apiTarget.origin}`);
});
