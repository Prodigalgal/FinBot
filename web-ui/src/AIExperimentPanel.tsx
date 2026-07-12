import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import ScienceIcon from '@mui/icons-material/Science';
import {
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import type { Dispatch, SetStateAction } from 'react';

import type { AIConfigPayload, AIExperimentConfig, AIExperimentVariant } from './types';

type AIConfigForm = Pick<AIConfigPayload, 'sites' | 'task_bindings' | 'prompts' | 'council_templates' | 'experiments'>;

const inputSx = {
  '& .MuiOutlinedInput-root': { minHeight: 40, borderRadius: 1, bgcolor: 'background.paper' },
  '& .MuiInputBase-input': { fontSize: 13, lineHeight: 1.45 },
};

export function AIExperimentPanel({
  aiForm,
  setAiForm,
  tasks,
}: {
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
  tasks: AIConfigPayload['tasks'];
}) {
  const updateExperiment = (index: number, patch: Partial<AIExperimentConfig>) => {
    setAiForm((current) => {
      if (!current) return current;
      const target = { ...current.experiments[index], ...patch };
      const experiments = current.experiments.map((item, itemIndex) => {
        if (itemIndex === index) return target;
        if (target.enabled && item.enabled && item.task_id === target.task_id) return { ...item, enabled: false };
        return item;
      });
      return { ...current, experiments };
    });
  };

  const addExperiment = () => {
    setAiForm((current) => {
      if (!current) return current;
      const experimentId = nextIdentifier('experiment', current.experiments.map((item) => item.experiment_id));
      return {
        ...current,
        experiments: [...current.experiments, defaultExperiment(experimentId, current, tasks[0]?.task_id || 'ai_debate')],
      };
    });
  };

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'stretch', sm: 'center' }}>
            <ScienceIcon color="primary" />
            <Box sx={{ flexGrow: 1 }}>
              <Typography variant="h3">AI A/B 实验</Typography>
              <Typography variant="caption" color="text.secondary">
                同一运行与角色会稳定落在同一变体；同一 AI 环节只允许启用一个实验。
              </Typography>
            </Box>
            <Button variant="outlined" startIcon={<AddIcon />} onClick={addExperiment}>新增实验</Button>
          </Stack>
        </CardContent>
      </Card>

      {aiForm.experiments.length === 0 && (
        <Card>
          <CardContent><Typography variant="body2" color="text.secondary">当前没有实验，AI 继续使用固定站点、模型和 Prompt。</Typography></CardContent>
        </Card>
      )}

      {aiForm.experiments.map((experiment, experimentIndex) => (
        <Card key={`${experiment.experiment_id}-${experimentIndex}`}>
          <CardContent>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'stretch', sm: 'center' }} sx={{ mb: 1.5 }}>
              <Typography variant="h3" sx={{ flexGrow: 1 }}>{experiment.display_name || experiment.experiment_id}</Typography>
              <FormControlLabel
                control={<Switch checked={experiment.enabled} onChange={(event) => updateExperiment(experimentIndex, { enabled: event.target.checked })} />}
                label="启用"
              />
              <Tooltip title="删除实验">
                <IconButton onClick={() => setAiForm((current) => current ? { ...current, experiments: current.experiments.filter((_, index) => index !== experimentIndex) } : current)}>
                  <DeleteIcon />
                </IconButton>
              </Tooltip>
            </Stack>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25, mb: 1.5 }}>
              <TextField label="实验 ID" value={experiment.experiment_id} disabled sx={inputSx} />
              <TextField label="显示名称" value={experiment.display_name} onChange={(event) => updateExperiment(experimentIndex, { display_name: event.target.value })} sx={inputSx} />
              <TextField select label="AI 环节" value={experiment.task_id} onChange={(event) => updateExperiment(experimentIndex, { task_id: event.target.value })} sx={inputSx}>
                {tasks.map((task) => <MenuItem key={task.task_id} value={task.task_id}>{task.label}</MenuItem>)}
              </TextField>
            </Box>
            <Stack divider={<Divider />} spacing={0}>
              {experiment.variants.map((variant, variantIndex) => (
                <VariantEditor
                  key={`${variant.variant_id}-${variantIndex}`}
                  variant={variant}
                  sites={aiForm.sites}
                  disableDelete={experiment.variants.length <= 2}
                  onChange={(patch) => updateExperiment(experimentIndex, {
                    variants: experiment.variants.map((item, index) => index === variantIndex ? { ...item, ...patch } : item),
                  })}
                  onDelete={() => updateExperiment(experimentIndex, {
                    variants: experiment.variants.filter((_, index) => index !== variantIndex),
                  })}
                />
              ))}
            </Stack>
            <Button
              variant="text"
              startIcon={<AddIcon />}
              sx={{ mt: 1 }}
              onClick={() => updateExperiment(experimentIndex, {
                variants: [
                  ...experiment.variants,
                  defaultVariant(
                    nextIdentifier('variant', experiment.variants.map((item) => item.variant_id)),
                    aiForm,
                    experiment.variants.length,
                  ),
                ],
              })}
            >
              添加变体
            </Button>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}

