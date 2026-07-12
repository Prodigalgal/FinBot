import {
  Box,
  Button,
  Card,
  CardContent,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import GroupsIcon from '@mui/icons-material/Groups';
import type { Dispatch, SetStateAction } from 'react';
import { useMemo, useState } from 'react';

import type { AIConfigPayload, CouncilChairConfig, CouncilRoleConfig, CouncilRolePreset, CouncilTemplateConfig } from './types';

type AIConfigForm = Pick<AIConfigPayload, 'sites' | 'task_bindings' | 'prompts' | 'council_templates' | 'experiments'>;

const inputSx = {
  '& .MuiOutlinedInput-root': { minHeight: 40, borderRadius: 1, bgcolor: 'background.paper' },
  '& .MuiInputBase-input': { fontSize: 13, lineHeight: 1.45 },
};

export function CouncilConfigPanel({
  aiForm,
  setAiForm,
  rolePresets,
}: {
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
  rolePresets: CouncilRolePreset[];
}) {
  const [selectedId, setSelectedId] = useState(aiForm.council_templates[0]?.template_id || '');
  const [selectedPresetId, setSelectedPresetId] = useState(rolePresets[0]?.preset_id || '');
  const selectedIndex = Math.max(0, aiForm.council_templates.findIndex((template) => template.template_id === selectedId));
  const template = aiForm.council_templates[selectedIndex];
  const siteIds = aiForm.sites.map((site) => site.site_id);

  const updateTemplate = (patch: Partial<CouncilTemplateConfig>) => {
    setAiForm((current) => {
      if (!current) return current;
      const councilTemplates = current.council_templates.map((item, index) => (
        index === selectedIndex ? { ...item, ...patch } : item
      ));
      return { ...current, council_templates: councilTemplates };
    });
  };

  const addTemplate = () => {
    const templateId = nextIdentifier(
      'council',
      aiForm.council_templates.map((item) => item.template_id),
    );
    const next = defaultTemplate(templateId, siteIds[0]);
    setAiForm((current) => current ? { ...current, council_templates: [...current.council_templates, next] } : current);
    setSelectedId(templateId);
  };

  const removeTemplate = () => {
    if (!template || aiForm.council_templates.length <= 1) return;
    const remaining = aiForm.council_templates.filter((item) => item.template_id !== template.template_id);
    setAiForm((current) => current ? { ...current, council_templates: remaining } : current);
    setSelectedId(remaining[0]?.template_id || '');
  };

  const addPresetRole = () => {
    const preset = rolePresets.find((item) => item.preset_id === selectedPresetId);
    if (!preset || template.roles.length >= template.max_roles) return;
    const presetRole: CouncilRoleConfig = {
      role_id: preset.role_id,
      display_name: preset.display_name,
      stance: preset.stance,
      objective: preset.objective,
      enabled: preset.enabled,
      order: preset.order,
      site_id: preset.site_id,
      protocol: preset.protocol,
      model: preset.model,
      fallback_site_ids: [...preset.fallback_site_ids],
      system_prompt: preset.system_prompt,
      user_prompt_template: preset.user_prompt_template,
    };
    const nextRole = {
      ...presetRole,
      role_id: uniqueIdentifier(preset.role_id, template.roles.map((role) => role.role_id)),
      order: (template.roles.length + 1) * 10,
      fallback_site_ids: [...preset.fallback_site_ids],
    };
    updateTemplate(addRoleToWorkflow(template, nextRole));
  };

  if (!template) {
    return (
      <Card><CardContent><Typography variant="body2" color="text.secondary">暂无 Council 模板</Typography></CardContent></Card>
    );
  }

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'stretch', sm: 'center' }} sx={{ mb: 1.5 }}>
            <GroupsIcon color="primary" />
            <Typography variant="h3" sx={{ flexGrow: 1 }}>AI 调度小组</Typography>
            <TextField select value={template.template_id} onChange={(event) => setSelectedId(event.target.value)} sx={{ minWidth: 190, ...inputSx }}>
              {aiForm.council_templates.map((item) => <MenuItem key={item.template_id} value={item.template_id}>{item.display_name}</MenuItem>)}
            </TextField>
            <Tooltip title="新增模板"><IconButton onClick={addTemplate}><AddIcon /></IconButton></Tooltip>
            <Tooltip title="删除模板"><span><IconButton onClick={removeTemplate} disabled={aiForm.council_templates.length <= 1}><DeleteIcon /></IconButton></span></Tooltip>
          </Stack>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
            <TextField label="模板 ID" value={template.template_id} disabled sx={inputSx} />
            <TextField label="显示名称" value={template.display_name} onChange={(event) => updateTemplate({ display_name: event.target.value })} sx={inputSx} />
            <TextField label="Quorum" type="number" value={template.quorum_ratio} inputProps={{ min: 0.25, max: 1, step: 0.05 }} onChange={(event) => updateTemplate({ quorum_ratio: Number(event.target.value) })} sx={inputSx} />
            <FormControlLabel control={<Switch checked={template.enabled} onChange={(event) => updateTemplate({ enabled: event.target.checked })} />} label="启用模板" />
          </Box>
        </CardContent>
      </Card>

      <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
        <Box>
          <Typography variant="h3">分析角色</Typography>
          <Typography variant="caption" color="text.secondary">启用角色 2-12 个；每个角色独立选择站点、协议、模型与提示词。</Typography>
        </Box>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'stretch', sm: 'center' }}>
          <TextField
            select
            label="角色预设"
            value={selectedPresetId}
            onChange={(event) => setSelectedPresetId(event.target.value)}
            sx={{ minWidth: { xs: '100%', sm: 210 }, ...inputSx }}
            disabled={rolePresets.length === 0}
          >
            {rolePresets.map((preset) => (
              <MenuItem key={preset.preset_id} value={preset.preset_id}>
                {preset.display_name} · {preset.site_id || '未绑定'}
              </MenuItem>
            ))}
          </TextField>
          <Button
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={addPresetRole}
            disabled={!selectedPresetId || template.roles.length >= template.max_roles}
          >
            添加预设
          </Button>
          <Tooltip title="新增空白角色">
            <span>
              <IconButton
                onClick={() => updateTemplate(addRoleToWorkflow(
                  template,
                  defaultRole(
                    nextIdentifier('analyst', template.roles.map((role) => role.role_id)),
                    template.roles.length,
                    siteIds[0],
                  ),
                ))}
                disabled={template.roles.length >= template.max_roles}
              >
                <AddIcon />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      </Stack>
      {template.roles.map((role, roleIndex) => (
        <CouncilRoleEditor
          key={`${role.role_id}-${roleIndex}`}
          role={role}
          roleIndex={roleIndex}
          siteIds={siteIds}
          aiForm={aiForm}
          disableDelete={template.roles.length <= 2}
          onChange={(patch) => updateTemplate(updateRoleAndWorkflow(template, roleIndex, patch))}
          onDelete={() => updateTemplate(removeRoleFromWorkflow(template, role.role_id))}
        />
      ))}

      <Card>
        <CardContent>
          <Typography variant="h3" sx={{ mb: 1.5 }}>轮次调度</Typography>
          <Stack spacing={1.25}>
            {template.phases.map((phase, phaseIndex) => (
              <Box key={phase.phase_id} sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '150px 150px 170px minmax(0, 1fr)' }, gap: 1, alignItems: 'start' }}>
                <TextField label="阶段 ID" value={phase.phase_id} disabled sx={inputSx} />
                <TextField label="阶段名称" value={phase.label} onChange={(event) => updateTemplate({ phases: template.phases.map((item, index) => index === phaseIndex ? { ...item, label: event.target.value } : item) })} sx={inputSx} />
                <TextField select label="调度方式" value={phase.scheduling_mode} onChange={(event) => updateTemplate({ phases: template.phases.map((item, index) => index === phaseIndex ? { ...item, scheduling_mode: event.target.value as typeof phase.scheduling_mode } : item) })} sx={inputSx}>
                  <MenuItem value="parallel">并行</MenuItem>
                  <MenuItem value="round_robin">轮流</MenuItem>
                  <MenuItem value="moderated" disabled>主持调度（预留）</MenuItem>
                </TextField>
                <TextField label="阶段指令" multiline minRows={2} value={phase.instructions} onChange={(event) => updateTemplate({ phases: template.phases.map((item, index) => index === phaseIndex ? { ...item, instructions: event.target.value } : item) })} sx={inputSx} />
              </Box>
            ))}
          </Stack>
        </CardContent>
      </Card>

      <CouncilChairEditor
        chair={template.chair}
        aiForm={aiForm}
        siteIds={siteIds}
        onChange={(patch) => updateTemplate(updateChairAndWorkflow(template, patch))}
      />
    </Stack>
  );
}

