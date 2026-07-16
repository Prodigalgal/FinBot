# TASK-20260716: 多 AI 证据共识与异构默认工作流

## 目标

接入 Gemini 3.5 Flash，并把默认工作流升级为多 AI 清洗、多 AI 压缩、独立验证和异构三轮辩论。

## 范围

- Provider/Secret/模型目录和最高推理强度默认值。
- 工作流节点契约、证据共识执行、审计持久化和 v6 迁移。
- Java/Web/PostgreSQL/Kustomize/线上 Provider 与工作流 smoke 验证。

## 非目标

- 不接入生产真实交易。
- 不将 AI 处理结果写回原始证据。
- 不激活 Gemini 图像模型参与文本研究。

## 验收标准

- 详见 `docs/requirements/34-multi-agent-evidence-consensus.md`。

## 状态

In progress.
