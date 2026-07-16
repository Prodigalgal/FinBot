import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');
const contractPath = path.join(repositoryRoot, 'contracts/finbot-control-plane.openapi.yaml');
const apiClientPath = path.join(repositoryRoot, 'apps/web/src/api.ts');
const generatedTypePath = path.join(repositoryRoot, 'apps/web/src/generated/control-plane.ts');
const controllerRoot = path.join(
  repositoryRoot,
  'services/backend/finbot-bootstrap/src/main/java/io/omnnu/finbot/api',
);
const mode = process.argv[2] ?? 'check';
const httpMethods = new Set(['get', 'post', 'put', 'delete', 'patch']);

const contract = YAML.parse(await readFile(contractPath, 'utf8'));
const controllerOperations = await extractControllerOperations(controllerRoot);

if (mode === 'sync') {
  synchronizeContract(contract, controllerOperations);
  await writeFile(contractPath, YAML.stringify(contract, { lineWidth: 0 }), 'utf8');
  await mkdir(path.dirname(generatedTypePath), { recursive: true });
  await writeFile(generatedTypePath, generateTypes(contract), 'utf8');
} else if (mode === 'check') {
  const expectedTypes = generateTypes(contract);
  const currentTypes = await readFile(generatedTypePath, 'utf8');
  if (currentTypes !== expectedTypes) {
    throw new Error('Generated control-plane TypeScript contract is stale; run npm run contract:sync');
  }
} else {
  throw new Error(`Unsupported contract command: ${mode}`);
}

validateControllerCoverage(contract, controllerOperations);
await validateWebCoverage(contract, apiClientPath);
console.log(
  `Control-plane contract verified: ${Object.keys(contract.paths).length} paths, `
    + `${controllerOperations.size} controller operations.`,
);

async function extractControllerOperations(directory) {
  const operations = new Map();
  for (const file of await walk(directory)) {
    if (!file.endsWith('.java') || file.includes(`${path.sep}internal${path.sep}`)) {
      continue;
    }
    const source = await readFile(file, 'utf8');
    const baseMatch = source.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/);
    if (!baseMatch || !baseMatch[1].startsWith('/api/v2')) {
      continue;
    }
    const annotationPattern = /@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)(?:\s*\(([\s\S]*?)\))?/g;
    for (const annotation of source.matchAll(annotationPattern)) {
      const method = annotation[1].replace('Mapping', '').toLowerCase();
      const argument = annotation[2] ?? '';
      const methodPath = argument.match(/"(\/[^"]*)"/)?.[1] ?? '';
      const fullPath = normalizeSlashes(baseMatch[1] + methodPath).replace(/^\/api\/v2/, '') || '/';
      const key = operationKey(method, fullPath);
      if (operations.has(key)) {
        throw new Error(`Duplicate controller operation ${key}: ${file}`);
      }
      operations.set(key, { method, path: fullPath, file });
    }
  }
  return operations;
}