function CouncilRoleEditor({
  role,
  roleIndex,
  siteIds,
  aiForm,
  disableDelete,
  onChange,
  onDelete,
}: {
  role: CouncilRoleConfig;
  roleIndex: number;
  siteIds: string[];
  aiForm: AIConfigForm;
  disableDelete: boolean;
  onChange: (patch: Partial<CouncilRoleConfig>) => void;
  onDelete: () => void;
}) {
  const models = useModels(aiForm, role.site_id, role.protocol);
  return (
    <Card>
      <CardContent>
        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.25 }}>
          <Typography variant="h3" sx={{ flexGrow: 1 }}>{role.display_name || `角色 ${roleIndex + 1}`}</Typography>
          <FormControlLabel control={<Switch checked={role.enabled} onChange={(event) => onChange({ enabled: event.target.checked })} />} label="启用" />
          <Tooltip title="删除角色"><span><IconButton onClick={onDelete} disabled={disableDelete}><DeleteIcon /></IconButton></span></Tooltip>
        </Stack>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
          <TextField label="角色 ID" value={role.role_id} onChange={(event) => onChange({ role_id: event.target.value })} sx={inputSx} />
          <TextField label="显示名称" value={role.display_name} onChange={(event) => onChange({ display_name: event.target.value })} sx={inputSx} />
          <TextField label="立场" value={role.stance} onChange={(event) => onChange({ stance: event.target.value })} sx={inputSx} />
          <TextField label="顺序" type="number" value={role.order} onChange={(event) => onChange({ order: Number(event.target.value) })} sx={inputSx} />
          <TextField select label="AI 站点" value={role.site_id || ''} onChange={(event) => onChange({ site_id: event.target.value, model: '' })} sx={inputSx}>
            {siteIds.map((siteId) => <MenuItem key={siteId} value={siteId}>{siteId}</MenuItem>)}
          </TextField>
          <TextField select label="协议" value={role.protocol || 'chat'} onChange={(event) => onChange({ protocol: event.target.value, model: '' })} sx={inputSx}>
            <MenuItem value="chat">chat</MenuItem><MenuItem value="responses">responses</MenuItem>
          </TextField>
          <TextField label="模型" value={role.model || ''} onChange={(event) => onChange({ model: event.target.value })} select={models.length > 0} sx={inputSx}>
            {models.map((model) => <MenuItem key={model} value={model}>{model}</MenuItem>)}
          </TextField>
          <ReasoningEffortField value={role.reasoning_effort} onChange={(reasoning_effort) => onChange({ reasoning_effort })} />
          <TextField label="备用站点" value={(role.fallback_site_ids || []).join(',')} onChange={(event) => onChange({ fallback_site_ids: splitCsv(event.target.value) })} sx={inputSx} />
          <TextField label="职责目标" value={role.objective} onChange={(event) => onChange({ objective: event.target.value })} multiline minRows={3} sx={{ gridColumn: { md: 'span 2' }, ...inputSx }} />
          <TextField label="系统提示词" value={role.system_prompt || ''} onChange={(event) => onChange({ system_prompt: event.target.value })} multiline minRows={3} sx={{ gridColumn: { md: 'span 2' }, ...inputSx }} />
          <TextField label="用户提示词模板" value={role.user_prompt_template || ''} onChange={(event) => onChange({ user_prompt_template: event.target.value })} multiline minRows={2} sx={{ gridColumn: '1 / -1', ...inputSx }} />
        </Box>
      </CardContent>
    </Card>
  );
}

