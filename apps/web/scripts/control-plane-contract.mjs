import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import YAML from 'yaml';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');
const contractPath = path.join(repositoryRoot, 'contracts/finbot-control-plane.openapi.yaml');
const apiClientPath = path.join(repositoryRoot, 'apps/web/src/api.ts');
const webTypesPath = path.join(repositoryRoot, 'apps/web/src/types.ts');
const controllerRoot = path.join(
  repositoryRoot,
  'services/backend/finbot-bootstrap/src/main/java/io/omnnu/finbot/api',
);
const httpMethods = new Set(['get', 'post', 'put', 'delete', 'patch']);

const contract = YAML.parse(await readFile(contractPath, 'utf8'));
const controllerOperations = await extractControllerOperations(controllerRoot);

validateControllerCoverage(contract, controllerOperations);
validateOperationContracts(contract, controllerOperations);
await validateWebCoverage(contract, apiClientPath);
await validateWebModelDeclarations(contract, webTypesPath);
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
    const annotations = [...source.matchAll(annotationPattern)];
    for (const [index, annotation] of annotations.entries()) {
      const method = annotation[1].replace('Mapping', '').toLowerCase();
      const argument = annotation[2] ?? '';
      const methodPath = argument.match(/"(\/[^"]*)"/)?.[1] ?? '';
      const fullPath = normalizeSlashes(baseMatch[1] + methodPath).replace(/^\/api\/v2/, '') || '/';
      const key = operationKey(method, fullPath);
      if (operations.has(key)) {
        throw new Error(`Duplicate controller operation ${key}: ${file}`);
      }
      const operationSource = source.slice(
        annotation.index,
        annotations[index + 1]?.index ?? source.length,
      );
      const javaMethod = operationSource.match(/public\s+([\w.$<>,? ]+)\s+\w+\s*\(/);
      if (!javaMethod) {
        throw new Error(`Could not extract Controller method signature for ${key}: ${file}`);
      }
      operations.set(key, {
        method,
        path: fullPath,
        file,
        hasRequestBody: operationSource.includes('@RequestBody'),
        requestType: operationSource.match(/@RequestBody(?:\([^)]*\))?\s+([\w.$<>?]+)\s+\w+/)?.[1] ?? null,
        responseType: javaMethod[1].replace(/\s+/g, ' ').trim(),
      });
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
  const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const mismatches = [];
  const visit = (node) => {
    if (ts.isCallExpression(node)
        && ts.isIdentifier(node.expression)
        && node.expression.text === 'request'
        && node.typeArguments?.length === 1) {
      const route = webRequestRoute(node.arguments[0], sourceFile);
      if (route) {
        const method = webRequestMethod(node.arguments[1], sourceFile);
        const contractRoute = Object.keys(document.paths)
          .find((candidate) => normalizeParameterizedPath(candidate) === normalizeParameterizedPath(route));
        const operation = contractRoute ? document.paths[contractRoute][method] : null;
        const responseType = node.typeArguments[0].getText(sourceFile);
        if (!operation) {
          mismatches.push(`${method.toUpperCase()} ${route} has no OpenAPI operation`);
        } else if (operation['x-finbot-web-response-type'] !== responseType) {
          mismatches.push(
            `${method.toUpperCase()} ${contractRoute} expects ${operation['x-finbot-web-response-type'] ?? 'unset'} but Web uses ${responseType}`,
          );
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  if (mismatches.length > 0) {
    throw new Error(`OpenAPI/Web response type drift:\n${mismatches.sort().join('\n')}`);
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

function validateOperationContracts(document, controllerOperations) {
  const genericResponses = new Set(['#/components/responses/JsonObject', '#/components/responses/JsonArray']);
  const violations = [];
  for (const [route, pathItem] of Object.entries(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!httpMethods.has(method)) continue;
      const key = operationKey(method, route);
      const controller = controllerOperations.get(key);
      const success = Object.entries(operation.responses ?? {})
        .filter(([status]) => /^2\d\d$/.test(status));
      if (success.length !== 1) {
        violations.push(`${key} must declare exactly one success response`);
      } else {
        const [status, response] = success[0];
        if (genericResponses.has(response?.$ref)) {
          violations.push(`${key} uses a generic success response`);
        }
        if (status !== '204' && !response?.content) {
          violations.push(`${key} success response has no explicit content schema`);
        }
        validateWebResponseBinding(document, key, status, response, operation, violations);
      }
      if (operation.responses?.default?.$ref !== '#/components/responses/Problem') {
        violations.push(`${key} has no default Problem response`);
      }
      if (controller?.hasRequestBody !== Boolean(operation.requestBody)) {
        violations.push(`${key} request body does not match the Controller method`);
      }
      if (operation['x-finbot-java-response-type'] !== controller?.responseType) {
        violations.push(`${key} Java response type metadata is stale`);
      }
      if (controller?.hasRequestBody
          && operation['x-finbot-java-request-type'] !== controller.requestType) {
        violations.push(`${key} Java request type metadata is stale`);
      }
    }
  }
  if (document.components?.responses?.JsonObject || document.components?.responses?.JsonArray) {
    violations.push('Generic JsonObject/JsonArray response components are forbidden');
  }
  if (violations.length > 0) {
    throw new Error(`Incomplete control-plane operation contracts:\n${violations.sort().join('\n')}`);
  }
}

function validateWebResponseBinding(document, key, status, response, operation, violations) {
  const responseType = operation['x-finbot-web-response-type'];
  if (typeof responseType !== 'string') {
    return;
  }
  if (responseType === 'void') {
    if (status !== '204') violations.push(`${key} declares void but does not return 204`);
    return;
  }
  const resolvedResponse = resolveLocalReference(document, response);
  const schema = resolvedResponse?.content?.['application/json']?.schema;
  if (!schema) {
    violations.push(`${key} has no application/json response schema for ${responseType}`);
    return;
  }
  const arrayResponse = responseType.endsWith('[]');
  const modelName = arrayResponse ? responseType.slice(0, -2) : responseType;
  const reference = arrayResponse ? schema.items?.$ref : schema.$ref;
  if ((arrayResponse && schema.type !== 'array') || referenceName(reference) !== modelName) {
    violations.push(`${key} response schema does not match Web type ${responseType}`);
  }
}

async function validateWebModelDeclarations(document, file) {
  const source = await readFile(file, 'utf8');
  const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const interfaces = new Map();
  sourceFile.forEachChild((node) => {
    if (!ts.isInterfaceDeclaration(node)) return;
    const properties = new Map();
    for (const member of node.members) {
      if (!ts.isPropertySignature(member) || !member.name) continue;
      const name = propertyName(member.name);
      if (name) properties.set(name, Boolean(member.questionToken));
    }
    const parents = (node.heritageClauses ?? [])
      .filter((clause) => clause.token === ts.SyntaxKind.ExtendsKeyword)
      .flatMap((clause) => clause.types)
      .map((type) => type.expression.getText(sourceFile));
    interfaces.set(node.name.text, { properties, parents });
  });

  const responseModels = new Set();
  for (const pathItem of Object.values(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!httpMethods.has(method)) continue;
      const responseType = operation['x-finbot-web-response-type'];
      if (typeof responseType === 'string' && responseType !== 'void') {
        responseModels.add(responseType.replace(/\[\]$/, ''));
      }
    }
  }

  const violations = [];
  for (const modelName of [...responseModels].sort()) {
    const declaration = inheritedInterfaceProperties(modelName, interfaces);
    const schema = document.components?.schemas?.[modelName];
    if (!declaration) {
      violations.push(`${modelName} has no hand-written Web interface`);
      continue;
    }
    if (!schema || schema.type !== 'object') {
      violations.push(`${modelName} has no object schema in OpenAPI`);
      continue;
    }
    const schemaProperties = new Set(Object.keys(schema.properties ?? {}));
    const required = new Set(schema.required ?? []);
    for (const [property, optional] of declaration) {
      if (!schemaProperties.has(property)) {
        violations.push(`${modelName}.${property} is missing from OpenAPI`);
      } else if (optional === required.has(property)) {
        violations.push(`${modelName}.${property} required/optional state differs from OpenAPI`);
      }
    }
    for (const property of schemaProperties) {
      if (!declaration.has(property)) violations.push(`${modelName}.${property} is missing from Web types`);
    }
  }
  if (violations.length > 0) {
    throw new Error(`OpenAPI/hand-written Web model drift:\n${violations.join('\n')}`);
  }
}

function inheritedInterfaceProperties(modelName, interfaces, visiting = new Set()) {
  const declaration = interfaces.get(modelName);
  if (!declaration) return null;
  if (visiting.has(modelName)) throw new Error(`Circular Web interface inheritance: ${modelName}`);
  const nextVisiting = new Set(visiting).add(modelName);
  const properties = new Map();
  for (const parent of declaration.parents) {
    const inherited = inheritedInterfaceProperties(parent, interfaces, nextVisiting);
    if (!inherited) throw new Error(`${modelName} extends unknown Web interface ${parent}`);
    for (const [name, optional] of inherited) properties.set(name, optional);
  }
  for (const [name, optional] of declaration.properties) properties.set(name, optional);
  return properties;
}

function resolveLocalReference(document, value) {
  if (!value?.$ref?.startsWith('#/')) return value;
  return value.$ref.slice(2).split('/').reduce((current, segment) => current?.[segment], document);
}

function referenceName(reference) {
  return typeof reference === 'string' ? reference.split('/').at(-1) : null;
}

function propertyName(name) {
  if (ts.isIdentifier(name) || ts.isStringLiteral(name) || ts.isNumericLiteral(name)) return name.text;
  return null;
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

function webRequestMethod(initializer, sourceFile) {
  if (!initializer || !ts.isObjectLiteralExpression(initializer)) return 'get';
  const property = initializer.properties.find((candidate) =>
    ts.isPropertyAssignment(candidate) && candidate.name.getText(sourceFile) === 'method');
  if (!property || !ts.isPropertyAssignment(property)) return 'get';
  return property.initializer.getText(sourceFile).replace(/["']/g, '').toLowerCase();
}

function webRequestRoute(argument, sourceFile) {
  if (ts.isStringLiteral(argument) || ts.isNoSubstitutionTemplateLiteral(argument)) {
    return stripApiPrefix(argument.text);
  }
  if (!ts.isTemplateExpression(argument)) return null;
  let route = argument.head.text;
  for (const span of argument.templateSpans) {
    if (!span.expression.getText(sourceFile).startsWith('query(')) {
      route += '{parameter}';
    }
    route += span.literal.text;
  }
  return stripApiPrefix(route);
}

function stripApiPrefix(route) {
  return route.replace(/^\/api\/v2/, '').split('?')[0] || '/';
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
