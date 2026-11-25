const { spawn, spawnSync } = require('node:child_process');
const { constants, copyFileSync, existsSync, readFileSync } = require('node:fs');
const { join } = require('node:path');
const { parseEnv } = require('node:util');

const root = join(__dirname, '..');
const configPath = join(root, '.env.local');
const internal = ['INTERBANK_SHARED_PASSWORD', 'HORIZONTE_INTERBANK_PASSWORD'];
const jwt = ['KWANZA_JWT_SECRET', 'HORIZONTE_JWT_SECRET'];
const required = [...internal, ...jwt, 'KWANZA_DB_PASSWORD', 'HORIZONTE_DB_PASSWORD'];
const services = [
  {
    id: 'rede',
    port: 8083,
    probe: '/api/network/health',
    title: 'InterBank - Rede interbancaria',
    cwd: 'rede-interbancaria',
    keys: [...internal, 'JAVA_HOME', 'KWANZA_BASE_URL', 'HORIZONTE_BASE_URL'],
  },
  {
    id: 'kwanza',
    port: 8081,
    probe: '/v3/api-docs',
    title: 'InterBank - Banco Kwanza',
    cwd: 'banco-kwanza',
    keys: [...internal, jwt[0], 'KWANZA_DB_PASSWORD', 'JAVA_HOME', 'INTERBANK_DB_PORT', 'INTERBANK_ROUTER_URL'],
  },
  {
    id: 'horizonte',
    port: 8082,
    probe: '/v3/api-docs',
    title: 'InterBank - Banco Horizonte',
    cwd: 'banco-horizonte',
    keys: [...internal, jwt[1], 'HORIZONTE_DB_PASSWORD', 'JAVA_HOME', 'INTERBANK_DB_PORT', 'INTERBANK_ROUTER_URL'],
  },
  {
    id: 'angular',
    port: 4200,
    probe: '/',
    title: 'InterBank - Angular',
    cwd: 'frontend/angular-app',
    keys: [],
    command: 'if exist node_modules\\.bin\\ng.cmd (call npm.cmd start) else (call npm.cmd ci && call npm.cmd start)',
  },
];

function readConfig() {
  if (!existsSync(configPath)) {
    copyFileSync(join(root, '.env.local.example'), configPath, constants.COPYFILE_EXCL);
    throw new Error('Criei .env.local. Preenche as seis variáveis e tenta novamente.');
  }
  const values = parseEnv(readFileSync(configPath, 'utf8'));
  const missing = required.filter((key) => !values[key]?.trim());
  if (missing.length) {
    throw new Error(`Preenche em .env.local: ${missing.join(', ')}.`);
  }
  for (const key of internal) {
    if (!/^[!-~]{32,64}$/.test(values[key])) {
      throw new Error(`${key} deve ter 32 a 64 caracteres ASCII sem espaços.`);
    }
  }
  for (const key of jwt) {
    if (Buffer.byteLength(values[key], 'utf8') < 32) {
      throw new Error(`${key} deve ter pelo menos 32 bytes.`);
    }
  }
  if (values[internal[0]] === values[internal[1]] || values[jwt[0]] === values[jwt[1]]) {
    throw new Error('Usa segredos JWT distintos e uma credencial interna diferente para cada sentido.');
  }
  return values;
}

function serviceEnv(service, config) {
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (required.includes(key.toUpperCase())) delete env[key];
  }
  for (const key of service.keys) {
    if (config[key]) env[key] = config[key];
  }
  if (service.keys.includes('JAVA_HOME')) {
    env.JAVA_HOME = javaHome(config.JAVA_HOME, env);
  }
  return env;
}

function isSupportedJdk(directory) {
  if (!directory || !existsSync(join(directory, 'bin', 'javac.exe'))
      || !existsSync(join(directory, 'bin', 'java.exe'))) return false;
  try {
    const version = /^JAVA_VERSION="(\d+)/m.exec(readFileSync(join(directory, 'release'), 'utf8'));
    return version !== null && Number(version[1]) >= 21;
  } catch {
    return false;
  }
}

function javaHome(configured, env) {
  if (configured) {
    if (isSupportedJdk(configured)) return configured;
    throw new Error('JAVA_HOME em .env.local deve apontar para a pasta de um JDK 21 ou superior.');
  }
  if (isSupportedJdk(env.JAVA_HOME)) return env.JAVA_HOME;
  const result = spawnSync('java.exe', ['-XshowSettings:properties', '-version'],
    { env, encoding: 'utf8', windowsHide: true, timeout: 5000 });
  const detected = /^\s*java\.home\s*=\s*(.+)$/m.exec(result.stderr ?? '')?.[1]?.trim();
  if (isSupportedJdk(detected)) return detected;
  throw new Error('JDK 21 ou superior em falta no PATH. Define JAVA_HOME em .env.local com a pasta do JDK.');
}

function serviceCommand(service) {
  return service.command ?? 'call mvnw.cmd spring-boot:run';
}

function startTerminal(service, config) {
  const command = serviceCommand(service);
  // START opens a separate interactive console. All shell arguments are fixed by the project.
  const child = spawn(process.env.ComSpec || 'cmd.exe',
    ['/d', '/c', 'start', `"${service.title}"`, 'cmd.exe', '/d', '/k', `"${command}"`],
    { cwd: join(root, service.cwd), env: serviceEnv(service, config), stdio: 'inherit', windowsVerbatimArguments: true });
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`Não foi possível abrir o terminal: ${service.title}.`));
    });
  });
}

async function main() {
  const config = readConfig();
  if (process.argv.includes('--check')) {
    console.log('Configuração válida. Os serviços ainda não foram iniciados.');
    return;
  }
  for (const service of services) await startTerminal(service, config);
  console.log('Quatro terminais abertos. Quando os serviços arrancarem, abre http://127.0.0.1:4200/.');
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

module.exports = { root, services, readConfig, serviceEnv, serviceCommand };