function CouncilChairEditor({
  chair,
  aiForm,
  siteIds,
  onChange,
}: {
  chair: CouncilChairConfig;
  aiForm: AIConfigForm;
  siteIds: string[];
  onChange: (patch: Partial<CouncilChairConfig>) => void;
}) {
  const models = useModels(aiForm, chair.site_id, chair.protocol);
  return (
    <Card>
      <CardContent>
        <Typography variant="h3" sx={{ mb: 1.5 }}>Chair 仲裁</Typography>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
          <TextField label="角色 ID" value={chair.role_id} onChange={(event) => onChange({ role_id: event.target.value })} sx={inputSx} />
          <TextField label="显示名称" value={chair.display_name} onChange={(event) => onChange({ display_name: event.target.value })} sx={inputSx} />
          <TextField select label="AI 站点" value={chair.site_id || ''} onChange={(event) => onChange({ site_id: event.target.value, model: '' })} sx={inputSx}>
            {siteIds.map((siteId) => <MenuItem key={siteId} value={siteId}>{siteId}</MenuItem>)}
          </TextField>
          <TextField select label="协议" value={chair.protocol || 'chat'} onChange={(event) => onChange({ protocol: event.target.value, model: '' })} sx={inputSx}>
            <MenuItem value="chat">chat</MenuItem><MenuItem value="responses">responses</MenuItem>
          </TextField>
          <TextField label="模型" value={chair.model || ''} onChange={(event) => onChange({ model: event.target.value })} select={models.length > 0} sx={inputSx}>
            {models.map((model) => <MenuItem key={model} value={model}>{model}</MenuItem>)}
          </TextField>
          <ReasoningEffortField value={chair.reasoning_effort} onChange={(reasoning_effort) => onChange({ reasoning_effort })} />
          <TextField label="备用站点" value={(chair.fallback_site_ids || []).join(',')} onChange={(event) => onChange({ fallback_site_ids: splitCsv(event.target.value) })} sx={inputSx} />
          <TextField label="系统提示词" value={chair.system_prompt || ''} onChange={(event) => onChange({ system_prompt: event.target.value })} multiline minRows={6} sx={{ gridColumn: '1 / -1', ...inputSx }} />
          <TextField label="用户提示词模板" value={chair.user_prompt_template || ''} onChange={(event) => onChange({ user_prompt_template: event.target.value })} multiline minRows={2} sx={{ gridColumn: '1 / -1', ...inputSx }} />
        </Box>
      </CardContent>
    </Card>
  );
}

