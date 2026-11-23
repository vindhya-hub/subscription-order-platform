import { useState } from 'react';
import { errorMessage } from '../api/graphql';
import { useChangeSubscription, useSubscriptions } from '../api/hooks';
import {
  SUBSCRIPTION_STATUSES,
  type Subscription,
  type SubscriptionAction,
  type SubscriptionStatus,
} from '../api/types';
import { formatDate, formatMoney, titleCase } from '../format';
import { useNotices } from '../notices/context';
import { FilterTabs, Panel, QueryState, StatusChip } from './common';

const ACTIONS: Record<SubscriptionStatus, SubscriptionAction[]> = {
  ACTIVE: ['pause', 'cancel'],
  PAUSED: ['resume', 'cancel'],
  CANCELLED: [],
};

const PAST_TENSE: Record<SubscriptionAction, string> = { pause: 'paused', resume: 'resumed', cancel: 'cancelled' };

export function SubscriptionsPanel() {
  const [status, setStatus] = useState<SubscriptionStatus | undefined>();
  const subscriptions = useSubscriptions(status);
  const change = useChangeSubscription();
  const { notify } = useNotices();

  const apply = (action: SubscriptionAction, subscription: Subscription) => {
    if (
      action === 'cancel' &&
      !window.confirm(`Cancel subscription #${subscription.id} for ${subscription.customer.name}? This is permanent.`)
    ) {
      return;
    }
    change.mutate(
      { action, id: subscription.id },
      {
        onSuccess: (updated) =>
          notify(
            'success',
            action === 'resume'
              ? `Subscription #${updated.id} resumed; next run ${formatDate(updated.nextRunDate)}.`
              : `Subscription #${updated.id} ${PAST_TENSE[action]}.`,
          ),
        onError: (error) => notify('error', errorMessage(error)),
      },
    );
  };

  return (
    <Panel
      title="Subscriptions"
      subtitle="Each active subscription is billed on its next run date, then the date moves to the next cycle."
      actions={
        <FilterTabs label="Filter by status" options={SUBSCRIPTION_STATUSES} value={status} onChange={setStatus} />
      }
    >
      <QueryState query={subscriptions} empty="No subscriptions here yet. Create one with the form.">
        {(rows) => (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">Customer</th>
                  <th scope="col">Plan</th>
                  <th scope="col">Schedule</th>
                  <th scope="col">Next run</th>
                  <th scope="col" className="numeric">
                    Per delivery
                  </th>
                  <th scope="col">Status</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((subscription) => {
                  const busy = change.isPending && change.variables?.id === subscription.id;
                  return (
                    <tr key={subscription.id}>
                      <td className="muted">{subscription.id}</td>
                      <td>
                        <div>{subscription.customer.name}</div>
                        <div className="muted small">{subscription.customer.email}</div>
                      </td>
                      <td>{subscription.plan.name}</td>
                      <td>
                        {subscription.quantity} × {titleCase(subscription.frequency)}
                      </td>
                      <td>{subscription.status === 'CANCELLED' ? '—' : formatDate(subscription.nextRunDate)}</td>
                      <td className="numeric">
                        {formatMoney(subscription.recurringAmount, subscription.plan.currency)}
                      </td>
                      <td>
                        <StatusChip status={subscription.status} />
                      </td>
                      <td className="row-actions">
                        {ACTIONS[subscription.status].map((action) => (
                          <button
                            key={action}
                            type="button"
                            className={`button small ${action === 'cancel' ? 'danger' : ''}`}
                            disabled={busy}
                            onClick={() => apply(action, subscription)}
                          >
                            {titleCase(action)}
                          </button>
                        ))}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
    </Panel>
  );
}
