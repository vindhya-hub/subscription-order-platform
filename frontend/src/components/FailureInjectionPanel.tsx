import { useState } from 'react';
import { errorMessage } from '../api/graphql';
import { usePipelineStatus, usePlans, useSetFailureInjection } from '../api/hooks';
import type { FailureMode } from '../api/types';
import { titleCase } from '../format';
import { useNotices } from '../notices/context';
import { Panel } from './common';

const MODES: { mode: FailureMode; description: string }[] = [
  { mode: 'NONE', description: 'Payments succeed.' },
  { mode: 'TRANSIENT', description: 'The first delivery of each message fails; the retry succeeds.' },
  { mode: 'PERMANENT', description: 'Every delivery fails, so messages end up in the dead-letter queue.' },
];

export function FailureInjectionPanel() {
  const pipeline = usePipelineStatus();
  const plans = usePlans();
  const update = useSetFailureInjection();
  const { notify } = useNotices();
  const current = pipeline.data?.failureInjection;
  // The plan scope follows the server until the user changes it here.
  const [scopeChoice, setScopeChoice] = useState<string | null>(null);
  const scope = scopeChoice ?? current?.planCode ?? '';

  const apply = (mode: FailureMode) =>
    update.mutate(
      { mode, planCode: scope || null },
      {
        onSuccess: (settings) =>
          notify(
            'info',
            settings.mode === 'NONE'
              ? 'Failure injection off: payments succeed.'
              : `${titleCase(settings.mode)} failures now apply to ${settings.planCode ?? 'every plan'}.`,
          ),
        onError: (error) => notify('error', errorMessage(error)),
      },
    );

  return (
    <Panel
      title="Failure injection"
      subtitle="Deterministic failures in the simulated payment step, to watch retries, the DLQ and replay."
    >
      <label className="field">
        <span>Applies to</span>
        <select value={scope} onChange={(event) => setScopeChoice(event.target.value)}>
          <option value="">Every plan</option>
          {(plans.data ?? []).map((plan) => (
            <option key={plan.id} value={plan.code}>
              {plan.name}
            </option>
          ))}
        </select>
      </label>
      <div className="modes" role="group" aria-label="Failure mode">
        {MODES.map(({ mode, description }) => (
          <button
            key={mode}
            type="button"
            className="mode"
            aria-pressed={current?.mode === mode}
            disabled={update.isPending}
            onClick={() => apply(mode)}
          >
            <strong>{titleCase(mode)}</strong>
            <span>{description}</span>
          </button>
        ))}
      </div>
    </Panel>
  );
}
