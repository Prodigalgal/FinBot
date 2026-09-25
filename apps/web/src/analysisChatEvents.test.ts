import { describe, expect, it } from 'vitest';
import { aiOutputStreams, mergeChatEvent, mergeChatEventPages, restoreChatEvents } from './analysisChatEvents';
import type { ChatStreamEvent } from './analysisChatEvents';
import type { ResearchHistoryDetail, WorkflowEvent } from './types';

const event = (sequence: number, invocationId: string, chunkSequence: number, text: string): WorkflowEvent => ({
  eventId: `event_${sequence}`, runId: 'run_test001', sequence,
  occurredAt: '2026-09-25T00:00:00Z', invocationId, nodeId: `node_${invocationId}`,
  chunkSequence, text,
});

describe('analysis chat event replay', () => {
  it('deduplicates replayed events and keeps output separate for each AI invocation', () => {
    const first: ChatStreamEvent = { type: 'workflow.ai.text.delta', event: event(1, 'ai_a', 1, '甲') };
    const second: ChatStreamEvent = { type: 'workflow.ai.text.delta', event: event(2, 'ai_b', 1, '乙') };
    const third: ChatStreamEvent = { type: 'workflow.ai.text.delta', event: event(3, 'ai_a', 2, '丙') };
    const duplicateChunk: ChatStreamEvent = { type: 'workflow.ai.text.delta', event: event(4, 'ai_a', 2, '丙') };

    const merged = [third, first, second, first, duplicateChunk].reduce(mergeChatEvent, [] as ChatStreamEvent[]);

    expect(merged.map(({ event: stored }) => stored.sequence)).toEqual([1, 2, 3, 4]);
    expect(aiOutputStreams(merged)).toEqual([
      { invocationId: 'ai_a', nodeId: 'node_ai_a', text: '甲丙', firstSequence: 1 },
      { invocationId: 'ai_b', nodeId: 'node_ai_b', text: '乙', firstSequence: 2 },
    ]);
  });

  it('restores valid saved events while skipping damaged payloads', () => {
    const saved = event(7, 'ai_a', 1, '历史输出');
    const detail = {
      events: [
        { sequence: 7, eventType: 'workflow.ai.text.delta', payloadJson: JSON.stringify(saved), occurredAt: saved.occurredAt },
        { sequence: 8, eventType: 'workflow.ai.text.delta', payloadJson: '{bad', occurredAt: saved.occurredAt },
      ],
    } as ResearchHistoryDetail;

    expect(aiOutputStreams(restoreChatEvents(detail))[0]?.text).toBe('历史输出');
    expect(mergeChatEventPages(restoreChatEvents(detail), restoreChatEvents(detail))).toHaveLength(1);
  });
});
