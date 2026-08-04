import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import type { AiModel, AiProvider, ExecutionAiStage } from './types';

const apiMock = vi.hoisted(() => ({
  probeProviderDraft: vi.fn(),
  probeProvider: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { ExecutionStageEditor, ProviderCreatePanel, ProviderEditor } from './SettingsPage';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const declaredSources = {
  availability: 'DECLARED', supportedProtocols: 'DECLARED', maximumReasoningEffort: 'DECLARED',
  tokenLimitParameterStyles: 'DECLARED', streaming: 'DECLARED', tools: 'DECLARED', multimodal: 'DECLARED',
  maximumContextTokens: 'DECLARED', maximumInputTokens: 'DECLARED', maximumOutputTokens: 'DECLARED',
} as const;

it('shows model capabilities and imports only explicitly selected usable models with per-model defaults', async () => {
  apiMock.probeProviderDraft.mockResolvedValue({
    providerProfileId: 'draft',
    status: 'READY',
    models: ['grok-4.5', 'chat-only', 'experimental-unknown', 'retired-model'],
    modelCapabilities: [
      {
        modelName: 'grok-4.5', availability: 'AVAILABLE', supportedProtocols: ['RESPONSES'],
        maximumReasoningEffort: 'HIGH', tokenLimitParameterStyles: ['MAX_OUTPUT_TOKENS'],
        streaming: 'SUPPORTED', tools: 'SUPPORTED', multimodal: 'UNKNOWN', maximumContextTokens: 128000,
        maximumInputTokens: null, maximumOutputTokens: 8192, sources: declaredSources,
      },
      {
        modelName: 'chat-only', availability: 'AVAILABLE', supportedProtocols: ['CHAT'],
        maximumReasoningEffort: 'HIGH', tokenLimitParameterStyles: ['MAX_TOKENS'], streaming: 'SUPPORTED',
        tools: 'SUPPORTED', multimodal: 'UNKNOWN', maximumContextTokens: 128000, maximumInputTokens: null,
        maximumOutputTokens: 8192, sources: declaredSources,
      },
      {
        modelName: 'experimental-unknown', availability: 'UNKNOWN', supportedProtocols: [],
        maximumReasoningEffort: null, tokenLimitParameterStyles: [], streaming: 'UNKNOWN', tools: 'UNKNOWN',
        multimodal: 'UNKNOWN', maximumContextTokens: null, maximumInputTokens: null, maximumOutputTokens: null,
        sources: Object.fromEntries(Object.keys(declaredSources).map((key) => [key, 'UNKNOWN'])),
      },
      {
        modelName: 'retired-model', availability: 'UNAVAILABLE', supportedProtocols: ['RESPONSES'],
        maximumReasoningEffort: 'MAX', tokenLimitParameterStyles: ['MAX_OUTPUT_TOKENS'], streaming: 'SUPPORTED',
        tools: 'SUPPORTED', multimodal: 'UNSUPPORTED', maximumContextTokens: 128000, maximumInputTokens: null,
        maximumOutputTokens: 8192, sources: declaredSources,
      },
    ],
    warnings: [],
    httpStatus: 200,
    latencyMilliseconds: 42,
    errorCode: null,
    errorMessage: null,
    checkedAt: '2026-07-17T00:00:00Z',
  });
  const create = vi.fn().mockResolvedValue(true);
  const user = userEvent.setup();
  render(<ProviderCreatePanel create={create} />);

  expect(screen.queryByLabelText('首个模型')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('厂商名称'), { target: { value: '测试厂商' } });
  fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://provider.example/v1' } });
  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'test-api-key' } });
  expect(screen.getByLabelText('最大并发请求')).toHaveValue(5);
  fireEvent.change(screen.getByLabelText('最大并发请求'), { target: { value: '7' } });
  fireEvent.change(screen.getByLabelText('排队等待（秒）'), { target: { value: '3600' } });
  fireEvent.change(screen.getByLabelText('单次请求超时（秒）'), { target: { value: '3600' } });
  await user.click(screen.getByRole('button', { name: '读取模型目录' }));

  await waitFor(() => expect(apiMock.probeProviderDraft).toHaveBeenCalledWith({
    baseUrl: 'https://provider.example/v1',
    apiKey: 'test-api-key',
    requestTimeoutSeconds: 3600,
  }));
  expect(await screen.findByText(/目录读取成功，共 4 个模型/)).toBeInTheDocument();
  expect(screen.getByText('可用 2')).toBeInTheDocument();
  expect(screen.getByText('能力未知 1')).toBeInTheDocument();
  expect(screen.getByText('不可用 1')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: '选择已知可用' }));
  expect(screen.getByRole('button', { name: '创建并导入 1 个模型' })).toBeEnabled();

  await user.click(screen.getByLabelText('导入模型'));
  expect(await screen.findByRole('option', { name: /retired-model/ })).toHaveAttribute('aria-disabled', 'true');
  expect(screen.getByRole('option', { name: /chat-only/ })).toHaveAttribute('aria-disabled', 'true');
  await user.click(screen.getByRole('option', { name: /experimental-unknown/ }));
  await user.click(screen.getByRole('button', { name: '创建并导入 2 个模型' }));

  await waitFor(() => expect(create).toHaveBeenCalledWith(
    expect.objectContaining({
      displayName: '测试厂商',
      baseUrl: 'https://provider.example/v1',
      protocol: 'RESPONSES',
      maximumConcurrentRequests: 7,
      acquireTimeoutSeconds: 3600,
      requestTimeoutSeconds: 3600,
    }),
    'test-api-key',
    [
      {
        modelName: 'grok-4.5', defaultReasoningEffort: 'HIGH', maximumReasoningEffort: 'HIGH',
        tokenLimitParameterStyle: 'MAX_OUTPUT_TOKENS',
      },
      {
        modelName: 'experimental-unknown', defaultReasoningEffort: 'PROVIDER_DEFAULT',
        maximumReasoningEffort: 'PROVIDER_DEFAULT', tokenLimitParameterStyle: 'PROTOCOL_DEFAULT',
      },
    ],
  ));
});

