import { useState } from 'react';
import { useOrders } from '../api/hooks';
import { ORDER_STATUSES, type OrderStatus } from '../api/types';
import { config } from '../config';
import { formatDate, formatDateTime, formatMoney } from '../format';
import { FilterTabs, Panel, QueryState, StatusChip } from './common';

export function OrdersPanel() {
  const [status, setStatus] = useState<OrderStatus | undefined>();
  const orders = useOrders(status);

  return (
    <Panel
      title="Orders"
      subtitle={
        <>
          Created by billing runs and processed asynchronously by the queue consumer.{' '}
          <span className="live" title={`Refreshes every ${config.pollIntervalMs / 1000}s`}>
            Live
          </span>
        </>
      }
      actions={<FilterTabs label="Filter by status" options={ORDER_STATUSES} value={status} onChange={setStatus} />}
    >
      <QueryState query={orders} empty="No orders yet. Run a billing cycle to create some.">
        {(rows) => (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Order</th>
                  <th scope="col">Run date</th>
                  <th scope="col">Customer · plan</th>
                  <th scope="col" className="numeric">
                    Amount
                  </th>
                  <th scope="col">Status</th>
                  <th scope="col" className="numeric">
                    Attempts
                  </th>
                  <th scope="col">Last error</th>
                  <th scope="col">Updated</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((order) => (
                  <tr key={order.id}>
                    <td className="muted">#{order.id}</td>
                    <td>{formatDate(order.runDate)}</td>
                    <td>
                      <div>{order.subscription.customer.name}</div>
                      <div className="muted small">{order.subscription.plan.name}</div>
                    </td>
                    <td className="numeric">{formatMoney(order.amount, order.currency)}</td>
                    <td>
                      <StatusChip status={order.status} />
                    </td>
                    <td className="numeric">{order.attempts}</td>
                    <td className="error-text" title={order.lastError ?? undefined}>
                      {order.lastError ?? ''}
                    </td>
                    <td className="muted small nowrap">{formatDateTime(order.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
    </Panel>
  );
}
