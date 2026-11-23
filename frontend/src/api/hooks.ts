import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { config } from '../config';
import { api } from './operations';
import type {
  CreateSubscriptionInput,
  FailureMode,
  OrderStatus,
  SubscriptionAction,
  SubscriptionStatus,
} from './types';

const ORDER_LIMIT = 50;

export const queryKeys = {
  plans: ['plans'],
  customers: ['customers'],
  subscriptions: ['subscriptions'],
  orders: ['orders'],
  deadLetters: ['deadLetters'],
  pipeline: ['pipeline'],
} as const;

export function usePlans() {
  return useQuery({ queryKey: queryKeys.plans, queryFn: ({ signal }) => api.plans(signal), staleTime: 60_000 });
}

export function useCustomers() {
  return useQuery({ queryKey: queryKeys.customers, queryFn: ({ signal }) => api.customers(signal) });
}

export function useSubscriptions(status?: SubscriptionStatus) {
  return useQuery({
    queryKey: [...queryKeys.subscriptions, status ?? 'ALL'],
    queryFn: ({ signal }) => api.subscriptions(status, signal),
    refetchInterval: config.pollIntervalMs * 5,
  });
}

/** Orders change asynchronously as the consumer works through the queue, so this one polls. */
export function useOrders(status?: OrderStatus) {
  return useQuery({
    queryKey: [...queryKeys.orders, status ?? 'ALL'],
    queryFn: ({ signal }) => api.orders(status, ORDER_LIMIT, signal),
    refetchInterval: config.pollIntervalMs,
  });
}

export function useDeadLetters() {
  return useQuery({
    queryKey: queryKeys.deadLetters,
    queryFn: ({ signal }) => api.deadLetters(signal),
    refetchInterval: config.pollIntervalMs,
  });
}

export function usePipelineStatus() {
  return useQuery({
    queryKey: queryKeys.pipeline,
    queryFn: ({ signal }) => api.pipeline(signal),
    refetchInterval: config.pollIntervalMs,
  });
}

function useInvalidate() {
  const client = useQueryClient();
  return (...keys: (readonly string[])[]) =>
    Promise.all(keys.map((queryKey) => client.invalidateQueries({ queryKey })));
}

export function useCreateSubscription() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: CreateSubscriptionInput) => api.createSubscription(input),
    onSuccess: () => invalidate(queryKeys.subscriptions, queryKeys.customers),
  });
}

export function useChangeSubscription() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ action, id }: { action: SubscriptionAction; id: string }) => api.changeSubscription(action, id),
    onSuccess: () => invalidate(queryKeys.subscriptions),
  });
}

export function useRunBillingCycle() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (asOf?: string) => api.runBillingCycle(asOf),
    onSuccess: () => invalidate(queryKeys.subscriptions, queryKeys.orders, queryKeys.pipeline),
  });
}

export function useReplayDeadLetters() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (messageIds?: string[]) => api.replayDeadLetters(messageIds),
    onSuccess: () => invalidate(queryKeys.deadLetters, queryKeys.orders, queryKeys.pipeline),
  });
}

export function useSetFailureInjection() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ mode, planCode }: { mode: FailureMode; planCode: string | null }) =>
      api.setFailureInjection(mode, planCode),
    onSuccess: () => invalidate(queryKeys.pipeline),
  });
}