async function validateWebCoverage(document, file) {
  const source = await readFile(file, 'utf8');
  const requestPathPattern = /([`'"])\/api\/v2([\s\S]*?)\1/g;
  const contractPaths = new Set(Object.keys(document.paths).map(normalizeParameterizedPath));
  const missing = new Set();
  for (const match of source.matchAll(requestPathPattern)) {
    const raw = `/api/v2${match[2]}`;
    const withoutQueryExpression = raw
      .replace(/\$\{query\([\s\S]*?\)\}/g, '')
      .split('?')[0]
      .replace(/\$\{[^}]+\}/g, '{parameter}')
      .replace(/^\/api\/v2/, '') || '/';
    const normalized = normalizeParameterizedPath(withoutQueryExpression);
    if (!contractPaths.has(normalized)) {
      missing.add(withoutQueryExpression);
    }
  }
  if (missing.size > 0) {
    throw new Error(`Web API paths missing from OpenAPI: ${[...missing].sort().join(', ')}`);
  }
}

function validateControllerCoverage(document, controllerOperations) {
  const contractOperations = new Set();
  for (const [route, pathItem] of Object.entries(document.paths)) {
    for (const method of Object.keys(pathItem)) {
      if (httpMethods.has(method)) {
        contractOperations.add(operationKey(method, route));
      }
    }
  }
  const missing = [...controllerOperations.keys()].filter((key) => !contractOperations.has(key));
  const ghost = [...contractOperations].filter((key) => !controllerOperations.has(key));
  if (missing.length > 0 || ghost.length > 0) {
    throw new Error(
      `OpenAPI/controller drift. Missing: ${missing.sort().join(', ') || 'none'}; `
        + `ghost: ${ghost.sort().join(', ') || 'none'}`,
    );
  }
}

function synchronizeContract(document, controllerOperations) {
  const synchronizedPaths = {};
  for (const operation of [...controllerOperations.values()].sort(compareOperations)) {
    const existingPath = document.paths[operation.path] ?? {};
    const pathItem = synchronizedPaths[operation.path] ?? {};
    pathItem[operation.method] = existingPath[operation.method]
      ?? genericOperation(operation.method, operation.path);
    synchronizedPaths[operation.path] = pathItem;
  }
  document.paths = synchronizedPaths;
}

function genericOperation(method, route) {
  const parameters = [...route.matchAll(/\{([^}]+)\}/g)].map((match) => ({
    name: match[1],
    in: 'path',
    required: true,
    schema: { type: 'string' },
  }));
  if (method !== 'get') {
    parameters.push({ $ref: '#/components/parameters/CsrfToken' });
  }
  const successStatus = method === 'delete' ? '204' : '200';
  const response = method === 'delete'
    ? { description: 'Operation completed without a response body.' }
    : { $ref: '#/components/responses/JsonObject' };
  const operation = {
    operationId: operationId(method, route),
    responses: {
      [successStatus]: response,
      default: { $ref: '#/components/responses/Problem' },
    },
  };
  if (parameters.length > 0) {
    operation.parameters = parameters;
  }
  if (route === '/auth/challenge' || route === '/auth/login') {
    operation.security = [];
  }
  return operation;
}

function generateTypes(document) {
  const routes = Object.entries(document.paths).sort(([left], [right]) => left.localeCompare(right));
  const pathUnion = routes
    .map(([route]) => `  | ${JSON.stringify(`/api/v2${route === '/' ? '' : route}`)}`)
    .join('\n');
  const requestPathUnion = routes
    .map(([route]) => {
      const template = `/api/v2${route === '/' ? '' : route}`.replace(/\{[^}]+\}/g, '${string}');
      return `  | \`${template}\``;
    })
    .join('\n');
  const operationRows = routes.flatMap(([route, pathItem]) =>
    Object.keys(pathItem)
      .filter((method) => httpMethods.has(method))
      .sort()
      .map((method) => `  | { method: '${method.toUpperCase()}'; path: ${JSON.stringify(route)} }`));
  const methodTypes = [...httpMethods].map((method) => {
    const typeName = method[0].toUpperCase() + method.slice(1);
    const matchingRoutes = routes
      .filter(([, pathItem]) => method in pathItem)
      .map(([route]) => {
        const template = `/api/v2${route === '/' ? '' : route}`.replace(/\{[^}]+\}/g, '${string}');
        return `  | \`${template}\``;
      });
    const methodRoutes = matchingRoutes.length > 0 ? matchingRoutes.join('\n') : '  never';
    return `export type ControlPlane${typeName}RequestPathBase =\n${methodRoutes};\n\n`
      + `export type ControlPlane${typeName}RequestPath = ControlPlane${typeName}RequestPathBase `
      + `| \`\${ControlPlane${typeName}RequestPathBase}?\${string}\`;`;
  }).join('\n\n');
  return `// Generated by scripts/control-plane-contract.mjs. Do not edit manually.\n`
    + `export type ControlPlanePath =\n${pathUnion};\n\n`
    + `export type ControlPlaneRequestPathBase =\n${requestPathUnion};\n\n`
    + 'export type ControlPlaneRequestPath = ControlPlaneRequestPathBase | `${ControlPlaneRequestPathBase}?${string}`;\n\n'
    + `${methodTypes}\n\n`
    + `export type ControlPlaneOperation =\n${operationRows.join('\n')};\n`;
}

function operationId(method, route) {
  const words = route
    .replace(/[{}]/g, '')
    .split(/[/-]/)
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1));
  return method + words.join('');
}

function operationKey(method, route) {
  return `${method.toUpperCase()} ${normalizeParameterizedPath(route)}`;
}

function normalizeParameterizedPath(route) {
  return normalizeSlashes(route).replace(/\{[^}]+\}/g, '{}');
}

function normalizeSlashes(value) {
  return ('/' + value).replace(/\/{2,}/g, '/').replace(/\/$/, '') || '/';
}

function compareOperations(left, right) {
  return left.path.localeCompare(right.path) || left.method.localeCompare(right.method);
}

async function walk(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const child = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await walk(child));
    } else {
      files.push(child);
    }
  }
  return files;
}
