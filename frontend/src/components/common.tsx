import type { UseQueryResult } from '@tanstack/react-query';
import { useId, type ReactNode } from 'react';
import { errorMessage } from '../api/graphql';
import type { OrderStatus, SubscriptionStatus } from '../api/types';
import { titleCase } from '../format';

export function StatusChip({ status }: { status: OrderStatus | SubscriptionStatus }) {
  return <span className={`chip chip-${status.toLowerCase()}`}>{titleCase(status)}</span>;
}

interface PanelProps {
  title: string;
  subtitle?: ReactNode;
  badge?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function Panel({ title, subtitle, badge, actions, className, children }: PanelProps) {
  const headingId = useId();
  return (
    <section className={`panel ${className ?? ''}`} aria-labelledby={headingId}>
      <header className="panel-header">
        <div>
          <h2 id={headingId}>
            {title}
            {badge}
          </h2>
          {subtitle && <p className="panel-subtitle">{subtitle}</p>}
        </div>
        {actions && <div className="panel-actions">{actions}</div>}
      </header>
      {children}
    </section>
  );
}

interface QueryStateProps<T> {
  query: UseQueryResult<T[]>;
  empty: ReactNode;
  children: (rows: T[]) => ReactNode;
}

/** Loading, error and empty states for a list query; renders the rows once there are some. */
export function QueryState<T>({ query, empty, children }: QueryStateProps<T>) {
  if (query.isPending) {
    return <p className="state">Loading…</p>;
  }
  if (query.isError) {
    return (
      <p className="state state-error" role="alert">
        {errorMessage(query.error)}
      </p>
    );
  }
  if (query.data.length === 0) {
    return <p className="state">{empty}</p>;
  }
  return <>{children(query.data)}</>;
}

interface FilterTabsProps<T extends string> {
  label: string;
  options: readonly T[];
  value: T | undefined;
  onChange: (value: T | undefined) => void;
}

/** "All" plus one tab per option. */
export function FilterTabs<T extends string>({ label, options, value, onChange }: FilterTabsProps<T>) {
  return (
    <div className="tabs" role="group" aria-label={label}>
      {[undefined, ...options].map((option) => (
        <button
          key={option ?? 'ALL'}
          type="button"
          className="tab"
          aria-pressed={value === option}
          onClick={() => onChange(option)}
        >
          {option ? titleCase(option) : 'All'}
        </button>
      ))}
    </div>
  );
}