function useModels(aiForm: AIConfigForm, siteId?: string | null, protocol?: string | null): string[] {
  return useMemo(() => {
    const site = aiForm.sites.find((item) => item.site_id === siteId);
    return protocol === 'responses' ? site?.responses_models || [] : site?.chat_models || [];
  }, [aiForm.sites, protocol, siteId]);
}

function defaultRole(roleId: string, index: number, siteId?: string): CouncilRoleConfig {
  return {
    role_id: roleId,
    display_name: `分析角色 ${index + 1}`,
    stance: 'neutral',
    objective: '独立审查输入、回应其他角色并保留证据引用。',
    enabled: true,
    order: (index + 1) * 10,
    site_id: siteId || null,
    protocol: 'chat',
    model: siteId === 'mimo' ? 'mimo-v2.5-pro' : null,
    reasoning_effort: 'high',
    fallback_site_ids: [],
    system_prompt: '不得虚构事实；必须列出证据、反证和失效条件。',
    user_prompt_template: '{payload_json}',
  };
}

function defaultTemplate(templateId: string, siteId?: string): CouncilTemplateConfig {
  return {
    template_id: templateId,
    display_name: '新建 AI 调度小组',
    enabled: true,
    roles: [defaultRole('analyst_1', 0, siteId), defaultRole('analyst_2', 1, siteId)],
    phases: [
      { phase_id: 'independent_analysis', label: '独立分析', message_type: 'analysis', scheduling_mode: 'parallel', instructions: '独立分析并保留 evidence refs。' },
      { phase_id: 'cross_examination', label: '交叉质询', message_type: 'challenge', scheduling_mode: 'round_robin', instructions: '阅读前序消息并质询具体 claim。' },
      { phase_id: 'position_revision', label: '立场修订', message_type: 'rebuttal', scheduling_mode: 'parallel', instructions: '回应质询并明确是否修订立场。' },
    ],
    chair: {
      role_id: 'chair_arbiter',
      display_name: '主席仲裁员',
      site_id: siteId || null,
      protocol: 'chat',
      model: siteId === 'mimo' ? 'mimo-v2.5-pro' : null,
      reasoning_effort: 'high',
      fallback_site_ids: [],
      system_prompt: '综合分歧并输出可审计结论，严格执行 advisory-only 策略。',
      user_prompt_template: '{payload_json}',
    },
    workflow: {
      version: 1,
      nodes: [
        { node_id: 'input_context', node_type: 'input', role_id: null, position: { x: 40, y: 170 } },
        { node_id: 'node_analyst_1', node_type: 'agent', role_id: 'analyst_1', position: { x: 320, y: 80 } },
        { node_id: 'node_analyst_2', node_type: 'agent', role_id: 'analyst_2', position: { x: 320, y: 260 } },
        { node_id: 'node_chair_arbiter', node_type: 'chair', role_id: 'chair_arbiter', position: { x: 680, y: 170 } },
      ],
      edges: [
        { edge_id: 'edge_1', source_node_id: 'input_context', target_node_id: 'node_analyst_1' },
        { edge_id: 'edge_2', source_node_id: 'node_analyst_1', target_node_id: 'node_chair_arbiter' },
        { edge_id: 'edge_3', source_node_id: 'input_context', target_node_id: 'node_analyst_2' },
        { edge_id: 'edge_4', source_node_id: 'node_analyst_2', target_node_id: 'node_chair_arbiter' },
      ],
    },
    quorum_ratio: 0.5,
    max_roles: 12,
  };
}

