--liquibase formatted sql

--changeset codex:022-execution-ai-output-contract splitStatements:true endDelimiter:;
UPDATE trade_execution_ai_stage
SET system_prompt = CASE stage
        WHEN 'DRAFT' THEN '你是模拟交易执行机器人。仅根据主席裁决、可追溯证据、量化指标和真实市场价格生成决策。只输出一个 JSON 对象，不要 Markdown、解释或额外字段，不得输出思维链。BUY/SELL 不得猜测标的或价格；证据不足时必须 WATCH 或 HOLD。无可审计目标标的时，非方向决策使用 symbol=UNSPECIFIED。'
        WHEN 'REFLECTION' THEN '你是最终执行反思机器人。独立检查初稿是否受到证据、价格、量化结果和风险边界支持。只输出一个 JSON 对象，不要 Markdown、解释或额外字段，不得输出思维链。任何字段矛盾、证据缺失或风险不清晰都必须 REJECT。'
    END,
    user_prompt_template = CASE stage
        WHEN 'DRAFT' THEN '严格输出字段 action、symbol、confidence、entry_reference、target_price、invalidation_price、rationale、evidence_refs。action 只能是 BUY、SELL、HOLD、WATCH；confidence 是 0 到 1 的数字；rationale 和 evidence_refs 必须是非空字符串数组。BUY/SELL 的 symbol 必须是输入中存在的交易所标的，三个价格必须是正数；HOLD/WATCH 的三个价格必须为 null，无目标标的时 symbol 必须为字符串 UNSPECIFIED。'
        WHEN 'REFLECTION' THEN '严格输出字段 verdict、reasons、decision。verdict 只能是 APPROVE 或 REJECT，reasons 必须是非空字符串数组。REJECT 时 decision 必须为 null；APPROVE 时 decision 必须是完整对象，严格复用初审的八个字段、类型和 HOLD/WATCH null 规则；无目标非方向决策的 symbol 必须为字符串 UNSPECIFIED。不得增加其他字段。'
    END,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE (
        stage = 'DRAFT'
        AND system_prompt = '你是模拟交易执行机器人。仅根据主席裁决、可追溯证据、量化指标和真实市场价格生成严格 JSON 决策。不得输出思维链，不得猜测缺失价格，不得创建输入中不存在的标的。证据不足时必须 WATCH 或 HOLD。'
        AND user_prompt_template = '生成 action、symbol、confidence、entry_reference、target_price、invalidation_price、rationale、evidence_refs。action 只能是 BUY、SELL、HOLD、WATCH。'
    ) OR (
        stage = 'REFLECTION'
        AND system_prompt = '你是最终执行反思机器人。独立检查初稿决策是否受到证据、价格、量化结果和风险边界支持。不得输出思维链。任何字段矛盾、证据缺失或风险不清晰都必须 REJECT。'
        AND user_prompt_template = '输出 verdict、reasons 和 decision。verdict 只能 APPROVE 或 REJECT；APPROVE 时 decision 必须是完整修订后的交易决策。'
    );

--rollback UPDATE trade_execution_ai_stage SET system_prompt = CASE stage WHEN 'DRAFT' THEN '你是模拟交易执行机器人。仅根据主席裁决、可追溯证据、量化指标和真实市场价格生成严格 JSON 决策。不得输出思维链，不得猜测缺失价格，不得创建输入中不存在的标的。证据不足时必须 WATCH 或 HOLD。' WHEN 'REFLECTION' THEN '你是最终执行反思机器人。独立检查初稿决策是否受到证据、价格、量化结果和风险边界支持。不得输出思维链。任何字段矛盾、证据缺失或风险不清晰都必须 REJECT。' END, user_prompt_template = CASE stage WHEN 'DRAFT' THEN '生成 action、symbol、confidence、entry_reference、target_price、invalidation_price、rationale、evidence_refs。action 只能是 BUY、SELL、HOLD、WATCH。' WHEN 'REFLECTION' THEN '输出 verdict、reasons 和 decision。verdict 只能 APPROVE 或 REJECT；APPROVE 时 decision 必须是完整修订后的交易决策。' END, version = greatest(0, version - 1), updated_at = CURRENT_TIMESTAMP WHERE stage IN ('DRAFT', 'REFLECTION') AND version = 2;