it('ignores a catalog response when connection parameters change while the request is running', async () => {
  let resolveProbe: ((value: unknown) => void) | undefined;
  apiMock.probeProviderDraft.mockReturnValue(new Promise((resolve) => { resolveProbe = resolve; }));
  const user = userEvent.setup();
  render(<ProviderCreatePanel create={vi.fn()} />);

  fireEvent.change(screen.getByLabelText('厂商名称'), { target: { value: '测试厂商' } });
  fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://old.example/v1' } });
  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'test-api-key' } });
  await user.click(screen.getByRole('button', { name: '读取模型目录' }));
  fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://new.example/v1' } });

  await act(async () => resolveProbe?.({
    providerProfileId: 'draft', status: 'READY', models: ['stale-model'], modelCapabilities: [], warnings: [],
    httpStatus: 200, latencyMilliseconds: 20, errorCode: null, errorMessage: null, checkedAt: '2026-08-04T00:00:00Z',
  }));

  expect(screen.queryByText(/目录读取成功/)).not.toBeInTheDocument();
  expect(screen.queryByLabelText('导入模型')).not.toBeInTheDocument();
});

it('uses pending provider URL and key when reading a model catalog', async () => {
  apiMock.probeProviderDraft.mockResolvedValue({
    providerProfileId: 'draft', status: 'READY', models: ['model-a'], modelCapabilities: [], warnings: [],
    httpStatus: 200, latencyMilliseconds: 20, errorCode: null, errorMessage: null,
    checkedAt: '2026-08-04T00:00:00Z',
  });
  const provider = {
    profileId: 'provider_saved', displayName: 'Saved provider', protocol: 'CHAT', reasoningParameterStyle: 'FLAT',
    baseUrl: 'https://old.example/v1', baseUrlConfigured: true, apiKeyConfigured: false,
    credentialSource: 'UNCONFIGURED', credentialFingerprint: null, credentialVersion: 1,
    credentialUpdatedAt: '2026-08-04T00:00:00Z', enabled: true, connectTimeoutSeconds: 10,
    requestTimeoutSeconds: 1800, maximumConcurrentRequests: 5, acquireTimeoutSeconds: 1800,
    workflowNodeUsageCount: 0, roleTemplateUsageCount: 0, executionStageUsageCount: 0,
    totalUsageCount: 0, version: 1, updatedAt: '2026-08-04T00:00:00Z',
  } as const;
  const user = userEvent.setup();
  render(<ProviderEditor provider={provider} models={[]} save={vi.fn()} remove={vi.fn()} putCredential={vi.fn()} clearCredential={vi.fn()} importModels={vi.fn()} />);

  fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://new.example/v1' } });
  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'pending-api-key' } });
  await user.click(screen.getByRole('button', { name: '读取模型目录' }));

  await waitFor(() => expect(apiMock.probeProviderDraft).toHaveBeenCalledWith({
    baseUrl: 'https://new.example/v1', apiKey: 'pending-api-key', requestTimeoutSeconds: 1800,
  }));
  expect(apiMock.probeProvider).not.toHaveBeenCalled();
});

