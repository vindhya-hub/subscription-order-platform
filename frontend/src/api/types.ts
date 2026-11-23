export type Frequency = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY';
export type SubscriptionStatus = 'ACTIVE' | 'PAUSED' | 'CANCELLED';
export type OrderStatus = 'PENDING' | 'PROCESSING' | 'FULFILLED' | 'FAILED';
export type FailureMode = 'NONE' | 'TRANSIENT' | 'PERMANENT';

export const FREQUENCIES: readonly Frequency[] = ['WEEKLY', 'BIWEEKLY', 'MONTHLY'];
export const ORDER_STATUSES: readonly OrderStatus[] = ['PENDING', 'PROCESSING', 'FULFILLED', 'FAILED'];
export const SUBSCRIPTION_STATUSES: readonly SubscriptionStatus[] = ['ACTIVE', 'PAUSED', 'CANCELLED'];

/** Decimal scalars arrive as strings (e.g. "31.98") so no precision is lost on the way. */
export type Decimal = string;
/** ISO-8601 calendar date, e.g. "2026-11-22". */
export type IsoDate = string;
/** ISO-8601 instant, e.g. "2026-11-22T09:30:00Z". */
export type IsoDateTime = string;

export interface Plan {
  id: string;
  code: string;
  name: string;
  description: string | null;
  unitPrice: Decimal;
  currency: string;
  active: boolean;
}

export interface Customer {
  id: string;
  name: string;
  email: string;
}

export interface Subscription {
  id: string;
  status: SubscriptionStatus;
  quantity: number;
  frequency: Frequency;
  nextRunDate: IsoDate;
  recurringAmount: Decimal;
  createdAt: IsoDateTime;
  cancelledAt: IsoDateTime | null;
  customer: Customer;
  plan: Pick<Plan, 'id' | 'code' | 'name' | 'currency'>;
}

export interface Order {
  id: string;
  runDate: IsoDate;
  amount: Decimal;
  currency: string;
  status: OrderStatus;
  attempts: number;
  lastError: string | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  fulfilledAt: IsoDateTime | null;
  subscription: {
    id: string;
    customer: Pick<Customer, 'name'>;
    plan: Pick<Plan, 'code' | 'name'>;
  };
}

export interface DeadLetter {
  messageId: string;
  orderId: string | null;
  sentAt: IsoDateTime | null;
  payload: string;
  order: Order | null;
}

export interface QueueStats {
  provider: string;
  visible: number;
  inFlight: number;
  deadLetters: number;
  maxReceiveCount: number;
}

export interface FailureInjection {
  mode: FailureMode;
  planCode: string | null;
}

export interface PipelineStatus {
  queueStats: QueueStats;
  failureInjection: FailureInjection;
}

export interface BillingRunResult {
  asOf: IsoDate;
  dueSubscriptions: number;
  ordersCreated: number;
}

export interface ReplayResult {
  replayed: number;
  messageIds: string[];
}

export interface CreateSubscriptionInput {
  customerId?: string;
  newCustomer?: { name: string; email: string };
  planId: string;
  quantity: number;
  frequency: Frequency;
  startDate?: IsoDate;
}

export type SubscriptionAction = 'pause' | 'resume' | 'cancel';