function ReasoningEffortField({
  value,
  onChange,
}: {
  value?: CouncilRoleConfig['reasoning_effort'];
  onChange: (value: NonNullable<CouncilRoleConfig['reasoning_effort']>) => void;
}) {
  return (
    <TextField select label="思考等级" value={value || 'provider_default'} onChange={(event) => onChange(event.target.value as NonNullable<CouncilRoleConfig['reasoning_effort']>)} sx={inputSx}>
      <MenuItem value="provider_default">厂商默认</MenuItem>
      <MenuItem value="none">关闭</MenuItem>
      <MenuItem value="minimal">极低</MenuItem>
      <MenuItem value="low">低</MenuItem>
      <MenuItem value="medium">中</MenuItem>
      <MenuItem value="high">高</MenuItem>
      <MenuItem value="xhigh">极高</MenuItem>
    </TextField>
  );
}

function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

function nextIdentifier(prefix: string, existingIds: string[]): string {
  const existing = new Set(existingIds);
  let suffix = 1;
  while (existing.has(`${prefix}_${suffix}`)) {
    suffix += 1;
  }
  return `${prefix}_${suffix}`;
}

function uniqueIdentifier(preferred: string, existingIds: string[]): string {
  if (!existingIds.includes(preferred)) return preferred;
  return nextIdentifier(preferred, existingIds);
}

function addRoleToWorkflow(template: CouncilTemplateConfig, role: CouncilRoleConfig): Partial<CouncilTemplateConfig> {
  const nodeId = uniqueIdentifier(`node_${role.role_id}`, template.workflow.nodes.map((node) => node.node_id));
  const inputNode = template.workflow.nodes.find((node) => node.node_type === 'input');
  const chairNode = template.workflow.nodes.find((node) => node.node_type === 'chair');
  const nextNode = {
    node_id: nodeId,
    node_type: 'agent' as const,
    role_id: role.role_id,
    position: { x: 360, y: 80 + template.roles.length * 130 },
  };
  const edges = [...template.workflow.edges];
  if (inputNode) {
    edges.push({
      edge_id: nextIdentifier('edge', edges.map((edge) => edge.edge_id)),
      source_node_id: inputNode.node_id,
      target_node_id: nodeId,
    });
  }
  if (chairNode) {
    edges.push({
      edge_id: nextIdentifier('edge', edges.map((edge) => edge.edge_id)),
      source_node_id: nodeId,
      target_node_id: chairNode.node_id,
    });
  }
  return {
    roles: [...template.roles, role],
    workflow: {
      ...template.workflow,
      nodes: [...template.workflow.nodes, nextNode],
      edges,
    },
  };
}

function removeRoleFromWorkflow(template: CouncilTemplateConfig, roleId: string): Partial<CouncilTemplateConfig> {
  const node = template.workflow.nodes.find((item) => item.node_type === 'agent' && item.role_id === roleId);
  if (!node) return { roles: template.roles.filter((role) => role.role_id !== roleId) };
  return {
    roles: template.roles.filter((role) => role.role_id !== roleId),
    workflow: {
      ...template.workflow,
      nodes: template.workflow.nodes.filter((item) => item.node_id !== node.node_id),
      edges: template.workflow.edges.filter((edge) => edge.source_node_id !== node.node_id && edge.target_node_id !== node.node_id),
    },
  };
}

function updateRoleAndWorkflow(
  template: CouncilTemplateConfig,
  roleIndex: number,
  patch: Partial<CouncilRoleConfig>,
): Partial<CouncilTemplateConfig> {
  const previousRoleId = template.roles[roleIndex].role_id;
  const nextRoleId = patch.role_id || previousRoleId;
  return {
    roles: template.roles.map((role, index) => index === roleIndex ? { ...role, ...patch } : role),
    workflow: nextRoleId === previousRoleId ? template.workflow : {
      ...template.workflow,
      nodes: template.workflow.nodes.map((node) => (
        node.node_type === 'agent' && node.role_id === previousRoleId ? { ...node, role_id: nextRoleId } : node
      )),
    },
  };
}

function updateChairAndWorkflow(
  template: CouncilTemplateConfig,
  patch: Partial<CouncilChairConfig>,
): Partial<CouncilTemplateConfig> {
  const nextRoleId = patch.role_id || template.chair.role_id;
  return {
    chair: { ...template.chair, ...patch },
    workflow: nextRoleId === template.chair.role_id ? template.workflow : {
      ...template.workflow,
      nodes: template.workflow.nodes.map((node) => (
        node.node_type === 'chair' ? { ...node, role_id: nextRoleId } : node
      )),
    },
  };
}