function VariantEditor({
  variant,
  sites,
  disableDelete,
  onChange,
  onDelete,
}: {
  variant: AIExperimentVariant;
  sites: AIConfigPayload['sites'];
  disableDelete: boolean;
  onChange: (patch: Partial<AIExperimentVariant>) => void;
  onDelete: () => void;
}) {
  const selectedSite = sites.find((site) => site.site_id === variant.site_id);
  const models = variant.protocol === 'responses' ? selectedSite?.responses_models || [] : selectedSite?.chat_models || [];
  return (
    <Box sx={{ py: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 700, flexGrow: 1 }}>{variant.display_name || variant.variant_id}</Typography>
        <Tooltip title="删除变体"><span><IconButton size="small" onClick={onDelete} disabled={disableDelete}><DeleteIcon fontSize="small" /></IconButton></span></Tooltip>
      </Stack>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
        <TextField label="变体 ID" value={variant.variant_id} onChange={(event) => onChange({ variant_id: event.target.value })} sx={inputSx} />
        <TextField label="显示名称" value={variant.display_name} onChange={(event) => onChange({ display_name: event.target.value })} sx={inputSx} />
        <TextField label="权重" type="number" inputProps={{ min: 0.01, step: 0.1 }} value={variant.weight} onChange={(event) => onChange({ weight: Number(event.target.value) })} sx={inputSx} />
        <TextField select label="AI 站点" value={variant.site_id || ''} onChange={(event) => onChange({ site_id: event.target.value, model: '' })} sx={inputSx}>
          <MenuItem value="">继承环节配置</MenuItem>
          {sites.map((site) => <MenuItem key={site.site_id} value={site.site_id}>{site.display_name}</MenuItem>)}
        </TextField>
        <TextField select label="协议" value={variant.protocol || ''} onChange={(event) => onChange({ protocol: event.target.value || null, model: '' })} sx={inputSx}>
          <MenuItem value="">继承</MenuItem>
          <MenuItem value="chat">chat</MenuItem>
          <MenuItem value="responses">responses</MenuItem>
        </TextField>
        <TextField label="模型" value={variant.model || ''} onChange={(event) => onChange({ model: event.target.value })} select={models.length > 0} sx={inputSx}>
          {models.map((model) => <MenuItem key={model} value={model}>{model}</MenuItem>)}
        </TextField>
        <TextField select label="思考等级" value={variant.reasoning_effort || 'provider_default'} onChange={(event) => onChange({ reasoning_effort: event.target.value as AIExperimentVariant['reasoning_effort'] })} sx={inputSx}>
          <MenuItem value="provider_default">继承环节</MenuItem>
          <MenuItem value="none">关闭</MenuItem>
          <MenuItem value="minimal">极低</MenuItem>
          <MenuItem value="low">低</MenuItem>
          <MenuItem value="medium">中</MenuItem>
          <MenuItem value="high">高</MenuItem>
          <MenuItem value="xhigh">极高</MenuItem>
        </TextField>
        <TextField label="Prompt 附加指令" value={variant.system_prompt_append || ''} onChange={(event) => onChange({ system_prompt_append: event.target.value })} multiline minRows={2} sx={{ gridColumn: { md: 'span 2' }, ...inputSx }} />
      </Box>
    </Box>
  );
}

function defaultExperiment(
  experimentId: string,
  aiForm: AIConfigForm,
  taskId: string,
): AIExperimentConfig {
  return {
    experiment_id: experimentId,
    display_name: '新建模型对比实验',
    task_id: taskId,
    enabled: false,
    variants: [defaultVariant('control', aiForm, 0), defaultVariant('challenger', aiForm, 1)],
  };
}

function defaultVariant(variantId: string, aiForm: AIConfigForm, index: number): AIExperimentVariant {
  const sites = aiForm.sites.filter((site) => site.enabled);
  const site = sites[index % Math.max(1, sites.length)];
  return {
    variant_id: variantId,
    display_name: index === 0 ? '对照组' : '实验组',
    weight: 1,
    site_id: site?.site_id || null,
    protocol: 'chat',
    model: site?.default_chat_model || null,
    reasoning_effort: 'high',
    system_prompt_append: null,
    user_prompt_template: null,
  };
}

function nextIdentifier(prefix: string, existingIds: string[]): string {
  const existing = new Set(existingIds);
  let suffix = 1;
  while (existing.has(`${prefix}_${suffix}`)) suffix += 1;
  return `${prefix}_${suffix}`;
}
