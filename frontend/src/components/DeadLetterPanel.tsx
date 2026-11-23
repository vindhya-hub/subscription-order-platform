import { errorMessage } from '../api/graphql';
import { useDeadLetters, usePipelineStatus, useReplayDeadLetters } from '../api/hooks';
import type { DeadLetter } from '../api/types';
import { formatDateTime, formatMoney, plural, shortId } from '../format';
import { useNotices } from '../notices/context';
import { Panel, QueryState, StatusChip } from './common';

function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

export function DeadLetterPanel() {
  const deadLetters = useDeadLetters();
  const pipeline = usePipelineStatus();
  const replay = useReplayDeadLetters();
  const { notify } = useNotices();
  const count = deadLetters.data?.length ?? 0;
  const maxReceives = pipeline.data?.queueStats.maxReceiveCount;

  const replayMessages = (messageIds?: string[]) =>
    replay.mutate(messageIds, {
      onSuccess: (result) =>
        notify(
          result.replayed > 0 ? 'success' : 'info',
          result.replayed > 0
            ? `Replayed ${plural(result.replayed, 'message')} to the order queue.`
            : 'Nothing to replay.',
        ),
      onError: (error) => notify('error', `Replay failed: ${errorMessage(error)}`),
    });

  return (
    <Panel
      title="Dead-letter queue"
      className={count > 0 ? 'panel-alert' : ''}
      badge={count > 0 ? <span className="count">{count}</span> : null}
      subtitle={`Messages that failed ${maxReceives ? plural(maxReceives, 'delivery') : 'every allowed delivery'}. Replay sends them back to the order queue with a fresh receive count.`}
      actions={
        <button
          type="button"
          className="button"
          disabled={count === 0 || replay.isPending}
          onClick={() => replayMessages()}
        >
          Replay all
        </button>
      }
    >
      <QueryState query={deadLetters} empty="Empty: every order message has been processed.">
        {(letters) => (
          <ul className="dead-letters">
            {letters.map((letter) => (
              <DeadLetterItem
                key={letter.messageId}
                letter={letter}
                disabled={replay.isPending}
                onReplay={() => replayMessages([letter.messageId])}
              />
            ))}
          </ul>
        )}
      </QueryState>
    </Panel>
  );
}

interface DeadLetterItemProps {
  letter: DeadLetter;
  disabled: boolean;
  onReplay: () => void;
}

function DeadLetterItem({ letter, disabled, onReplay }: DeadLetterItemProps) {
  const { order } = letter;
  return (
    <li className="dead-letter">
      <div className="dead-letter-main">
        <div className="dead-letter-title">
          <strong>{order ? `Order #${order.id}` : 'Unreadable message'}</strong>
          {order && <StatusChip status={order.status} />}
        </div>
        {order && (
          <div className="muted small">
            {order.subscription.customer.name} · {order.subscription.plan.name} ·{' '}
            {formatMoney(order.amount, order.currency)} · {plural(order.attempts, 'attempt')}
          </div>
        )}
        {order?.lastError && <div className="error-text small">{order.lastError}</div>}
        <details>
          <summary className="muted small">
            Message {shortId(letter.messageId)} · sent {formatDateTime(letter.sentAt)}
          </summary>
          <pre>{prettyPayload(letter.payload)}</pre>
        </details>
      </div>
      <button type="button" className="button small" disabled={disabled} onClick={onReplay}>
        Replay
      </button>
    </li>
  );
}