it('clears an existing provider catalog before pending credentials can reuse stale models', async () => {
  apiMock.probeProviderDraft.mockResolvedValue({
    providerProfileId: 'draft', status: 'READY', models: ['model-a'], modelCapabilities: [], warnings: [],
    httpStatus: 200, latencyMilliseconds: 20, errorCode: null, errorMessage: null,
    checkedAt: '2026-08-04T00:00:00Z',
  });
  const provider = {
    profileId: 'provider_saved', displayName: 'Saved provider', protocol: 'CHAT', reasoningParameterStyle: 'FLAT',
    baseUrl: 'https://provider.example/v1', baseUrlConfigured: true, apiKeyConfigured: false,
    credentialSource: 'UNCONFIGURED', credentialFingerprint: null, credentialVersion: 1,
    credentialUpdatedAt: '2026-08-04T00:00:00Z', enabled: true, connectTimeoutSeconds: 10,
    requestTimeoutSeconds: 1800, maximumConcurrentRequests: 5, acquireTimeoutSeconds: 1800,
    workflowNodeUsageCount: 0, roleTemplateUsageCount: 0, executionStageUsageCount: 0,
    totalUsageCount: 0, version: 1, updatedAt: '2026-08-04T00:00:00Z',
  } as const;
  const user = userEvent.setup();
  render(<ProviderEditor provider={provider} models={[]} save={vi.fn()} remove={vi.fn()} putCredential={vi.fn()} clearCredential={vi.fn()} importModels={vi.fn()} />);

  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'first-api-key' } });
  await user.click(screen.getByRole('button', { name: '读取模型目录' }));
  expect(await screen.findByText(/探测到 1 个模型/)).toBeInTheDocument();

  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'second-api-key' } });
  expect(screen.queryByText(/探测到 1 个模型/)).not.toBeInTheDocument();
  expect(screen.queryByLabelText('导入新探测模型')).not.toBeInTheDocument();
});

const providerProfile = (profileId: string, enabled: boolean): AiProvider => ({
  profileId, displayName: profileId, protocol: 'RESPONSES', reasoningParameterStyle: 'NESTED',
  baseUrl: 'https://provider.example/v1', baseUrlConfigured: true, apiKeyConfigured: true,
  credentialSource: 'DATABASE_OVERRIDE', credentialFingerprint: '12345678', credentialVersion: 1,
  credentialUpdatedAt: '2026-08-04T00:00:00Z', enabled, connectTimeoutSeconds: 10,
  requestTimeoutSeconds: 1800, maximumConcurrentRequests: 5, acquireTimeoutSeconds: 1800,
  workflowNodeUsageCount: 0, roleTemplateUsageCount: 0, executionStageUsageCount: 0,
  totalUsageCount: 0, version: 1, updatedAt: '2026-08-04T00:00:00Z',
});

const modelProfile = (modelProfileId: string, providerProfileId: string, enabled: boolean, maximumReasoningEffort: AiModel['maximumReasoningEffort'] = 'MAX'): AiModel => ({
  modelProfileId, providerProfileId, modelName: `${modelProfileId}-name`, defaultReasoningEffort: 'HIGH',
  maximumReasoningEffort, tokenLimitParameterStyle: 'PROTOCOL_DEFAULT', inputUsdPerMillion: 0,
  outputUsdPerMillion: 0, enabled, version: 1, updatedAt: '2026-08-04T00:00:00Z',
});

it('switches an execution stage to an enabled model owned by the selected provider', async () => {
  const providers = [providerProfile('provider_a', true), providerProfile('provider_b', true), providerProfile('provider_disabled', false)];
  const models = [modelProfile('model_a', 'provider_a', true), modelProfile('model_b', 'provider_b', true, 'HIGH'), modelProfile('model_disabled', 'provider_disabled', true)];
  const stage: ExecutionAiStage = {
    stage: 'DRAFT', primaryAiBinding: { providerProfileId: 'provider_a', modelName: 'model_a-name', reasoningEffort: 'HIGH' },
    fallbackAiBinding: null, systemPrompt: 'system', userPromptTemplate: 'user', maximumOutputTokens: 4096,
    timeoutSeconds: 300, retryPolicy: { maximumAttempts: 2, backoff: 5 }, enabled: true, version: 1,
  };
  const save = vi.fn();
  const user = userEvent.setup();
  render(<ExecutionStageEditor stage={stage} providers={providers} models={models} save={save} />);

  await user.click(screen.getByLabelText('厂商'));
  expect(screen.queryByRole('option', { name: 'provider_disabled' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('option', { name: 'provider_b' }));
  expect(screen.getByLabelText('模型')).toHaveTextContent('model_b-name');
  await user.click(screen.getByLabelText('思考强度'));
  expect(screen.queryByRole('option', { name: 'XHIGH' })).not.toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'MAX' })).not.toBeInTheDocument();
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('button', { name: '保存阶段' }));

  expect(save).toHaveBeenCalledWith(expect.objectContaining({
    primaryAiBinding: { providerProfileId: 'provider_b', modelName: 'model_b-name', reasoningEffort: 'HIGH' },
  }));
});
