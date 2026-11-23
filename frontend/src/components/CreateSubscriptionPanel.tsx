import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/graphql';
import { useCreateSubscription, useCustomers, usePlans } from '../api/hooks';
import { FREQUENCIES, type CreateSubscriptionInput, type Frequency } from '../api/types';
import { formatDate, formatMoney, titleCase } from '../format';
import { useNotices } from '../notices/context';
import { Panel } from './common';

type CustomerMode = 'existing' | 'new';

/** unitPrice x quantity, computed in cents to avoid floating-point artefacts in the preview. */
function deliveryTotal(unitPrice: string, quantity: number): string {
  return ((Math.round(Number(unitPrice) * 100) * quantity) / 100).toFixed(2);
}

export function CreateSubscriptionPanel() {
  const plans = usePlans();
  const customers = useCustomers();
  const create = useCreateSubscription();
  const { notify } = useNotices();

  const [customerMode, setCustomerMode] = useState<CustomerMode>('existing');
  const [customerId, setCustomerId] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [planId, setPlanId] = useState('');
  const [quantity, setQuantity] = useState(1);
  const [frequency, setFrequency] = useState<Frequency>('WEEKLY');
  const [startDate, setStartDate] = useState('');
  const [error, setError] = useState<string | null>(null);

  const activePlans = (plans.data ?? []).filter((plan) => plan.active);
  const knownCustomers = customers.data ?? [];
  // Fall back to sensible selections until the user picks something.
  const mode: CustomerMode = knownCustomers.length === 0 ? 'new' : customerMode;
  const selectedCustomerId = customerId || knownCustomers[0]?.id || '';
  const selectedPlan = activePlans.find((plan) => plan.id === planId) ?? activePlans[0];

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedPlan) {
      return;
    }
    const input: CreateSubscriptionInput = {
      planId: selectedPlan.id,
      quantity,
      frequency,
      ...(startDate ? { startDate } : {}),
      ...(mode === 'existing'
        ? { customerId: selectedCustomerId }
        : { newCustomer: { name: name.trim(), email: email.trim() } }),
    };
    create.mutate(input, {
      onSuccess: (subscription) => {
        setError(null);
        setName('');
        setEmail('');
        setQuantity(1);
        setStartDate('');
        notify(
          'success',
          `Subscription #${subscription.id} created for ${subscription.customer.name}: ${subscription.plan.name}, ` +
            `first run ${formatDate(subscription.nextRunDate)}.`,
        );
      },
      onError: (failure) => setError(errorMessage(failure)),
    });
  };

  return (
    <Panel title="New subscription" subtitle="Start a recurring order for an existing or a new customer.">
      <form className="form" onSubmit={submit}>
        <fieldset className="segmented" disabled={knownCustomers.length === 0}>
          <legend className="visually-hidden">Customer</legend>
          {(['existing', 'new'] as const).map((option) => (
            <label key={option} className={mode === option ? 'selected' : ''}>
              <input
                type="radio"
                name="customer-mode"
                value={option}
                checked={mode === option}
                onChange={() => setCustomerMode(option)}
              />
              {option === 'existing' ? 'Existing customer' : 'New customer'}
            </label>
          ))}
        </fieldset>

        {mode === 'existing' ? (
          <label className="field">
            <span>Customer</span>
            <select value={selectedCustomerId} onChange={(event) => setCustomerId(event.target.value)} required>
              {knownCustomers.map((customer) => (
                <option key={customer.id} value={customer.id}>
                  {customer.name} ({customer.email})
                </option>
              ))}
            </select>
          </label>
        ) : (
          <div className="field-row">
            <label className="field">
              <span>Name</span>
              <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} />
            </label>
            <label className="field">
              <span>Email</span>
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
                maxLength={254}
              />
            </label>
          </div>
        )}

        <label className="field">
          <span>Plan</span>
          <select
            value={selectedPlan?.id ?? ''}
            onChange={(event) => setPlanId(event.target.value)}
            required
            disabled={activePlans.length === 0}
          >
            {activePlans.map((plan) => (
              <option key={plan.id} value={plan.id}>
                {plan.name} · {formatMoney(plan.unitPrice, plan.currency)}
              </option>
            ))}
          </select>
        </label>

        <div className="field-row">
          <label className="field">
            <span>Quantity</span>
            <input
              type="number"
              min={1}
              max={100}
              value={quantity}
              onChange={(event) => setQuantity(Math.max(1, Math.min(100, Number(event.target.value) || 1)))}
              required
            />
          </label>
          <label className="field">
            <span>Frequency</span>
            <select value={frequency} onChange={(event) => setFrequency(event.target.value as Frequency)}>
              {FREQUENCIES.map((option) => (
                <option key={option} value={option}>
                  {titleCase(option)}
                </option>
              ))}
            </select>
          </label>
        </div>

        <label className="field">
          <span>First delivery</span>
          <input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
          <small className="muted">Leave empty to start today (server time).</small>
        </label>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <div className="form-footer">
          <span className="muted">
            {selectedPlan
              ? `${formatMoney(deliveryTotal(selectedPlan.unitPrice, quantity), selectedPlan.currency)} per delivery`
              : 'No plans available'}
          </span>
          <button type="submit" className="button primary" disabled={create.isPending || !selectedPlan}>
            {create.isPending ? 'Creating…' : 'Create subscription'}
          </button>
        </div>
      </form>
    </Panel>
  );
}
