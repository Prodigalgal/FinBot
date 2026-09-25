import type { ResearchHistoryDetail, WorkflowEvent } from './types';

export interface ChatStreamEvent {
  type: string;
  event: WorkflowEvent;
}

export interface AiOutputStream {
  invocationId: string;
  nodeId: string;
  text: string;
  firstSequence: number;
}

export const chatEventTypes = [
  'workflow.accepted',
  'workflow.stage.started',
  'workflow.progressed',
  'workflow.ai.text.delta',
  'workflow.agent.message',
  'workflow.completed',
  'workflow.failed',
] as const;

export function mergeChatEvent(events: ChatStreamEvent[], next: ChatStreamEvent): ChatStreamEvent[] {
  if (events.some(({ event }) => event.sequence === next.event.sequence)) return events;
  return [...events, next].sort((left, right) => left.event.sequence - right.event.sequence);
}

export function restoreChatEvents(detail: ResearchHistoryDetail): ChatStreamEvent[] {
  const restored = new Map<number, ChatStreamEvent>();
  for (const stored of detail.events) {
    try {
      const event = JSON.parse(stored.payloadJson) as WorkflowEvent;
      if (typeof event.sequence === 'number') {
        restored.set(event.sequence, { type: stored.eventType, event });
      }
    } catch {
      // A damaged saved frame must not hide later events.
    }
  }
  return [...restored.values()].sort((left, right) => left.event.sequence - right.event.sequence);
}

export function mergeChatEventPages(current: ChatStreamEvent[], restored: ChatStreamEvent[]): ChatStreamEvent[] {
  const bySequence = new Map(current.map((stored) => [stored.event.sequence, stored]));
  restored.forEach((stored) => bySequence.set(stored.event.sequence, stored));
  return [...bySequence.values()].sort((left, right) => left.event.sequence - right.event.sequence);
}

export function aiOutputStreams(events: ChatStreamEvent[]): AiOutputStream[] {
  const streams = new Map<string, AiOutputStream>();
  const seenChunks = new Set<string>();
  for (const { type, event } of events) {
    if (type !== 'workflow.ai.text.delta' || typeof event.text !== 'string') continue;
    const invocationId = idValue(event.invocationId);
    const nodeId = idValue(event.nodeId);
    if (!invocationId || !nodeId) continue;
    const chunkKey = `${invocationId}:${String(event.chunkSequence ?? event.sequence)}`;
    if (seenChunks.has(chunkKey)) continue;
    seenChunks.add(chunkKey);
    const current = streams.get(invocationId);
    if (current) {
      current.text += event.text;
    } else {
      streams.set(invocationId, { invocationId, nodeId, text: event.text, firstSequence: event.sequence });
    }
  }
  return [...streams.values()].sort((left, right) => left.firstSequence - right.firstSequence);
}

function idValue(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && 'value' in value && typeof value.value === 'string') {
    return value.value;
  }
  return null;
}
