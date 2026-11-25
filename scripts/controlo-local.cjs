const { spawn } = require('node:child_process');
const { readFile } = require('node:fs/promises');
const { createServer } = require('node:http');
const { connect } = require('node:net');
const { join } = require('node:path');
const { root, services, readConfig, serviceEnv, serviceCommand } = require('./arrancar-local.cjs');

const host = '127.0.0.1';
const port = 4199;
const origin = `http://${host}:${port}`;
const directory = join(root, 'frontend', 'angular-app', 'dist', 'angular-app', 'browser');
const cmd = process.env.ComSpec || 'cmd.exe';
const managed = new Map();
const busy = new Set();
const errors = new Map();

function respond(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(body));
}

function occupied(service) {
  return new Promise((resolve) => {
    const socket = connect({ host, port: service.port });
    const finish = (connected) => {
      socket.destroy();
      resolve(connected);
    };
    socket.setTimeout(700, () => finish(false));
    socket.once('error', () => finish(false));
    socket.once('connect', () => finish(true));
  });
}

async function status(service) {
  const record = managed.get(service.id);
  let state = 'off';
  if (busy.has(service.id) || record?.stopping) {
    state = record ? 'stopping' : 'starting';
  } else if (record) {
    try {
      const result = await fetch(`http://${host}:${service.port}${service.probe}`,
        { signal: AbortSignal.timeout(850), redirect: 'error' });
      state = result.ok ? 'on' : 'starting';
      await result.body?.cancel();
    } catch {
      state = 'starting';
    }
  } else if (await occupied(service)) {
    state = 'external';
  } else if (errors.has(service.id)) {
    state = 'error';
  }
  return { id: service.id, port: service.port, state, message: errors.get(service.id) };
}

async function start(service) {
  if (managed.has(service.id) || await occupied(service)) {
    throw new Error('Já existe um processo nesta porta. Fecha-o antes de arrancar pelo painel.');
  }
  const config = service.keys.length ? readConfig() : {};
  const child = spawn(cmd, ['/d', '/s', '/c', `"${serviceCommand(service)}"`], {
    windowsVerbatimArguments: true,
    windowsHide: true,
    cwd: join(root, service.cwd),
    env: serviceEnv(service, config),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const record = { child, stopping: false };
  managed.set(service.id, record);
  let output = '';
  const capture = (chunk) => { output = (output + chunk.toString()).slice(-16000); };
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);
  child.once('error', () => errors.set(service.id, 'Não foi possível abrir o processo.'));
  child.once('close', (code) => {
    if (managed.get(service.id) === record) {
      managed.delete(service.id);
      if (!record.stopping && !errors.has(service.id)) {
        const message = /JAVA_HOME|UnsupportedClassVersionError/i.test(output)
          ? 'O Maven não encontrou um JDK compatível. Confirma JAVA_HOME no ficheiro .env.local.'
          : `O processo terminou (${code ?? 'sem código'}). Usa arrancar.cmd para consultar a saída no CMD.`;
        errors.set(service.id, message);
      }
    }
  });
}

async function stop(service) {
  const record = managed.get(service.id);
  if (!record || record.child.exitCode !== null || !record.child.pid) {
    throw new Error('O painel só pode parar processos que ele próprio iniciou.');
  }
  record.stopping = true;
  const killer = spawn('taskkill.exe', ['/pid', String(record.child.pid), '/t', '/f'],
    { windowsHide: true, stdio: 'ignore' });
  try {
    await new Promise((resolve, reject) => {
      killer.once('error', reject);
      killer.once('exit', (code) => {
        if (code === 0 || record.child.exitCode !== null) resolve();
        else reject(new Error('Não foi possível parar o processo.'));
      });
    });
  } catch (error) {
    record.stopping = false;
    throw error;
  }
}

async function route(request, response) {
  if (request.headers.host !== `${host}:${port}`) {
    return respond(response, 403, { message: 'Endereço de origem inválido.' });
  }
  const path = new URL(request.url, origin).pathname;
  if (path === '/control-api/services' && request.method === 'GET') {
    return respond(response, 200, await Promise.all(services.map(status)));
  }
  const action = /^\/control-api\/services\/([a-z]+)\/(start|stop)$/.exec(path);
  if (request.method === 'POST' && action) {
    if (request.headers.origin !== origin || request.headers['x-interbank-control'] !== 'local') {
      return respond(response, 403, { message: 'Acção recusada fora do painel local.' });
    }
    const service = services.find((item) => item.id === action[1]);
    if (!service) return respond(response, 404, { message: 'Serviço desconhecido.' });
    if (busy.has(service.id)) return respond(response, 409, { message: 'Aguarda a operação anterior.' });
    busy.add(service.id);
    errors.delete(service.id);
    try {
      await (action[2] === 'start' ? start(service) : stop(service));
      return respond(response, 202, { ok: true });
    } catch (error) {
      return respond(response, 409, { message: error.message });
    } finally {
      busy.delete(service.id);
    }
  }

  const asset = /^\/([\w.-]+)\.(js|css)$/.exec(path);
  if (request.method === 'GET' && (path === '/' || path === '/controlo' || asset)) {
    const file = asset ? path.slice(1) : 'index.html';
    const type = asset ? (asset[2] === 'js' ? 'text/javascript' : 'text/css') : 'text/html';
    const contents = await readFile(join(directory, file));
    response.writeHead(200, {
      'Content-Type': `${type}; charset=utf-8`,
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
    });
    return response.end(contents);
  }
  return respond(response, 404, { message: 'Recurso inexistente.' });
}

function openBrowser() {
  if (!process.argv.includes('--abrir')) return;
  const browser = spawn(cmd, ['/d', '/c', 'start', '""', `${origin}/controlo`],
    { windowsHide: true, windowsVerbatimArguments: true, stdio: 'ignore' });
  browser.once('error', () => console.error(`Abre ${origin}/controlo no navegador.`));
}

const server = createServer((request, response) => {
  route(request, response).catch(() => respond(response, 500, { message: 'O painel local encontrou um erro.' }));
});
server.on('error', (error) => {
  if (error.code === 'EADDRINUSE') {
    fetch(`${origin}/control-api/services`, { signal: AbortSignal.timeout(1000) })
      .then((response) => response.json())
      .then((items) => {
        if (!Array.isArray(items) || !items.some((item) => item.id === 'rede')) throw error;
        openBrowser();
      })
      .catch(() => console.error(`A porta ${port} já está ocupada.`));
  } else {
    console.error(error.message);
  }
});
server.listen(port, host, () => {
  console.log(`Painel local em ${origin}/controlo`);
  openBrowser();
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, async () => {
    await Promise.allSettled(services.filter((service) => managed.has(service.id)).map(stop));
    server.close();
  });
}
