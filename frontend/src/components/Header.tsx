import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/graphql';
import { usePipelineStatus, useRunBillingCycle } from '../api/hooks';
import { formatDate, plural } from '../format';
import { useNotices } from '../notices/context';

export function Header() {
  return (
    <header className="app-header">
      <div className="brand">
        <img src="/favicon.svg" alt="" width={36} height={36} />
        <div>
          <h1>Subscription Order Platform</h1>
          <p className="tagline">
            Recurring orders: billing run → SNS topic → SQS queue → consumer, with a dead-letter queue and replay.
          </p>
        </div>
      </div>
      <PipelineStats />
      <BillingRunControl />
    </header>
  );
}

function PipelineStats() {
  const pipeline = usePipelineStatus();
  if (pipeline.isError) {
    return (
      <p className="stats stats-offline" role="alert">
        API unreachable: {errorMessage(pipeline.error)}
      </p>
    );
  }
  const stats = pipeline.data?.queueStats;
  return (
    <dl className="stats" aria-label="Order pipeline">
      <div className="stat">
        <dt>Broker</dt>
        <dd>{stats ? (stats.provider === 'aws' ? 'AWS SNS/SQS' : 'In-memory') : '…'}</dd>
      </div>
      <div className="stat">
        <dt>Queued</dt>
        <dd>{stats?.visible ?? '…'}</dd>
      </div>
      <div className="stat">
        <dt>In flight</dt>
        <dd>{stats?.inFlight ?? '…'}</dd>
      </div>
      <div className={`stat ${stats && stats.deadLetters > 0 ? 'stat-alert' : ''}`}>
        <dt>Dead letters</dt>
        <dd>{stats?.deadLetters ?? '…'}</dd>
      </div>
      <div className="stat">
        <dt>DLQ after</dt>
        <dd>{stats ? plural(stats.maxReceiveCount, 'receive') : '…'}</dd>
      </div>
    </dl>
  );
}

function BillingRunControl() {
  const [asOf, setAsOf] = useState('');
  const run = useRunBillingCycle();
  const { notify } = useNotices();

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    run.mutate(asOf || undefined, {
      onSuccess: (result) =>
        notify(
          result.ordersCreated > 0 ? 'success' : 'info',
          `Billing run as of ${formatDate(result.asOf)}: ${plural(result.ordersCreated, 'order')} created ` +
            `from ${plural(result.dueSubscriptions, 'due subscription')}.`,
        ),
      onError: (error) => notify('error', `Billing run failed: ${errorMessage(error)}`),
    });
  };

  return (
    <form className="billing-run" onSubmit={submit}>
      <label className="field compact">
        <span>Bill as of</span>
        <input
          type="date"
          value={asOf}
          onChange={(event) => setAsOf(event.target.value)}
          title="Leave empty for today (server time); pick a later date to simulate time passing"
        />
      </label>
      <button type="submit" className="button primary" disabled={run.isPending}>
        {run.isPending ? 'Running…' : 'Run billing cycle'}
      </button>
    </form>
  );
}
