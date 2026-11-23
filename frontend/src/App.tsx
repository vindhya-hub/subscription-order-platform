import { CreateSubscriptionPanel } from './components/CreateSubscriptionPanel';
import { DeadLetterPanel } from './components/DeadLetterPanel';
import { FailureInjectionPanel } from './components/FailureInjectionPanel';
import { Header } from './components/Header';
import { OrdersPanel } from './components/OrdersPanel';
import { SubscriptionsPanel } from './components/SubscriptionsPanel';
import { config } from './config';

export function App() {
  return (
    <div className="app">
      <Header />
      <main className="layout">
        <div className="main-column">
          <SubscriptionsPanel />
          <OrdersPanel />
        </div>
        <aside className="side-column" aria-label="Actions">
          <CreateSubscriptionPanel />
          <DeadLetterPanel />
          <FailureInjectionPanel />
        </aside>
      </main>
      <footer className="app-footer muted small">
        GraphQL endpoint <code>{config.graphqlUrl}</code> · explore it in{' '}
        <a href="/graphiql" target="_blank" rel="noreferrer">
          GraphiQL
        </a>{' '}
        · live panels refresh every {config.pollIntervalMs / 1000}s
      </footer>
    </div>
  );
}
