import { config } from '../config';

export interface GraphQLErrorDetail {
  message: string;
  path?: (string | number)[];
  extensions?: { classification?: string };
}

/** The server answered, but with GraphQL errors (validation, not found, business rule, ...). */
export class GraphQLRequestError extends Error {
  readonly errors: GraphQLErrorDetail[];

  constructor(errors: GraphQLErrorDetail[]) {
    super(errors.map((error) => error.message).join('; '));
    this.name = 'GraphQLRequestError';
    this.errors = errors;
  }
}

interface GraphQLResponse<TData> {
  data?: TData | null;
  errors?: GraphQLErrorDetail[];
}

/** POSTs one GraphQL operation to the configured endpoint and returns its data, or throws. */
export async function graphql<TData>(
  query: string,
  variables?: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<TData> {
  let response: Response;
  try {
    response = await fetch(config.graphqlUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/graphql-response+json, application/json',
      },
      body: JSON.stringify({ query, variables }),
      signal,
    });
  } catch (cause) {
    if (signal?.aborted) {
      throw cause;
    }
    throw new Error(`Cannot reach the API at ${config.graphqlUrl}`, { cause });
  }

  let body: GraphQLResponse<TData> | undefined;
  try {
    body = (await response.json()) as GraphQLResponse<TData>;
  } catch {
    body = undefined;
  }
  if (body?.errors?.length) {
    throw new GraphQLRequestError(body.errors);
  }
  if (!response.ok || body?.data == null) {
    throw new Error(`API request failed (HTTP ${response.status})`);
  }
  return body.data;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
